"""Verify DEV-041 on a disposable iOS simulator with the pinned Core framework."""
import argparse
from contextlib import closing
import errno
import hashlib
import json
import os
import plistlib
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tempfile
import time
import uuid
import zipfile

from sqlite_checkpoint_fixture import SCHEMA
from verify_sqlite_capabilities import matrix
from verify_sqlite_checkpoint_fixture import ROOT, V1, V2, codec_environment, decode

BUILD = ROOT / 'build'
PIN = ROOT / 'reports/c1-pinned-sdk-20261117.json'
SWIFT = ROOT / 'tools/probes/SqliteJointCheckpoint.swift'
SUPPORT = ROOT / 'tools/probes/SqliteProbeSupport.swift'
APP = ROOT / 'tools/probes/SqliteJointCheckpointApp.swift'
DECODER = ROOT / 'tools/probes/SqliteLegacyFixture.java'
ANDROID = ROOT / 'reports/dev041-android-joint-checkpoint-20261117.json'
CONTROL = ROOT / 'tools/probes/SqliteCrashProbe.swift'
PREFIX = 'NOTECORE_SQLITE '


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pinned_codec(work, stdlib, pin):
    jars = []
    hashes = {stdlib.name: sha(stdlib.read_bytes())}
    assert hashes[stdlib.name] == '6558a3d233da56a20934b32159f9db5f86ed5816ef098f78a2c223dc6abb79dd'
    for name, spec in pin['android']['rebuilt_aars'].items():
        source = ROOT / spec['artifact']
        hashes[name] = sha(source.read_bytes())
        assert hashes[name] == spec['sha256']
        jar = work / (name + '.jar')
        with zipfile.ZipFile(source) as archive:
            jar.write_bytes(archive.read('classes.jar'))
        jars.append(jar)
    java = Path(os.environ['JAVA_HOME']) / 'bin/java'
    classpath = os.pathsep.join(map(str, [*jars, stdlib]))
    return [str(java), '-cp', classpath, str(DECODER)], hashes


def wait_for_exit(pid, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return
        time.sleep(.1)
    raise RuntimeError(f'App process {pid} is still alive')


def verify(framework, runtime, device_type, output, *, fixed_stdlib=None, app_sandbox=False):
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

    pin = json.loads(PIN.read_text(encoding='utf-8'))
    expected_framework = pin['apple']['targets']['iosSimulatorArm64']
    framework_binary = framework / 'NoteCanvasPrototype'
    framework_header = framework / 'Headers/NoteCanvasPrototype.h'
    assert sha(framework_binary.read_bytes()) == expected_framework['framework_sha256']
    assert sha(framework_header.read_bytes()) == expected_framework['header_sha256']
    v1, v2 = V1.read_bytes(), V2.read_bytes()
    resource_data = {
        'resource-a.bin': b'dev041-ios-attachment-A\x00' * 173,
        'resource-b.bin': b'dev041-ios-attachment-B\x00' * 197,
    }
    resources = {sha(data): data for data in resource_data.values()}
    manifest = sorted((key, len(data)) for key, data in resources.items())
    inputs = {'legacy.ncp': v1, 'existing.ncp': v2, **resource_data}
    with tempfile.TemporaryDirectory(prefix='dev041-apple-', dir=BUILD) as temp:
        work = root = Path(temp)
        if fixed_stdlib:
            command, runtime_hashes = pinned_codec(work, fixed_stdlib, pin)
        else:
            command, runtime_hashes = codec_environment()
        imported, existing = decode(command, v1), decode(command, v2)
        assert imported.document_id == 'legacy-fixture'
        assert existing.canonical == v2
        if app_sandbox:
            cross_platform = json.loads(ANDROID.read_text(encoding='utf-8'))
            assert cross_platform['candidate_source_commit'] == pin['candidate_source_commit']
            assert cross_platform['inputs_sha256']['legacy.ncp'] == sha(v1)
            assert cross_platform['canonical_sha256'] == sha(imported.canonical)

        def seed_inputs():
            for name, data in inputs.items():
                (root / name).write_bytes(data)
            (root / 'schema.json').write_text(json.dumps(SCHEMA), encoding='utf-8')
            (root / '.fixture-scope').write_text('dev041-apple', encoding='utf-8')

        control = work / 'SqliteCrashProbe'
        binary = work / 'SqliteJointCheckpoint'
        compiler = ('xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios17.0-simulator',
                    '-module-cache-path', work / 'module-cache')
        app_options = ()
        if app_sandbox:
            bundle = 'dev.notecore.probes.sqlitejointapple.p' + uuid.uuid4().hex
            app = work / 'JointCheckpoint.app'
            app.mkdir()
            binary = app / binary.name
            (app / 'Info.plist').write_bytes(plistlib.dumps({
                'CFBundleIdentifier': bundle, 'CFBundlePackageType': 'APPL',
                'CFBundleName': 'JointCheckpoint', 'CFBundleExecutable': binary.name,
                'CFBundleShortVersionString': '1.0', 'CFBundleVersion': '1',
                'MinimumOSVersion': '17.0', 'CFBundleSupportedPlatforms': ['iPhoneSimulator'],
                'UIDeviceFamily': [1, 2], 'UILaunchScreen': {},
                'UIApplicationSceneManifest': {
                    'UIApplicationSupportsMultipleScenes': False,
                    'UISceneConfigurations': {'UIWindowSceneSessionRoleApplication': [{
                        'UISceneConfigurationName': 'JointCheckpoint',
                        'UISceneDelegateClassName': 'Dev041JointSceneDelegate',
                    }]},
                },
            }))
            app_options = ('-D', 'DEV041_APP', APP)
        run('compile-joint', *compiler, '-F', framework.parent, SUPPORT, SWIFT, *app_options, '-lsqlite3', '-o', binary)
        if app_sandbox:
            run('sign-probe', 'codesign', '--force', '--sign', '-', app)
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
            if app_sandbox:
                marker = root / 'result.json'
                marker.unlink(missing_ok=True)
                result = run(label, *simctl, 'launch',
                             f'--stdout={output / (label + ".app-stdout.log")}',
                             f'--stderr={output / (label + ".app-stderr.log")}',
                             device, bundle, '--dev041-stage', stage, root.name)
                launch = result.stdout.decode().strip()
                match = re.fullmatch(re.escape(bundle) + r': (\d+)', launch)
                assert match, launch
                pid = int(match[1])
                deadline = time.monotonic() + 45
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(.1)
                if not marker.exists():
                    run(label + '-diagnostics', *simctl, 'spawn', device, 'log', 'show', '--last', '2m',
                        '--predicate', f'processID == {pid} OR (eventMessage CONTAINS "{bundle}")',
                        timeout=30, codes=(0, 1))
                    raise RuntimeError(f'No App receipt for {label}; see {output / (label + ".app-stderr.log")}')
                event = json.loads(marker.read_bytes())
                assert event['pid'] == pid and event['bundle'] == bundle
                assert Path(event.pop('sandbox_home')).resolve() == home.resolve()
                event['data_container_uuid'] = home.name
                if not cut:
                    run(label + '-terminate', *simctl, 'terminate', device, bundle)
                wait_for_exit(pid)
                launch_evidence = {'simctl_launch_pid': pid, 'terminated': True}
            else:
                result = run(label, *simctl, 'spawn', device, binary, root, stage, codes=(137,) if cut else (0,))
                event, = [json.loads(line[len(PREFIX):]) for line in result.stdout.decode().splitlines()
                          if line.startswith(PREFIX)]
                launch_evidence = {'exit': result.returncode}
            assert event['pid'] > 0 and event['pid'] not in {item['pid'] for item in observations.values()}
            if cut:
                assert event['cut'] == stage
            else:
                assert event['status'] == expected and event['stage'] == stage
            observations[label] = {**event, **launch_evidence}

        def db_state(label=None):
            # SQLite recovery can touch WAL/SHM. Inspect only a scratch copy of the quiescent files.
            with tempfile.TemporaryDirectory(dir=work) as copy:
                database = Path(copy) / 'checkpoint.sqlite'
                for name in (database.name, database.name + '-wal'):
                    source = root / name
                    if source.exists():
                        shutil.copyfile(source, Path(copy) / name)
                with closing(sqlite3.connect(database.as_uri() + '?mode=rw', uri=True)) as db:
                    db.execute('PRAGMA query_only=ON')
                    assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                    assert db.execute('PRAGMA foreign_key_check').fetchall() == []
                    assert db.execute('PRAGMA user_version').fetchone() == (2,)
                    if label:
                        evidence = observations[label]
                        evidence['retirement'] = db.execute('SELECT * FROM retirement ORDER BY 1').fetchall()
                        evidence['retirement_readers'] = db.execute('SELECT * FROM retirement_reader ORDER BY 1').fetchall()
                    return {
                        'documents': db.execute('SELECT id,revision,checkpoint FROM document ORDER BY id').fetchall(),
                        'sources': db.execute('SELECT document_id,original FROM legacy_source ORDER BY document_id').fetchall(),
                        'operations': db.execute('SELECT id,document_id,base_revision,revision,checkpoint_sha256,source_sha256 FROM operation ORDER BY id').fetchall(),
                        'resources': db.execute('SELECT sha256,size FROM resource ORDER BY sha256').fetchall(),
                        'references': db.execute('SELECT operation_id,sha256 FROM operation_resource ORDER BY 1,2').fetchall(),
                        'tasks': db.execute('SELECT operation_id,document_id,identity,active_attempt,cancel_state FROM import_task').fetchall(),
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
            if app_sandbox:
                run('install', *simctl, 'install', device, app)
                installed = Path(run('installed-app', *simctl, 'get_app_container', device, bundle, 'app').stdout.decode().strip())
                assert sha((installed / binary.name).read_bytes()) == sha(binary.read_bytes())
                home = Path(run('data-container', *simctl, 'get_app_container', device, bundle, 'data').stdout.decode().strip())
                root = home / 'Library/Caches/dev041-apple-joint'
                root.mkdir(parents=True)
            seed_inputs()
            step('initial_document', 'init', expected='SEEDED')
            previous = {
                'documents': [(existing.document_id, existing.revision, v2)],
                'sources': [(existing.document_id, v2)],
                'operations': [row('existing', existing, v2)],
                'resources': [], 'references': [], 'tasks': [], 'pins': [],
            }
            assert db_state() == previous
            step('first_resource_published', 'resource-published', cut=True)
            task_identity = json.dumps([imported.document_id, None, imported.revision, sha(imported.canonical), sha(v1)])
            task = ('attach-legacy', imported.document_id, task_identity, 'first')
            pending = {**previous, 'tasks': [(*task, 'active')], 'pins': manifest}
            assert db_state() == pending
            first, second = (item[0] for item in manifest)
            target = root / 'resources' / first
            first_inode = target.stat().st_ino
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
            inodes = {item.name: item.stat().st_ino for item in target.parent.iterdir()}
            assert inodes[first] == first_inode
            step('committed_before_receipt', 'after-commit', cut=True)
            committed = {
                'documents': sorted([*previous['documents'], (imported.document_id, imported.revision, imported.canonical)]),
                'sources': sorted([*previous['sources'], (imported.document_id, v1)]),
                'operations': [row('attach-legacy', imported, v1), *previous['operations']],
                'resources': manifest, 'references': [('attach-legacy', key) for key, _ in manifest],
                'tasks': [], 'pins': [],
            }
            assert db_state() == committed and file_state() == resources
            if app_sandbox:
                step('retire_committed_resource', 'retire', expected='PROTECTED')
                assert db_state('retire_committed_resource') == committed and file_state() == resources
                assert not observations['retire_committed_resource']['retirement']
            step('late_cancel_after_commit', 'cancel-request', expected='ALREADY_COMMITTED')
            assert db_state() == committed and file_state() == resources
            step('exact_replay', 'replay', expected='REPLAYED')
            assert db_state() == committed and file_state() == resources
            assert {item.name: item.stat().st_ino for item in target.parent.iterdir()} == inodes
            step('committed_manifest_conflict', 'conflict', expected='OPERATION_CONFLICT')
            assert db_state() == committed
            occupied.write_bytes(bad)
            step('same_size_corruption_refuses_receipt', 'replay', expected='REPAIR_REQUIRED')
            assert db_state() == committed and file_state() == {**resources, second: bad}
            assert all((root / name).read_bytes() == data for name, data in inputs.items())
            if app_sandbox:
                def start_case(fixture):
                    case = home / 'Library/Caches' / fixture
                    case.mkdir()
                    return case

                def cancelled_state(status):
                    return {**pending, 'tasks': [(*task, status)]}

                root = start_case('dev041-apple-cancel-stopped')
                seed_inputs()
                step('seed_cancel_after_stop', 'init', expected='SEEDED')
                assert db_state() == previous
                step('cancel_after_stop_publish', 'resource-published', cut=True)
                retained = {first: resources[first]}
                assert db_state() == pending and file_state() == retained
                original = root / 'resources' / first
                retained_inode = original.stat().st_ino
                reserved_identity = f'{original.stat().st_dev}:{retained_inode}'
                step('retire_active_task', 'retire', expected='PROTECTED')
                assert db_state('retire_active_task') == pending and file_state() == retained
                assert not observations['retire_active_task']['retirement']
                step('reader_pin_before_cancel', 'retire-pin', expected='PINNED')
                assert db_state('reader_pin_before_cancel') == pending and file_state() == retained
                assert observations['reader_pin_before_cancel']['retirement_readers'] == [(first, 'reader-new')]
                step('stale_cancel_after_stop', 'cancel-stale', expected='STALE_ATTEMPT')
                assert db_state() == pending and file_state() == retained
                step('request_cancel_after_stop', 'cancel-request', expected='CANCEL_REQUESTED')
                assert db_state() == cancelled_state('requested') and file_state() == retained
                step('ack_cancel_after_stop', 'replay', expected='CANCELLED')
                assert db_state() == cancelled_state('cancelled') and file_state() == retained
                step('retry_cancelled_after_stop', 'replay', expected='CANCELLED')
                assert db_state() == cancelled_state('cancelled') and file_state() == retained
                assert original.stat().st_ino == retained_inode
                cancelled = cancelled_state('cancelled')
                step('late_reader_release', 'retire-stale-release', expected='STALE_READER')
                assert db_state('late_reader_release') == cancelled and file_state() == retained
                assert observations['late_reader_release']['retirement_readers'] == [(first, 'reader-new')]
                step('retire_reader_protected', 'retire', expected='PROTECTED')
                assert db_state('retire_reader_protected') == cancelled and file_state() == retained
                assert not observations['retire_reader_protected']['retirement']
                step('release_reader', 'retire-release', expected='RELEASED')
                assert db_state('release_reader') == cancelled and file_state() == retained
                assert not observations['release_reader']['retirement_readers']

                tombstone = root / 'retire-tombstone'

                def file_identity(path):
                    metadata = path.stat()
                    return {'identity': f'{metadata.st_dev}:{metadata.st_ino}', 'sha256': sha(path.read_bytes())}

                def check_retirement(label, metadata, expected_files, tomb=None):
                    assert db_state(label) == cancelled and file_state() == expected_files
                    evidence = observations[label]
                    assert evidence['retirement'] == [(first, metadata, reserved_identity)]
                    assert evidence['retirement_readers'] == []
                    evidence['retirement_file'] = file_identity(tombstone) if tombstone.exists() else None
                    assert evidence['retirement_file'] == tomb

                step('retirement_reserved', 'retire-reserved', cut=True)
                check_retirement('retirement_reserved', 'retiring', retained)
                step('retiring_refuses_new_pin', 'retire-pin', expected='RESOURCE_NOT_AVAILABLE')
                check_retirement('retiring_refuses_new_pin', 'retiring', retained)
                tombstone.write_bytes(resources[first])
                collision = file_identity(tombstone)
                assert collision['identity'] != reserved_identity
                step('same_bytes_different_inode', 'retire', expected='REPAIR_REQUIRED')
                check_retirement('same_bytes_different_inode', 'retiring', retained, collision)
                tombstone.unlink()  # Remove only the fixture-created collision.
                tomb = {'identity': reserved_identity, 'sha256': first}
                step('retirement_moved', 'retire-moved', cut=True)
                check_retirement('retirement_moved', 'retiring', {}, tomb)
                step('move_directory_sync_failure', 'retire-move-sync-failure', expected='DIRECTORY_SYNC_FAILED')
                check_retirement('move_directory_sync_failure', 'retiring', {}, tomb)
                assert observations['move_directory_sync_failure']['directory_sync_errno'] == errno.ENOTDIR
                step('retirement_delete_pending', 'retire-delete-pending', cut=True)
                check_retirement('retirement_delete_pending', 'delete-pending', {}, tomb)
                step('retirement_unlinked', 'retire-unlinked', cut=True)
                check_retirement('retirement_unlinked', 'delete-pending', {})
                step('unlink_directory_sync_failure', 'retire-unlink-sync-failure', expected='DIRECTORY_SYNC_FAILED')
                check_retirement('unlink_directory_sync_failure', 'delete-pending', {})
                assert observations['unlink_directory_sync_failure']['directory_sync_errno'] == errno.ENOTDIR
                step('retirement_recovered', 'retire', expected='RETIRED')
                check_retirement('retirement_recovered', 'retired', {})
                step('retirement_replayed', 'retire', expected='RETIRED')
                check_retirement('retirement_replayed', 'retired', {})
                step('retired_refuses_new_pin', 'retire-pin', expected='RESOURCE_NOT_AVAILABLE')
                check_retirement('retired_refuses_new_pin', 'retired', {})
                assert all((root / name).read_bytes() == data for name, data in inputs.items())

                root = start_case('dev041-apple-cancel-live')
                seed_inputs()
                step('seed_live_cancel', 'init', expected='SEEDED')
                assert db_state() == previous
                step('request_while_producer_live', 'cancel-live', expected='CANCELLED')
                assert db_state() == cancelled_state('cancelled') and file_state() == retained
                original = root / 'resources' / first
                retained_inode = original.stat().st_ino
                step('retry_live_cancelled', 'replay', expected='CANCELLED')
                assert db_state() == cancelled_state('cancelled') and file_state() == retained
                assert original.stat().st_ino == retained_inode
                assert all((root / name).read_bytes() == data for name, data in inputs.items())
                run('uninstall', *simctl, 'uninstall', device, bundle)

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
            'status': 'passed', 'milestone': 'DEV-041', 'scope': ('iOS simulator UIKit App sandbox' if app_sandbox else 'iOS simulator isolated executable')
            + ' with system SQLite and fixed Kotlin/Native codec',
            'runtime': {'identifier': runtime, 'version': version['version'], 'build': version['buildversion'],
                        'device_type': device_type, 'sqlite': observations['initial_document']['sqlite']},
            'simulator_link_floor': '17.0', 'observations': observations,
            'resource_manifest': manifest, 'native_checkpoint_matches_jvm_reference': True,
            'canonical_sha256': sha(imported.canonical), 'app_sandbox_verified': bool(app_sandbox),
            'candidate_source_commit': pin['candidate_source_commit'],
            'retained_source_bytes_exact': True, 'pending_pins_transition_to_committed_references': True,
            'resource_inode_reused': True, 'replayed_resource_inodes': inodes,
            'prior_dev038_stages_retested': len(prior_matrix),
            'existing_simulator_inventory_unchanged': True, 'temporary_simulator_deleted': True,
            'production_connected': False, 'physical_verified': False, 'power_loss_verified': False,
            'framework_sha256': sha(framework_binary.read_bytes()), 'probe_sha256': sha(binary.read_bytes()),
            'control_sha256': sha(control.read_bytes()), 'host_jvm_sha256': runtime_hashes,
            'source_sha256': {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in
                              (Path(__file__).resolve(), SWIFT, SUPPORT, CONTROL, PIN, V1, V2,
                               ROOT / 'tools/sqlite_checkpoint_fixture.py', ROOT / 'tools/verify_sqlite_capabilities.py',
                               *((DECODER,) if fixed_stdlib else ()), *((APP, ANDROID) if app_sandbox else ()))},
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
    parser.add_argument('--fixed-stdlib', type=Path,
                        help='Use the pinned Android AARs and this Kotlin 2.2.21 JAR for the JVM oracle')
    parser.add_argument('--app-sandbox', action='store_true', help='Install and launch the probe as a UIKit App')
    args = parser.parse_args()
    if args.app_sandbox and not args.fixed_stdlib:
        parser.error('--app-sandbox requires --fixed-stdlib')
    BUILD.mkdir(exist_ok=True)
    verify(args.framework.resolve(), args.runtime, args.device_type, args.output.resolve(),
           fixed_stdlib=args.fixed_stdlib.resolve() if args.fixed_stdlib else None, app_sandbox=args.app_sandbox)


if __name__ == '__main__':
    main()
