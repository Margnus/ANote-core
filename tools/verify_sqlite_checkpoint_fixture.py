"""Run the DEV-041 isolated host store against synthetic, codec-validated fixtures."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import sys
import tempfile

from sqlite_checkpoint_fixture import Checkpoint, Rejected, commit, digest, open_store
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]
V1 = ROOT / 'fixtures/storage/dev041-legacy-v1.ncp'
V2 = ROOT / 'fixtures/transfer/android-paged-v2.ncp'
BUILD = ROOT / 'build'
OFFLINE = ROOT / 'reports/offline-jvm-verification.json'
DECODE = ROOT / 'tools/probes/SqliteLegacyFixture.java'
SCOPE = 'dev041-checkpoint-fixture-v1'
# Windows os.kill with a non-console signal calls TerminateProcess using that exit code.
CUT_SIGNAL = signal.SIGTERM if os.name == 'nt' else signal.SIGKILL
CUT_EXIT_CODE = int(CUT_SIGNAL) if os.name == 'nt' else -int(CUT_SIGNAL)
CUT_METHOD = 'TerminateProcess' if os.name == 'nt' else 'SIGKILL'
ATTACHMENTS = {
    'attached': ('resource-a.bin', 'resource-b.bin'),
    'attached-reversed': ('resource-b.bin', 'resource-a.bin'),
    'attached-conflict': ('resource-a.bin',),
}


def raw_hash(path):
    return digest(path.read_bytes()).hex()


def codec_environment():
    report = json.loads(OFFLINE.read_text(encoding='utf-8'))
    if report['status'] != 'passed':
        raise RuntimeError('run tools/verify_offline_jvm.py first')
    jars = [ROOT / f'canvas-core/build/offline-jvm/{module}-main.jar' for module in ('canvas', 'shared')]
    for relative, expected in report['source_and_fixture_sha256'].items():
        if relative.startswith(('canvas-core/src/', 'shared-core/src/')) and verification_digest(ROOT / relative) != expected:
            raise RuntimeError(f'stale Kotlin build input: {relative}')
    for path in jars:
        if raw_hash(path) != report['output_jar_sha256'][path.relative_to(ROOT).as_posix()]:
            raise RuntimeError(f'stale Kotlin build output: {path}')
    stdlib_rel, = (key for key in report['compiler_and_runtime_sha256'] if key.endswith('/kotlin-stdlib-2.2.21.jar'))
    cache = Path(os.environ.get('GRADLE_USER_HOME', ROOT / '.gradle-user-home')) / 'caches/modules-2/files-2.1'
    stdlib = cache / stdlib_rel
    if raw_hash(stdlib) != report['compiler_and_runtime_sha256'][stdlib_rel]:
        raise RuntimeError('Kotlin runtime differs from offline build')
    command = [str(Path(os.environ['JAVA_HOME']) / 'bin/java'), '-cp',
               os.pathsep.join(map(str, [*jars, stdlib])), str(DECODE)]
    return command, {path.name: raw_hash(path) for path in [*jars, stdlib]}


def decode(command, content):
    result = subprocess.run(command, input=content, capture_output=True, timeout=25)
    if result.returncode != 0:
        if b'Exception in thread "main"' not in result.stderr or not any(
            name in result.stderr for name in (b'IllegalArgumentException', b'MalformedInputException')
        ):
            raise RuntimeError(result.stderr.decode(errors='replace'))
        raise Rejected('INVALID_SOURCE')
    identity, revision, canonical = result.stdout.split(b'\n', 2)
    return Checkpoint(base64.b64decode(identity, validate=True).decode(), int(revision), canonical)


def worker(folder, op, base, source, kind, fault):
    # All workers are children of the verifier. Read and write only its fresh synthetic directory.
    if folder.parent != BUILD.resolve() or not folder.name.startswith('dev041-checkpoint-'):
        raise ValueError('invalid fixture directory')
    if (folder / '.fixture-scope').read_text(encoding='utf-8') != SCOPE or source not in {
        'legacy.ncp', 'transfer.ncp', 'corrupt.ncp', 'edit.ncp', 'other.ncp', 'large.ncp', 'changed-source.ncp', 'next-edit.ncp',
    }:
        raise ValueError('invalid fixture scope')
    command, _ = codec_environment()
    content = (folder / source).read_bytes()

    def phase(point):
        if fault == point:
            print(json.dumps({'cut': point, 'pid': os.getpid()}), flush=True)
            os.kill(os.getpid(), CUT_SIGNAL)

    try:
        checkpoint = decode(command, content)
        with open_store(folder) as db:
            if fault == 'capacity-full':
                pages, = db.execute('PRAGMA page_count').fetchone()
                db.execute(f'PRAGMA max_page_count={pages}')
            receipt = commit(db, op, None if base == 'new' else int(base), checkpoint,
                             original=content if kind == 'import' or kind in ATTACHMENTS else None,
                             resources=tuple((folder / name).read_bytes() for name in ATTACHMENTS.get(kind, ())),
                             folder=folder, phase=phase)
        result = {'status': receipt.status, 'revision': receipt.revision}
    except Rejected as error:
        result = {'status': str(error)}
    except sqlite3.DatabaseError as error:
        # SQLITE_FULL is stable across Python versions (sqlite_errorcode was added in 3.11).
        if fault != 'capacity-full' or str(error) != 'database or disk is full':
            raise
        result = {'status': 'SQLITE_FULL'}
    print(json.dumps(result), flush=True)


def verify_resource_import(imported, transferred, v1, v2):
    payloads = {'resource-a.bin': b'dev041-synthetic-attachment-A\x00' * 129,
                'resource-b.bin': b'dev041-synthetic-attachment-B\x00' * 133}
    inputs = {'legacy.ncp': v1, 'transfer.ncp': v2, **payloads}
    manifest = sorted((digest(data).hex(), len(data)) for data in payloads.values())
    expected_files = {digest(data).hex(): data for data in payloads.values()}
    observations = {}
    with tempfile.TemporaryDirectory(prefix='dev041-checkpoint-', dir=BUILD) as temp:
        folder = Path(temp)
        (folder / '.fixture-scope').write_text(SCOPE, encoding='utf-8')
        for name, data in inputs.items():
            (folder / name).write_bytes(data)
        with open_store(folder, initialize=True):
            pass

        def invoke(label, expected, *, operation='attach-legacy', source='legacy.ncp', kind='attached', cut='none'):
            result = subprocess.run(
                [sys.executable, str(Path(__file__).resolve()), '--worker', str(folder), operation,
                 'new', source, kind, cut],
                capture_output=True, timeout=45,
            )
            assert result.returncode == (CUT_EXIT_CODE if cut != 'none' else 0), (label, result)
            observation = json.loads(result.stdout)
            if cut == 'none':
                assert observation == expected, (label, observation)
            else:
                assert observation['cut'] == cut and observation['pid'] > 0
            observations[label] = observation

        def snapshot():
            with open_store(folder) as db:
                assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                assert db.execute('PRAGMA foreign_key_check').fetchall() == []
                return {
                    'documents': db.execute('SELECT id,revision,checkpoint FROM document ORDER BY id').fetchall(),
                    'sources': db.execute('SELECT document_id,original FROM legacy_source ORDER BY document_id').fetchall(),
                    'operations': db.execute('SELECT id,document_id FROM operation ORDER BY id').fetchall(),
                    'resources': db.execute('SELECT sha256,size FROM resource ORDER BY sha256').fetchall(),
                    'references': db.execute('SELECT operation_id,sha256 FROM operation_resource ORDER BY 1,2').fetchall(),
                    'tasks': db.execute('SELECT operation_id,document_id FROM import_task').fetchall(),
                    'pins': db.execute('SELECT sha256,size FROM import_task_resource ORDER BY sha256').fetchall(),
                }

        def published_files():
            return {path.name: path.read_bytes() for path in (folder / 'resources').iterdir()}

        invoke('previous_document', {'status': 'COMMITTED', 'revision': transferred.revision},
               operation='existing', source='transfer.ncp', kind='import')
        previous = {
            'documents': [(transferred.document_id, transferred.revision, transferred.canonical)],
            'sources': [(transferred.document_id, v2)],
            'operations': [('existing', transferred.document_id)],
            'resources': [], 'references': [], 'tasks': [], 'pins': [],
        }
        assert snapshot() == previous
        invoke('one_resource_published', None, cut='resource-published')
        pending = {**previous, 'tasks': [('attach-legacy', imported.document_id)], 'pins': manifest}
        assert snapshot() == pending
        first_sha = manifest[0][0]
        assert published_files() == {first_sha: expected_files[first_sha]}
        published_inode = (folder / 'resources' / first_sha).stat().st_ino

        invoke('pending_manifest_conflict', {'status': 'OPERATION_CONFLICT'}, kind='attached-conflict')
        invoke('pending_operation_reserved_document', {'status': 'DOCUMENT_RESERVED'}, operation='other-import', kind='import')
        invoke('pending_operation_cannot_bypass_manifest', {'status': 'OPERATION_CONFLICT'}, kind='import')
        assert snapshot() == pending
        assert published_files() == {first_sha: expected_files[first_sha]}

        second_sha = manifest[1][0]
        occupied = folder / 'resources' / second_sha
        invalid_target = b'X' * len(expected_files[second_sha])
        occupied.write_bytes(invalid_target)
        invoke('occupied_hash_refuses_overwrite', {'status': 'REPAIR_REQUIRED'})
        assert snapshot() == pending
        assert published_files() == {first_sha: expected_files[first_sha], second_sha: invalid_target}
        occupied.unlink()  # Remove only the synthetic corruption that this verifier injected.

        invoke('joint_transaction_uncommitted', None, cut='before-commit')
        assert snapshot() == pending
        assert published_files() == expected_files

        invoke('joint_transaction_committed_before_receipt', None, cut='after-commit')
        committed = {
            'documents': sorted([*previous['documents'], (imported.document_id, imported.revision, imported.canonical)]),
            'sources': sorted([*previous['sources'], (imported.document_id, v1)]),
            'operations': [('attach-legacy', imported.document_id), *previous['operations']],
            'resources': manifest,
            'references': [('attach-legacy', sha) for sha, _ in manifest],
            'tasks': [], 'pins': [],
        }
        assert snapshot() == committed
        assert published_files() == expected_files
        assert (folder / 'resources' / first_sha).stat().st_ino == published_inode

        invoke('replay_resource_order_independent', {'status': 'REPLAYED', 'revision': imported.revision},
               kind='attached-reversed')
        invoke('committed_manifest_conflict', {'status': 'OPERATION_CONFLICT'}, kind='attached-conflict')
        damaged = bytes([expected_files[first_sha][0] ^ 1]) + expected_files[first_sha][1:]
        (folder / 'resources' / first_sha).write_bytes(damaged)
        invoke('same_size_corruption_refuses_receipt', {'status': 'REPAIR_REQUIRED'})
        assert snapshot() == committed
        assert published_files() == {**expected_files, first_sha: damaged}
        assert all((folder / name).read_bytes() == data for name, data in inputs.items())
    return {'observations': observations, 'resource_manifest': manifest,
            'partial_publication_retains_all_task_pins': True,
            'joint_commit_transfers_pins_to_references': True, 'published_inode_reused': True}


def verify():
    command, runtime_hashes = codec_environment()
    v1, v2 = V1.read_bytes(), V2.read_bytes()
    imported, transferred = decode(command, v1), decode(command, v2)
    header, title = b'D\tlegacy-fixture\t', '旧标题😀'.encode()
    edit = v1.replace(header + title + b'\t2\t1\t2\n', header + b'Edited\t2\t1\t3\n', 1)
    next_edit = edit.replace(header + b'Edited\t2\t1\t3\n', header + b'Next\t2\t1\t4\n', 1)
    other = edit.replace(b'Edited\t2', b'Other\t2', 1)
    large = edit.replace(b'S\tink-1\t-1\t2.5\t2\nP\t1\t2\t0.3\t123\nP\t3\t4\t0.8\t456\n',
                         b'S\tink-1\t-1\t2.5\t4000\n' +
                         b''.join(f'P\t1\t2\t0.5\t{i}\n'.encode() for i in range(4000)))
    updated = decode(command, edit)
    assert imported.document_id == 'legacy-fixture' and imported.revision == 2 and updated.revision == 3
    assert imported.canonical.startswith(b'NCPREVIEW\t2\n') and title in imported.canonical
    assert [line for line in imported.canonical.splitlines() if line.startswith(b'P\t')] == [
        b'P\t1.0\t2.0\t0.3\t123', b'P\t3.0\t4.0\t0.8\t456',
    ]
    assert transferred.canonical == v2
    assert len(decode(command, large).canonical) > 64 * 1024

    fixtures = {'legacy.ncp': v1, 'transfer.ncp': v2, 'corrupt.ncp': v1[:len(v1) // 2],
                'edit.ncp': edit, 'other.ncp': other, 'large.ncp': large, 'next-edit.ncp': next_edit,
                'changed-source.ncp': v1.replace(b'P\t1\t2\t', b'P\t1.0\t2.0\t', 1)}
    events = {}
    with tempfile.TemporaryDirectory(prefix='dev041-checkpoint-', dir=BUILD) as temp:
        folder = Path(temp)
        (folder / '.fixture-scope').write_text(SCOPE, encoding='utf-8')
        for name, content in fixtures.items():
            (folder / name).write_bytes(content)
        with open_store(folder, initialize=True):
            pass

        def snapshot():
            with open_store(folder) as db:
                assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                assert db.execute('PRAGMA foreign_key_check').fetchall() == []
                return (
                    db.execute('SELECT id,revision,checkpoint FROM document ORDER BY id').fetchall(),
                    db.execute('SELECT document_id,original FROM legacy_source ORDER BY document_id').fetchall(),
                    db.execute('SELECT id,document_id,base_revision,revision FROM operation ORDER BY id').fetchall(),
                )

        def invocation(op, base, source, kind='write', fault='none'):
            return [sys.executable, str(Path(__file__).resolve()), '--worker', str(folder), op,
                    str(base), source, kind, fault]

        def expect(label, expected, *args, cut=None):
            result = subprocess.run(invocation(*args), capture_output=True, timeout=45)
            assert result.returncode == (CUT_EXIT_CODE if cut else 0), (label, result)
            obj = json.loads(result.stdout)
            if cut:
                assert obj['cut'] == cut and obj['pid'] > 0, obj
            else:
                assert obj == expected, (label, obj)
            events[label] = obj

        empty = ([], [], [])
        expect('bad_legacy', {'status': 'INVALID_SOURCE'}, 'import-v1', 'new', 'corrupt.ncp', 'import')
        assert snapshot() == empty
        expect('import_before_commit', None, 'import-v1', 'new', 'legacy.ncp', 'import',
               'before-commit', cut='before-commit')
        assert snapshot() == empty
        expect('import_after_commit_lost_receipt', None, 'import-v1', 'new', 'legacy.ncp', 'import',
               'after-commit', cut='after-commit')
        first = ([(imported.document_id, 2, imported.canonical)], [(imported.document_id, v1)],
                 [('import-v1', imported.document_id, None, 2)])
        assert snapshot() == first
        expect('import_retry', {'status': 'REPLAYED', 'revision': 2}, 'import-v1', 'new', 'legacy.ncp', 'import')
        expect('same_canonical_different_source', {'status': 'OPERATION_CONFLICT'},
               'import-v1', 'new', 'changed-source.ncp', 'import')
        expect('real_sqlite_full', {'status': 'SQLITE_FULL'}, 'edit-v1', 2, 'large.ncp', 'write', 'capacity-full')
        assert snapshot() == first
        expect('edit_before_commit', None, 'edit-v1', 2, 'edit.ncp', 'write', 'before-commit', cut='before-commit')
        assert snapshot() == first
        expect('edit_after_commit_lost_receipt', None, 'edit-v1', 2, 'edit.ncp', 'write', 'after-commit', cut='after-commit')
        last = ([(imported.document_id, 3, updated.canonical)], first[1],
                [('edit-v1', imported.document_id, 2, 3), *first[2]])
        assert snapshot() == last
        expect('edit_retry', {'status': 'REPLAYED', 'revision': 3}, 'edit-v1', 2, 'edit.ncp')
        expect('retry_older_operation', {'status': 'REPLAYED', 'revision': 2}, 'import-v1', 'new', 'legacy.ncp', 'import')
        expect('same_id_other_bytes', {'status': 'OPERATION_CONFLICT'}, 'edit-v1', 2, 'other.ncp')
        expect('stale_writer', {'status': 'STALE_BASE'}, 'other-edit', 2, 'other.ncp')
        assert snapshot() == last

        import_v2 = invocation('import-v2', 'new', 'transfer.ncp', 'import')
        with ThreadPoolExecutor(max_workers=2) as executor:
            children = [executor.submit(subprocess.run, import_v2, capture_output=True, timeout=45) for _ in range(2)]
            results = [child.result() for child in children]
        for child in results:
            assert child.returncode == 0, child.stderr.decode(errors='replace')
        answers = [json.loads(child.stdout) for child in results]
        statuses = sorted(answer['status'] for answer in answers)
        assert statuses == ['COMMITTED', 'REPLAYED']
        assert all(answer['revision'] == transferred.revision for answer in answers)
        events['concurrent_v2_retry'] = statuses
        assert snapshot() == (
            sorted([*last[0], (transferred.document_id, transferred.revision, v2)]),
            sorted([*last[1], (transferred.document_id, v2)]),
            sorted([*last[2], ('import-v2', transferred.document_id, None, transferred.revision)]),
        )

        with open_store(folder) as db:
            corrupted = updated.canonical.replace(b'Edited', b'BROKEN', 1)
            assert len(corrupted) == len(updated.canonical) and corrupted != updated.canonical
            db.execute('UPDATE document SET checkpoint=? WHERE id=?', (corrupted, imported.document_id))
        damaged = snapshot()
        expect('damaged_checkpoint_replay', {'status': 'REPAIR_REQUIRED'}, 'edit-v1', 2, 'edit.ncp')
        expect('damaged_checkpoint_new_write', {'status': 'REPAIR_REQUIRED'}, 'next-edit', 3, 'next-edit.ncp')
        assert snapshot() == damaged

        with open_store(folder) as db:
            db.execute('UPDATE document SET checkpoint=? WHERE id=?', (updated.canonical, imported.document_id))
        expect('repaired_checkpoint_can_advance', {'status': 'COMMITTED', 'revision': 4}, 'next-edit', 3, 'next-edit.ncp')
        advanced = snapshot()
        assert (imported.document_id, 4, decode(command, next_edit).canonical) in advanced[0]
        assert advanced[1] == damaged[1]
        assert ('next-edit', imported.document_id, 3, 4) in advanced[2]
        with open_store(folder) as db:
            db.execute('UPDATE legacy_source SET original=? WHERE document_id=?', (b'broken', imported.document_id))
        damaged = snapshot()
        expect('damaged_source_replay', {'status': 'REPAIR_REQUIRED'}, 'import-v1', 'new', 'legacy.ncp', 'import')
        assert snapshot() == damaged
        assert all((folder / name).read_bytes() == data for name, data in fixtures.items())
    assert V1.read_bytes() == v1 and V2.read_bytes() == v2
    return {
        'status': 'passed', 'milestone': 'DEV-041', 'scope': 'isolated host SQLite, JVM preview codec, synthetic notes',
        'sqlite_version': sqlite3.sqlite_version, 'journal_mode': 'wal', 'synchronous': 'full',
        'crash_signal': CUT_METHOD, 'events': events, 'original_fixtures_preserved': True,
        'joint_resource_import': verify_resource_import(imported, transferred, v1, v2),
        'app_storage_touched': False, 'production_connected': False, 'power_loss_verified': False,
        'fixture_sha256': {path.relative_to(ROOT).as_posix(): raw_hash(path) for path in (V1, V2)},
        'implementation_sha256': {
            path.relative_to(ROOT).as_posix(): raw_hash(path)
            for path in (Path(__file__).resolve(), DECODE, ROOT / 'tools/sqlite_checkpoint_fixture.py')
        },
        'codec_artifact_sha256': runtime_hashes, 'codec_verification_sha256': raw_hash(OFFLINE),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', nargs=6, help=argparse.SUPPRESS)
    parser.add_argument('--output', type=Path, help='Write the verification report to a new file')
    args = parser.parse_args()
    if args.worker:
        worker(Path(args.worker[0]).resolve(), *args.worker[1:])
    else:
        BUILD.mkdir(exist_ok=True)
        report = json.dumps(verify(), ensure_ascii=False, indent=2) + '\n'
        if args.output:
            with args.output.open('x', encoding='utf-8') as output:
                output.write(report)
        print(report, end='')


if __name__ == '__main__':
    main()
