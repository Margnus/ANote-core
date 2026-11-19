"""Verify explicit pending-preview recovery before a DEV-041 SQLite migration."""
import argparse
import base64
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import tempfile
import zipfile

from sqlite_checkpoint_fixture import Checkpoint, Rejected, commit, digest, open_store
from verify_sqlite_checkpoint_fixture import OFFLINE, codec_environment

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'
PIN = ROOT / 'reports/c1-pinned-sdk-20261117.json'
STDLIB_PIN = ROOT / 'reports/apple-sqlite-joint-checkpoint-20261117/verification.json'
ORACLE = ROOT / 'tools/probes/SqlitePendingRecoveryFixture.java'
STORE = ROOT / 'tools/sqlite_checkpoint_fixture.py'
SOURCE_NAME = 'prepare.ncp'
CONFIRMED_NAME = 'confirmed-final.ncp'


def sha(path):
    return digest(path.read_bytes()).hex()


def recover(java, jars, content, budget):
    run = subprocess.run([str(java), '-cp', os.pathsep.join(map(str, jars)), str(ORACLE), str(budget)],
                         input=content, capture_output=True, timeout=30, check=True)
    status, _, payload = run.stdout.partition(b'\n')
    if status != b'READY':
        if status not in {b'SOURCE_CAPACITY', b'INVALID_SOURCE', b'INPUT_CAPACITY', b'PENDING_COMMIT_REFUSED',
                          b'DOCUMENT_CAPACITY'} or payload:
            raise RuntimeError(run.stdout)
        raise Rejected(status.decode())
    identity, revision, canonical = payload.split(b'\n', 2)
    return Checkpoint(base64.b64decode(identity, validate=True).decode(), int(revision), canonical)


def worker(folder, operation, phase_cut, source, budget):
    folder = folder.resolve()
    if folder.parent != BUILD or not folder.name.startswith('dev041-pending-'):
        raise ValueError('invalid fixture directory')
    if source not in {SOURCE_NAME, CONFIRMED_NAME, 'truncated.ncp'}:
        raise ValueError('invalid fixture source')
    config = json.loads((folder / 'oracle.json').read_bytes())
    original = (folder / source).read_bytes()

    def phase(point):
        if point == phase_cut:
            print(json.dumps({'cut': point, 'pid': os.getpid()}), flush=True)
            os.kill(os.getpid(), signal.SIGKILL)

    try:
        checkpoint = recover(config['java'], config['jars'], original, budget)
        with open_store(folder) as db:
            receipt = commit(db, operation, None, checkpoint, original=original, folder=folder, phase=phase)
        result = {'status': receipt.status, 'revision': receipt.revision}
    except Rejected as error:
        result = {'status': str(error)}
    print(json.dumps(result))


def verify_matrix(folder, java, jars, original, expected):
    evidence = {}
    (folder / 'oracle.json').write_text(json.dumps({'java': str(java), 'jars': list(map(str, jars))}), encoding='utf-8')
    checkpoint = recover(java, jars, original, 2248)
    assert checkpoint.canonical == expected
    inputs = {SOURCE_NAME: original, CONFIRMED_NAME: expected, 'truncated.ncp': original[:-1]}
    for name, contents in inputs.items():
        (folder / name).write_bytes(contents)
    with open_store(folder, initialize=True):
        pass

    def invoke(label, want, *, cut='none', source=SOURCE_NAME, budget=2248, operation='recover-legacy'):
        run = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--worker', str(folder), operation,
                              cut, source, str(budget)], capture_output=True, timeout=45)
        assert run.returncode == (0 if cut == 'none' else -signal.SIGKILL), (label, run)
        result = json.loads(run.stdout)
        if cut == 'none':
            assert result == want, (label, result)
        else:
            assert result['cut'] == cut and result['pid'] > 0, result
        for name, contents in inputs.items():
            assert (folder / name).read_bytes() == contents
        evidence[label] = result

    def assert_rows(committed):
        with open_store(folder) as db:
            assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
            assert db.execute('PRAGMA foreign_key_check').fetchall() == []
            document = [(checkpoint.document_id, checkpoint.revision, expected)] if committed else []
            assert db.execute('SELECT id, revision, checkpoint FROM document').fetchall() == document
            archived = [(checkpoint.document_id, original)] if committed else []
            assert db.execute('SELECT document_id, original FROM legacy_source').fetchall() == archived
            operation = [('recover-legacy', checkpoint.document_id, checkpoint.revision,
                          digest(expected), digest(original))] if committed else []
            assert db.execute('SELECT id, document_id, revision, checkpoint_sha256, source_sha256 '
                              'FROM operation').fetchall() == operation

    invoke('confirmed_document_requires_normal_import', {'status': 'INVALID_SOURCE'}, source=CONFIRMED_NAME)
    assert_rows(False)
    invoke('truncated_refused', {'status': 'INVALID_SOURCE'}, source='truncated.ncp')
    assert_rows(False)
    invoke('capacity_refused', {'status': 'INPUT_CAPACITY'}, budget=2247)
    assert_rows(False)
    invoke('dies_before_commit', None, cut='before-commit')
    assert_rows(False)
    invoke('dies_after_commit_before_receipt', None, cut='after-commit')
    assert_rows(True)
    invoke('same_operation_replays', {'status': 'REPLAYED', 'revision': checkpoint.revision})
    assert_rows(True)
    invoke('different_operation_cannot_reimport', {'status': 'STALE_BASE'}, operation='another-import')
    assert_rows(True)

    return checkpoint, evidence


def highlighter_fixture(original, expected):
    # The archived inputs are ASCII and contain one pending stroke, with its own length-delimited body.
    assert original.isascii() and expected.isascii()
    magic, metadata, frame, body = original.split(b'\n', 3)
    base_size, ink_size, operation = frame.split(b'\t')
    base_end = int(base_size)
    ink_end = base_end + int(ink_size)
    base, ink, tail = body[:base_end], body[base_end:ink_end], body[ink_end:]
    before, after = b'\tMONOLINE\t1\t', b'\tHIGHLIGHTER\t1\t'
    assert before not in base and ink.count(before) == expected.count(before) == 1
    assert tail == b'RECOVERY-END\n'
    ink = ink.replace(before, after)
    updated_frame = b'\t'.join((base_size, str(len(ink)).encode('ascii'), operation))
    return b'\n'.join((magic, metadata, updated_frame, base + ink + tail)), expected.replace(before, after)


def verify(stdlib, *, current_highlighter=False):
    pin = json.loads(PIN.read_bytes())
    expected_fixtures = pin['apple']['fixture_sha256']
    fixtures = {Path(name).name: ROOT / name for name in expected_fixtures}
    for name, expected in expected_fixtures.items():
        assert sha(ROOT / name) == expected, name
    original = fixtures[SOURCE_NAME].read_bytes()
    expected = fixtures[CONFIRMED_NAME].read_bytes()
    tool_inputs = [Path(__file__).resolve(), STORE, ORACLE, PIN, *fixtures.values()]
    with tempfile.TemporaryDirectory(prefix='dev041-pending-', dir=BUILD) as directory:
        folder = Path(directory)
        if current_highlighter:
            command, artifacts = codec_environment()
            java, _, classpath, _ = command
            jars = classpath.split(os.pathsep)
            original, expected = highlighter_fixture(original, expected)
            tool_inputs.extend(ROOT / name for name in (
                'tools/verify_sqlite_checkpoint_fixture.py',
                'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewDocumentReader.kt',
                'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewRecoveryCodec.kt',
            ))
            artifact_bindings = {'current_jvm_sha256': artifacts,
                                 'offline_jvm_receipt_sha256': sha(OFFLINE),
                                 'derived_fixture_sha256': {SOURCE_NAME: digest(original).hex(),
                                                            CONFIRMED_NAME: digest(expected).hex()}}
        else:
            expected_stdlib = json.loads(STDLIB_PIN.read_bytes())['host_jvm_sha256']['kotlin-stdlib-2.2.21.jar']
            assert sha(stdlib) == expected_stdlib
            jars = [stdlib.resolve()]
            artifacts = {}
            for name, spec in pin['android']['rebuilt_aars'].items():
                artifact = ROOT / spec['artifact']
                artifacts[name] = sha(artifact)
                assert artifacts[name] == spec['sha256'], name
                jar = folder / (name + '.jar')
                with zipfile.ZipFile(artifact) as archive:
                    jar.write_bytes(archive.read('classes.jar'))
                jars.append(jar)
            java = Path(os.environ['JAVA_HOME']) / 'bin/java'
            tool_inputs.append(STDLIB_PIN)
            artifact_bindings = {'candidate_source_commit': pin['candidate_source_commit'],
                                 'android_aars_sha256': artifacts, 'kotlin_stdlib_sha256': expected_stdlib}
        checkpoint, evidence = verify_matrix(folder, java, jars, original, expected)

    profile = 'current JVM HIGHLIGHTER/1' if current_highlighter else 'fixed C1 Java recovery oracle'
    return {
        'status': 'passed', 'scope': f'Host SQLite and {profile}; synthetic source; isolated migration',
        'sqlite_version': sqlite3.sqlite_version, 'checkpoint_revision': checkpoint.revision,
        'recovery_admitted_bytes': 2248, 'source_unchanged': True, 'observations': evidence,
        **artifact_bindings,
        'source_sha256': {path.relative_to(ROOT).as_posix(): sha(path) for path in tool_inputs},
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    decoder = parser.add_mutually_exclusive_group()
    decoder.add_argument('--stdlib', type=Path, help='Use the fixed C1 AARs')
    decoder.add_argument('--current-highlighter', action='store_true', help='Use a verified current offline JVM build')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--worker', nargs=5, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.worker:
        folder, operation, cut, source, budget = args.worker
        worker(Path(folder), operation, cut, source, int(budget))
    else:
        if not args.output or not (args.stdlib or args.current_highlighter):
            parser.error('choose --stdlib or --current-highlighter and pass --output')
        report = verify(args.stdlib, current_highlighter=args.current_highlighter)
        with args.output.open('x', encoding='utf-8') as stream:
            json.dump(report, stream, indent=2)
            stream.write('\n')
