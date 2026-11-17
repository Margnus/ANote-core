"""Verify DEV-041 on a disposable iOS simulator with the pinned Core framework."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import uuid

from sqlite_checkpoint_fixture import SCHEMA
from verify_sqlite_capabilities import matrix
from verify_sqlite_checkpoint_fixture import ROOT, V1, V2, codec_environment, decode

BUILD = ROOT / 'build'
PIN = ROOT / 'reports/c1-pinned-sdk-20261117.json'
SWIFT = ROOT / 'tools/probes/SqliteJointCheckpoint.swift'
SUPPORT = ROOT / 'tools/probes/SqliteProbeSupport.swift'
CONTROL = ROOT / 'tools/probes/SqliteCrashProbe.swift'
PREFIX = 'NOTECORE_SQLITE '


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(framework, runtime, device_type, output):
    output.mkdir(exist_ok=False, parents=True)

    def save(name, value):
        (output / name).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

    def run(label, *command, timeout=60, codes=(0,)):
        args = list(map(str, command))
        result = subprocess.run(args, cwd=ROOT, capture_output=True, timeout=timeout)
        (output / (label + '.stdout.log')).write_bytes(result.stdout)
        (output / (label + '.stderr.log')).write_bytes(result.stderr)
        if result.returncode not in codes:
            raise RuntimeError((label, result.returncode, result.stderr.decode(errors='replace')))
        return result

    expected_framework = json.loads(PIN.read_text(encoding='utf-8'))['apple']['targets']['iosSimulatorArm64']
    framework_binary = framework / 'NoteCanvasPrototype'
    framework_header = framework / 'Headers/NoteCanvasPrototype.h'
    assert sha(framework_binary.read_bytes()) == expected_framework['framework_sha256']
    assert sha(framework_header.read_bytes()) == expected_framework['header_sha256']
    command, runtime_hashes = codec_environment()
    v1, v2 = V1.read_bytes(), V2.read_bytes()
    imported, existing = decode(command, v1), decode(command, v2)
    assert imported.document_id == 'legacy-fixture'
    assert existing.canonical == v2
    resource_data = {
        'resource-a.bin': b'dev041-ios-attachment-A\x00' * 173,
        'resource-b.bin': b'dev041-ios-attachment-B\x00' * 197,
    }
    resources = {sha(data): data for data in resource_data.values()}
    manifest = sorted((key, len(data)) for key, data in resources.items())
    inputs = {'legacy.ncp': v1, 'existing.ncp': v2, **resource_data}
    with tempfile.TemporaryDirectory(prefix='dev041-apple-', dir=BUILD) as temp:
        root = Path(temp)
        for name, data in inputs.items():
            (root / name).write_bytes(data)
        (root / 'schema.json').write_text(json.dumps(SCHEMA), encoding='utf-8')
        (root / '.fixture-scope').write_text('dev041-apple', encoding='utf-8')
        binary, control = root / 'SqliteJointCheckpoint', root / 'SqliteCrashProbe'
        compiler = ('xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios17.0-simulator',
                    '-module-cache-path', root / 'module-cache')
        run('compile-joint', *compiler, '-F', framework.parent, SUPPORT, SWIFT, '-lsqlite3', '-o', binary)
        run('compile-control', *compiler, SUPPORT, CONTROL, '-lsqlite3', '-o', control)
        link = run('link-platform', 'xcrun', 'vtool', '-show-build', binary).stdout.decode()
        assert 'minos 17.0' in link and 'IOSSIMULATOR' in link

        simctl = ('xcrun', 'simctl')
        before = json.loads(run('devices-before', *simctl, 'list', 'devices', '-j').stdout)
        runtime_info = json.loads(run('runtimes', *simctl, 'list', 'runtimes', '-j').stdout)
        version, = [item for item in runtime_info['runtimes'] if item['identifier'] == runtime and item['isAvailable']]
        device = run('create', *simctl, 'create', 'NoteCore-Joint-' + uuid.uuid4().hex, device_type, runtime).stdout.decode().strip()
        uuid.UUID(device)
        observations = {}

        def step(label, stage, *, cut=False, expected=None):
            result = run(label, *simctl, 'spawn', device, binary, root, stage, codes=(137,) if cut else (0,))
            event, = [json.loads(line[len(PREFIX):]) for line in result.stdout.decode().splitlines() if line.startswith(PREFIX)]
            assert event['pid'] > 0 and event['pid'] not in {item['pid'] for item in observations.values()}
            if cut:
                assert event['cut'] == stage
            else:
                assert event['status'] == expected and event['stage'] == stage
            observations[label] = {**event, 'exit': result.returncode}

        def db_state():
            with closing(sqlite3.connect(f'{(root / "checkpoint.sqlite").as_uri()}?mode=ro', uri=True)) as db:
                assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                assert db.execute('PRAGMA foreign_key_check').fetchall() == []
                assert db.execute('PRAGMA user_version').fetchone() == (2,)
                return {
                    'documents': db.execute('SELECT id,revision,checkpoint FROM document ORDER BY id').fetchall(),
                    'sources': db.execute('SELECT document_id,original FROM legacy_source ORDER BY document_id').fetchall(),
                    'operations': db.execute('SELECT id,document_id,base_revision,revision,checkpoint_sha256,source_sha256 FROM operation ORDER BY id').fetchall(),
                    'resources': db.execute('SELECT sha256,size FROM resource ORDER BY sha256').fetchall(),
                    'references': db.execute('SELECT operation_id,sha256 FROM operation_resource ORDER BY 1,2').fetchall(),
                    'tasks': db.execute('SELECT operation_id,document_id FROM import_task').fetchall(),
                    'pins': db.execute('SELECT sha256,size FROM import_task_resource ORDER BY sha256').fetchall(),
                }

        def row(operation, checkpoint, original):
            return (operation, checkpoint.document_id, None, checkpoint.revision,
                    hashlib.sha256(checkpoint.canonical).digest(), hashlib.sha256(original).digest())

        def file_state():
            return {item.name: item.read_bytes() for item in (root / 'resources').iterdir()}

        try:
            run('boot', *simctl, 'boot', device)
            run('bootstatus', *simctl, 'bootstatus', device, '-b', timeout=180)
            step('initial_document', 'init', expected='SEEDED')
            previous = {
                'documents': [(existing.document_id, existing.revision, v2)],
                'sources': [(existing.document_id, v2)],
                'operations': [row('existing', existing, v2)],
                'resources': [], 'references': [], 'tasks': [], 'pins': [],
            }
            assert db_state() == previous
            step('first_resource_published', 'resource-published', cut=True)
            pending = {**previous, 'tasks': [('attach-legacy', imported.document_id)], 'pins': manifest}
            assert db_state() == pending
            first, second = (item[0] for item in manifest)
            target = root / 'resources' / first
            inode = target.stat().st_ino
            assert file_state() == {first: resources[first]}
            step('pending_manifest_conflict', 'conflict', expected='OPERATION_CONFLICT')
            assert db_state() == pending
            assert file_state() == {first: resources[first]}

            occupied = root / 'resources' / second
            bad = b'X' * len(resources[second])
            occupied.write_bytes(bad)
            step('occupied_target_refuses_overwrite', 'replay', expected='REPAIR_REQUIRED')
            assert db_state() == pending
            assert file_state() == {first: resources[first], second: bad}
            occupied.unlink()  # Only the deliberately injected synthetic collision.
            step('joint_sql_transaction_uncommitted', 'before-commit', cut=True)
            assert db_state() == pending and file_state() == resources
            step('committed_before_receipt', 'after-commit', cut=True)
            committed = {
                'documents': sorted([*previous['documents'], (imported.document_id, imported.revision, imported.canonical)]),
                'sources': sorted([*previous['sources'], (imported.document_id, v1)]),
                'operations': [row('attach-legacy', imported, v1), *previous['operations']],
                'resources': manifest, 'references': [('attach-legacy', key) for key, _ in manifest],
                'tasks': [], 'pins': [],
            }
            assert db_state() == committed and file_state() == resources
            step('exact_replay', 'replay', expected='REPLAYED')
            assert target.stat().st_ino == inode and file_state() == resources
            step('committed_manifest_conflict', 'conflict', expected='OPERATION_CONFLICT')
            assert db_state() == committed
            occupied.write_bytes(bad)
            step('same_size_corruption_refuses_receipt', 'replay', expected='REPAIR_REQUIRED')
            assert db_state() == committed and file_state() == {**resources, second: bad}
            assert all((root / name).read_bytes() == data for name, data in inputs.items())

            with tempfile.TemporaryDirectory(prefix='sqlite-capability-joint-regression-', dir=BUILD) as control_root:
                def invoke(stage):
                    return subprocess.run([*simctl, 'spawn', device, str(control), control_root, stage],
                                          capture_output=True, text=True, timeout=60)
                with (output / 'dev038-regression.log').open('w', encoding='utf-8') as log:
                    prior_matrix = matrix(invoke, log)
                assert len(prior_matrix) == 20
        finally:
            try:
                run('shutdown', *simctl, 'shutdown', device, codes=(0, 1, 149))
            finally:
                run('delete', *simctl, 'delete', device)

        after = json.loads(run('devices-after', *simctl, 'list', 'devices', '-j').stdout)
        assert before == after
        assert V1.read_bytes() == v1 and V2.read_bytes() == v2
        report = {
            'status': 'passed', 'milestone': 'DEV-041', 'scope': 'iOS simulator isolated executable with system SQLite and fixed Kotlin/Native codec',
            'runtime': {'identifier': runtime, 'version': version['version'], 'build': version['buildversion'],
                        'device_type': device_type, 'sqlite': observations['initial_document']['sqlite']},
            'simulator_link_floor': '17.0', 'observations': observations,
            'resource_manifest': manifest, 'native_checkpoint_matches_jvm_reference': True,
            'retained_source_bytes_exact': True, 'pending_pins_transition_to_committed_references': True,
            'resource_inode_reused': True, 'prior_dev038_stages_retested': len(prior_matrix),
            'existing_simulator_inventory_unchanged': True, 'temporary_simulator_deleted': True,
            'production_connected': False, 'physical_verified': False, 'power_loss_verified': False,
            'framework_sha256': sha(framework_binary.read_bytes()), 'probe_sha256': sha(binary.read_bytes()),
            'control_sha256': sha(control.read_bytes()), 'host_jvm_sha256': runtime_hashes,
            'source_sha256': {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in
                              (Path(__file__).resolve(), SWIFT, SUPPORT, CONTROL, PIN, V1, V2,
                               ROOT / 'tools/sqlite_checkpoint_fixture.py', ROOT / 'tools/verify_sqlite_capabilities.py')},
            'raw_sha256': {p.name: sha(p.read_bytes()) for p in sorted(output.iterdir())},
        }
        save('verification.json', report)
        print(f'{output / "verification.json"}: {len(observations)} isolated stages, {len(prior_matrix)} DEV-038 regressions')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--framework', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--runtime', default='com.apple.CoreSimulator.SimRuntime.iOS-27-0')
    parser.add_argument('--device-type', default='com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro')
    args = parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    verify(args.framework.resolve(), args.runtime, args.device_type, args.output.resolve())


if __name__ == '__main__':
    main()
