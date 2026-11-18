"""Build and run the isolated DEV-041 probe on a disposable API 26 ARM64 AVD."""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sqlite3
import subprocess
import tempfile
import time
import uuid
import zipfile

from sqlite_checkpoint_fixture import SCHEMA
from verify_sqlite_checkpoint_fixture import ROOT, V1, V2, decode

PIN = ROOT / 'reports/c1-pinned-sdk-20261117.json'
ATOMIC = ROOT / 'reports/android-atomic-promote-api26.json'
PROBES = ROOT / 'tools/probes'
JAVA = [PROBES / (name + '.java') for name in ('AtomicPromoteProbe', 'SqliteJointCheckpointAndroid')]
NATIVE = 'lib/arm64-v8a/libnotecore_atomic_promote_probe.so'
TABLES = ('document', 'legacy_source', 'operation', 'resource', 'operation_resource',
          'import_task', 'import_task_resource')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify(args, work):
    sdk, jdk = (Path(os.environ[key]) for key in ('ANDROID_HOME', 'JAVA_HOME'))
    tools = sdk / 'build-tools/36.0.0'
    android_jar = sdk / 'platforms/android-36/android.jar'
    adb = sdk / 'platform-tools/adb'
    log = args.output / 'commands.log'
    raw = args.output / 'raw'
    raw.mkdir()

    def run(*command, data=None, binary=False, codes=(0,), timeout=45):
        command = list(map(str, command))
        result = subprocess.run(command, input=data, capture_output=True, timeout=timeout)
        with log.open('a', encoding='utf-8') as stream:
            stream.write(f'{result.returncode} {shlex.join(command)}\n')
            stream.write(result.stderr.decode(errors='replace'))
            if not binary:
                stream.write(result.stdout.decode(errors='replace'))
        if result.returncode not in codes:
            raise RuntimeError(f'{command[0]} exited {result.returncode}; see {log}')
        return result.stdout if binary else result.stdout.decode().strip()

    pin_bytes, atomic_bytes = PIN.read_bytes(), ATOMIC.read_bytes()
    pin = json.loads(pin_bytes)
    atomic_apk = args.atomic_apk.read_bytes()
    assert sha(atomic_apk) == json.loads(atomic_bytes)['identical_apk_sha256']
    assert sha(args.stdlib.read_bytes()) == json.loads(
        (ROOT / 'reports/apple-sqlite-joint-checkpoint-20261117/verification.json').read_bytes()
    )['host_jvm_sha256']['kotlin-stdlib-2.2.21.jar']
    with zipfile.ZipFile(args.atomic_apk) as archive:
        native = archive.read(NATIVE)

    jars = []
    artifacts = {}
    for name, spec in pin['android']['rebuilt_aars'].items():
        artifact = ROOT / spec['artifact']
        artifacts[name] = sha(artifact.read_bytes())
        assert artifacts[name] == spec['sha256']
        classes = work / (name + '.jar')
        with zipfile.ZipFile(artifact) as archive:
            classes.write_bytes(archive.read('classes.jar'))
        jars.append(classes)
    jars.append(args.stdlib)
    java = jdk / 'bin/java'
    classpath = os.pathsep.join(map(str, jars))
    oracle = [str(java), '-cp', classpath, str(PROBES / 'SqliteLegacyFixture.java')]
    v1, v2 = V1.read_bytes(), V2.read_bytes()
    imported, existing = decode(oracle, v1), decode(oracle, v2)
    assert imported.document_id != existing.document_id and existing.canonical == v2

    token = uuid.uuid4()
    package = 'dev.notecore.probes.sqlitejoint.p' + token.hex
    scope = 'cache/dev041-joint-' + str(token)
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text((PROBES / 'atomic-promote-AndroidManifest.xml').read_text(encoding='utf-8')
                        .replace('dev.notecore.probes.atomicpromote', package)
                        .replace('dev.notecore.probes.AtomicPromoteProbe', 'dev.notecore.probes.SqliteJointCheckpointAndroid')
                        .replace('Disposable atomic promote probe', 'Disposable SQLite joint checkpoint probe'), encoding='utf-8')
    classes = work / 'classes'
    run(jdk / 'bin/javac', '--release', '8', '-cp', str(android_jar) + os.pathsep + classpath,
        '-d', classes, *JAVA)
    dex = work / 'dex.zip'
    run(java, '-Xmx512m', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '26', '--lib', android_jar, '--output', dex, *sorted(classes.rglob('*.class')), *jars)
    unsigned, apk, key = (work / name for name in ('unsigned.apk', 'probe.apk', 'probe.keystore'))
    run(tools / 'aapt2', 'link', '-I', android_jar, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(dex) as compiled, zipfile.ZipFile(unsigned, 'a') as merged:
        for name in compiled.namelist():
            if re.fullmatch(r'classes[0-9]*\.dex', name):
                merged.writestr(name, compiled.read(name))
        merged.writestr(NATIVE, native)
    run(jdk / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'disposable',
        '-alias', 'probe', '-dname', 'CN=Disposable NoteCore Probe', '-keyalg', 'RSA', '-validity', '2')
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:disposable', '--out', apk, unsigned)
    run(tools / 'apksigner', 'verify', apk)
    if args.build_only:
        (args.output / 'probe.apk').write_bytes(apk.read_bytes())
        return

    serial = f'emulator-{args.port}'
    assert not any(line.split()[:1] == [serial] for line in run(adb, 'devices').splitlines())
    name = 'NoteCoreJoint26-' + token.hex
    avds = work / 'avd'
    avd = avds / (name + '.avd')
    avd.mkdir(parents=True)
    (avds / (name + '.ini')).write_text(f'avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-26\n', encoding='utf-8')
    config = {'AvdId': name, 'avd.ini.displayname': name, 'abi.type': 'arm64-v8a',
              'hw.cpu.arch': 'arm64', 'hw.cpu.ncore': '4', 'hw.ramSize': '2048',
              'image.sysdir.1': str(args.image.resolve()) + '/', 'tag.id': 'default',
              'tag.display': 'Default Android System Image', 'disk.dataPartition.size': '2147483648',
              'hw.lcd.width': '1080', 'hw.lcd.height': '1920', 'hw.lcd.density': '420'}
    (avd / 'config.ini').write_text(''.join(f'{key}={value}\n' for key, value in config.items()), encoding='utf-8')

    def device(*command, **kwargs):
        return run(adb, '-s', serial, *command, **kwargs)

    def app(*command, **kwargs):
        return device('exec-out', 'run-as', package, *command, **kwargs)

    def put(path, data):
        device('shell', '-T', f'run-as {package} sh -c "cat > {path}"', data=data)
        assert app('cat', path, binary=True) == data

    attachments = {'resource-a.bin': b'dev041-android-A\x00' * 173,
                   'resource-b.bin': b'dev041-android-B\x00' * 197}
    resources = {sha(data): data for data in attachments.values()}
    resource_ids = sorted(resources)
    inputs = {'legacy.ncp': v1, 'existing.ncp': v2,
              'schema.json': json.dumps(SCHEMA).encode(), **attachments}
    observations = {}

    def seed(label):
        device('shell', 'run-as', package, 'mkdir', scope)
        for filename, data in inputs.items():
            put(f'{scope}/{filename}', data)
        step(label, 'init', expected='SEEDED')
        assert state(label) == (previous, {})

    def step(label, stage, *, expected=None):
        result = device('shell', 'am', 'instrument', '-w', '-r',
                        '-e', 'scope', scope.split('/')[1], '-e', 'stage', stage,
                        package + '/dev.notecore.probes.SqliteJointCheckpointAndroid', codes=(0, 1))
        if expected is None:
            assert 'Process crashed' in result and 'INSTRUMENTATION_RESULT: joint=' not in result, result
            event = json.loads(app('cat', f'{scope}/{stage}.json'))
            assert event['cut'] == stage
        else:
            prefix = 'INSTRUMENTATION_RESULT: joint='
            assert 'INSTRUMENTATION_CODE: -1' in result, result
            line, = [line for line in result.splitlines() if line.startswith(prefix)]
            event = json.loads(line[len(prefix):])
            assert event['status'] == expected and event['stage'] == stage, event
        assert re.fullmatch(r'u:r:untrusted_app(?:_[0-9]+)?:s0.*', event['domain']), event
        assert event['pid'] > 0 and event['pid'] not in {item['pid'] for item in observations.values()}
        event['instrumentation'] = result
        observations[label] = event

    def state(label):
        folder = raw / label
        folder.mkdir()
        files = app('ls', '-1', scope).splitlines()
        db_path = folder / 'checkpoint.sqlite'
        for filename in ('checkpoint.sqlite', 'checkpoint.sqlite-wal'):
            if filename in files:
                (folder / filename).write_bytes(app('cat', f'{scope}/{filename}', binary=True))
        # SQLite may initialize SHM or recover WAL on open. Do that on a scratch
        # copy so that the captured database and WAL remain exactly as pulled.
        scratch = work / 'inspection' / label
        shutil.copytree(folder, scratch)
        with closing(sqlite3.connect(scratch / db_path.name)) as db:
            db.execute('PRAGMA query_only=ON')
            assert db.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
            assert not db.execute('PRAGMA foreign_key_check').fetchall()
            assert db.execute('PRAGMA user_version').fetchone() == (2,)
            rows = {table: db.execute(f'SELECT * FROM {table} ORDER BY 1,2').fetchall() for table in TABLES}
            observations[label]['retirement'] = db.execute('SELECT * FROM retirement ORDER BY 1').fetchall()
            observations[label]['retirement_readers'] = db.execute('SELECT * FROM retirement_reader ORDER BY 1').fetchall()
        published = {}
        if 'resources' in files:
            for resource in app('ls', '-1', f'{scope}/resources').splitlines():
                assert resource in resources
                path = f'{scope}/resources/{resource}'
                published[resource] = {
                    'inode': int(app('stat', '-c', '%i', path)),
                    'sha256': sha(app('cat', path, binary=True)),
                }
        observations[label]['db_and_wal_sha256'] = {
            file.name: sha(file.read_bytes()) for file in sorted(folder.iterdir())
        }
        observations[label]['resources'] = published
        tombstone = f'{scope}/retire-tombstone'
        observations[label]['retirement_file'] = ({
            'identity': app('stat', '-c', '%d:%i', tombstone),
            'sha256': sha(app('cat', tombstone, binary=True)),
        } if 'retire-tombstone' in files else None)
        return rows, published

    def rows(operation, value, original):
        return {'document': [(value.document_id, value.revision, value.canonical)],
                'legacy_source': [(value.document_id, original)],
                'operation': [(operation, value.document_id, None, value.revision,
                               hashlib.sha256(value.canonical).digest(), hashlib.sha256(original).digest())]}

    previous = {**dict.fromkeys(TABLES, []), **rows('existing', existing, v2)}
    pending = {**previous, 'import_task': [('attach-legacy', imported.document_id, json.dumps([
        imported.document_id, None, imported.revision, sha(imported.canonical), sha(v1)], separators=(',', ':')), 'first', 'active')],
        'import_task_resource': [('attach-legacy', key, len(resources[key])) for key in resource_ids]}
    def cancellation_state(status):
        task, = pending['import_task']
        return {**pending, 'import_task': [(*task[:-1], status)]}

    committed = {table: sorted(previous[table] + values)
                 for table, values in rows('attach-legacy', imported, v1).items()}
    committed.update(resource=[(key, len(resources[key])) for key in resource_ids],
                     operation_resource=[('attach-legacy', key) for key in resource_ids],
                     import_task=[], import_task_resource=[])

    emu_env = {**os.environ, 'ANDROID_AVD_HOME': str(avds), 'ANDROID_EMULATOR_HOME': str(work),
               'ANDROID_USER_HOME': str(work)}
    with (args.output / 'emulator.log').open('wb') as emulator_log:
        emulator = subprocess.Popen([sdk / 'emulator/emulator', '-avd', name, '-port', str(args.port),
                                    '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect'],
                                   env=emu_env, stdout=emulator_log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                assert emulator.poll() is None, 'emulator exited; see emulator.log'
                if device('shell', 'getprop', 'sys.boot_completed', codes=(0, 1), timeout=10) == '1':
                    break
                time.sleep(2)
            else:
                raise RuntimeError('isolated emulator did not boot')
            assert device('shell', 'getprop', 'ro.kernel.qemu.avd_name') == name
            environment = {key: device('shell', *command) for key, command in {
                'api': ('getprop', 'ro.build.version.sdk'), 'fingerprint': ('getprop', 'ro.build.fingerprint'),
                'abi': ('getprop', 'ro.product.cpu.abi'), 'selinux': ('getenforce',), 'kernel': ('uname', '-r'),
            }.items()}
            assert (environment['api'], environment['abi'], environment['selinux']) == ('26', 'arm64-v8a', 'Enforcing')
            assert not device('shell', 'pm', 'list', 'packages', 'dev.notecore.preview')
            assert device('install', apk).splitlines()[-1] == 'Success'
            installed = device('shell', 'pm', 'path', package).removeprefix('package:')
            assert installed.startswith('/data/app/') and installed.endswith('/base.apk')
            assert sha(device('exec-out', 'cat', installed, binary=True)) == sha(apk.read_bytes())
            seed('seeded')
            step('partial_publish', 'resource-published')
            database, first = state('partial_publish')
            assert database == pending and list(first) == resource_ids[:1]
            assert first[resource_ids[0]]['sha256'] == resource_ids[0]
            step('pending_conflict', 'conflict', expected='OPERATION_CONFLICT')
            assert state('pending_conflict') == (pending, first)
            step('uncommitted', 'before-commit')
            database, published = state('uncommitted')
            assert database == pending and list(published) == resource_ids
            assert all(facts['sha256'] == key for key, facts in published.items())
            assert published[resource_ids[0]] == first[resource_ids[0]]
            damaged_key = resource_ids[1]
            target = f'{scope}/resources/{damaged_key}'
            data = resources[damaged_key]
            corrupt = bytes([data[0] ^ 1]) + data[1:]
            put(target, corrupt)
            damaged = {**published, damaged_key: {**published[damaged_key], 'sha256': sha(corrupt)}}
            step('occupied_corrupt', 'replay', expected='REPAIR_REQUIRED')
            assert state('occupied_corrupt') == (pending, damaged)
            put(target, data)
            step('committed_without_receipt', 'after-commit')
            assert state('committed_without_receipt') == (committed, published)
            step('retire_committed_resource', 'retire', expected='PROTECTED')
            assert state('retire_committed_resource') == (committed, published)
            assert observations['retire_committed_resource']['retirement'] == []
            step('late_cancel_after_commit', 'cancel-request', expected='ALREADY_COMMITTED')
            assert state('late_cancel_after_commit') == (committed, published)
            step('replayed', 'replay', expected='REPLAYED')
            assert state('replayed') == (committed, published)
            step('committed_conflict', 'conflict', expected='OPERATION_CONFLICT')
            assert state('committed_conflict') == (committed, published)
            put(target, corrupt)
            step('committed_corruption', 'replay', expected='REPAIR_REQUIRED')
            assert state('committed_corruption') == (committed, damaged)
            for filename, data in inputs.items():
                assert app('cat', f'{scope}/{filename}', binary=True) == data

            scope = 'cache/dev041-joint-' + str(uuid.uuid4())
            seed('seed_cancel_after_stop')
            step('cancel_after_stop_publish', 'resource-published')
            database, retained_files = state('cancel_after_stop_publish')
            assert database == pending and list(retained_files) == resource_ids[:1]
            assert retained_files[resource_ids[0]]['sha256'] == resource_ids[0]
            first_resource = resource_ids[0]
            retained_identity = app('stat', '-c', '%d:%i', f'{scope}/resources/{first_resource}')
            step('retire_active_task', 'retire', expected='PROTECTED')
            assert state('retire_active_task') == (pending, retained_files)
            step('reader_pin_before_cancel', 'retire-pin', expected='PINNED')
            assert state('reader_pin_before_cancel') == (pending, retained_files)
            assert observations['reader_pin_before_cancel']['retirement_readers'] == [(first_resource, 'reader-new')]
            step('stale_cancel_after_stop', 'cancel-stale', expected='STALE_ATTEMPT')
            assert state('stale_cancel_after_stop') == (pending, retained_files)
            step('request_cancel_after_stop', 'cancel-request', expected='CANCEL_REQUESTED')
            assert state('request_cancel_after_stop') == (cancellation_state('requested'), retained_files)
            step('ack_cancel_after_stop', 'replay', expected='CANCELLED')
            assert state('ack_cancel_after_stop') == (cancellation_state('cancelled'), retained_files)
            step('retry_cancelled_after_stop', 'replay', expected='CANCELLED')
            assert state('retry_cancelled_after_stop') == (cancellation_state('cancelled'), retained_files)

            cancelled = cancellation_state('cancelled')
            step('late_reader_release', 'retire-stale-release', expected='STALE_READER')
            assert state('late_reader_release') == (cancelled, retained_files)
            assert observations['late_reader_release']['retirement_readers'] == [(first_resource, 'reader-new')]
            step('retire_reader_protected', 'retire', expected='PROTECTED')
            assert state('retire_reader_protected') == (cancelled, retained_files)
            assert observations['retire_reader_protected']['retirement'] == []
            step('release_reader', 'retire-release', expected='RELEASED')
            assert state('release_reader') == (cancelled, retained_files)
            assert observations['release_reader']['retirement_readers'] == []

            def check_retirement(label, metadata, expected_files, tomb=None):
                assert state(label) == (cancelled, expected_files)
                assert observations[label]['retirement'] == [(first_resource, metadata, retained_identity)]
                assert observations[label]['retirement_file'] == tomb
                assert observations[label]['retirement_readers'] == []

            step('retirement_reserved', 'retire-reserved')
            check_retirement('retirement_reserved', 'retiring', retained_files)
            step('retiring_refuses_new_pin', 'retire-pin', expected='RESOURCE_NOT_AVAILABLE')
            check_retirement('retiring_refuses_new_pin', 'retiring', retained_files)
            tombstone = f'{scope}/retire-tombstone'
            put(tombstone, resources[first_resource])
            occupied = {'identity': app('stat', '-c', '%d:%i', tombstone), 'sha256': first_resource}
            assert occupied['identity'] != retained_identity
            step('same_bytes_different_inode', 'retire', expected='REPAIR_REQUIRED')
            check_retirement('same_bytes_different_inode', 'retiring', retained_files, occupied)
            app('rm', tombstone)  # Remove only the fixture-created collision.
            tomb = {'identity': retained_identity, 'sha256': first_resource}
            step('retirement_moved', 'retire-moved')
            check_retirement('retirement_moved', 'retiring', {}, tomb)
            step('retirement_delete_pending', 'retire-delete-pending')
            check_retirement('retirement_delete_pending', 'delete-pending', {}, tomb)
            step('retirement_unlinked', 'retire-unlinked')
            check_retirement('retirement_unlinked', 'delete-pending', {})
            step('retirement_recovered', 'retire', expected='RETIRED')
            check_retirement('retirement_recovered', 'retired', {})
            step('retirement_replayed', 'retire', expected='RETIRED')
            check_retirement('retirement_replayed', 'retired', {})
            step('retired_refuses_new_pin', 'retire-pin', expected='RESOURCE_NOT_AVAILABLE')
            check_retirement('retired_refuses_new_pin', 'retired', {})
            for filename, data in inputs.items():
                assert app('cat', f'{scope}/{filename}', binary=True) == data

            scope = 'cache/dev041-joint-' + str(uuid.uuid4())
            seed('seed_live_cancel')
            step('request_while_producer_live', 'cancel-live', expected='CANCELLED')
            database, live_files = state('request_while_producer_live')
            assert database == cancellation_state('cancelled') and list(live_files) == resource_ids[:1]
            assert live_files[resource_ids[0]]['sha256'] == resource_ids[0]
            step('retry_live_cancelled', 'replay', expected='CANCELLED')
            assert state('retry_live_cancelled') == (cancellation_state('cancelled'), live_files)
            for filename, data in inputs.items():
                assert app('cat', f'{scope}/{filename}', binary=True) == data
            assert device('uninstall', package) == 'Success'
            assert not device('shell', 'pm', 'list', 'packages', package)
        finally:
            emulator.terminate()
            try:
                emulator.wait(timeout=20)
            except subprocess.TimeoutExpired:
                emulator.kill()
                emulator.wait()

    report = {
        'status': 'passed', 'environment': environment, 'avd': name,
        'observations': observations, 'host_integrity_and_foreign_keys': f'passed after all {len(observations)} stages',
        'canonical_sha256': sha(imported.canonical), 'inputs_sha256': {key: sha(data) for key, data in inputs.items()},
        'candidate_source_commit': pin['candidate_source_commit'], 'aars_sha256': artifacts,
        'apk_sha256': sha(apk.read_bytes()), 'atomic_apk_sha256': sha(atomic_apk), 'jni_sha256': sha(native),
        'source_sha256': {path.relative_to(ROOT).as_posix(): sha(path.read_bytes())
                          for path in (Path(__file__).resolve(), *JAVA, PROBES / 'atomic-promote-AndroidManifest.xml',
                                       PROBES / 'SqliteLegacyFixture.java', ROOT / 'tools/sqlite_checkpoint_fixture.py',
                                       PIN, ATOMIC)},
    }
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--atomic-apk', type=Path, required=True)
    parser.add_argument('--stdlib', type=Path, required=True)
    parser.add_argument('--output', type=lambda path: Path(path).resolve(), required=True)
    parser.add_argument('--port', type=int, default=5584)
    parser.add_argument('--build-only', action='store_true')
    args = parser.parse_args()
    assert (args.image / 'system.img').is_file()
    args.output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='dev041-android-joint-', dir=ROOT / 'build') as temp:
        verify(args, Path(temp))
