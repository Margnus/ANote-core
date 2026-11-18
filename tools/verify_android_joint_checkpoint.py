"""Build and verify DEV-041 in a unique self-targeted API 26 emulator App."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import uuid
import zipfile

from sqlite_checkpoint_fixture import SCHEMA
from verify_sqlite_checkpoint_fixture import ROOT, V1, V2, DECODE, decode

PIN = ROOT / 'reports/c1-pinned-sdk-20261117.json'
HISTORICAL = ROOT / 'reports/sdk-artifact-boundary.json'
PROBE = ROOT / 'tools/probes/AndroidJointCheckpointProbe.java'
NATIVE = ROOT / 'tools/probes/atomic_promote_probe.c'
CLASSES = 'dev.notecore.probes.AndroidJointCheckpointProbe'
INPUTS = {
    'legacy.ncp': V1.read_bytes(),
    'existing.ncp': V2.read_bytes(),
    'resource-a.bin': b'dev041-synthetic-attachment-A\x00' * 129,
    'resource-b.bin': b'dev041-synthetic-attachment-B\x00' * 133,
}
ABIS = {'arm64-v8a': 'aarch64', 'x86_64': 'x86_64'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8', newline='\n')


def executable(folder, name):
    return folder / (name + ('.exe' if os.name == 'nt' else ''))


def run(args):
    output = args.output.resolve()
    output.mkdir(parents=True)
    raw = output / 'raw'
    raw.mkdir()
    execution = []

    def invoke(label, *argv, codes=(0,), timeout=120):
        assert not any(item['label'] == label for item in execution), label
        command = [str(arg) for arg in argv]
        result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout)
        (raw / (label + '.stdout')).write_bytes(result.stdout)
        (raw / (label + '.stderr')).write_bytes(result.stderr)
        execution.append({'label': label, 'command': command, 'exit': result.returncode})
        save(raw / 'execution.json', execution)
        assert result.returncode in codes, (label, result.returncode, result.stderr.decode(errors='replace'))
        return result.stdout

    sdk, jdk = args.sdk.resolve(), args.jdk.resolve()
    adb = executable(sdk / 'platform-tools', 'adb')
    java = executable(jdk / 'bin', 'java')
    javac = executable(jdk / 'bin', 'javac')
    build_tools = sdk / 'build-tools' / args.build_tools
    android = sdk / f'platforms/android-{args.compile_sdk}/android.jar'
    clang, = [path for path in args.ndk.glob('toolchains/llvm/prebuilt/*/bin/clang*')
              if path.name in ('clang', 'clang.exe')]
    historical = json.loads(HISTORICAL.read_text(encoding='utf-8'))
    stdlib_sha = historical['android']['compiler_dependencies']['kotlin-stdlib-2.2.21.jar']
    assert file_sha(args.stdlib) == stdlib_sha, 'unexpected Kotlin runtime bytes'
    if args.repository:
        pin = json.loads(PIN.read_text(encoding='utf-8'))
        pin_source = PIN
        candidate = pin['candidate_source_commit']
        artifacts = [(args.repository / Path(entry['artifact']).relative_to('build/c1-pinned-20261117/sdk-candidate-repository'), entry['sha256'])
                     for entry in pin['android']['rebuilt_aars'].values()]
    else:
        pin_source = HISTORICAL
        candidate = historical['baseline']
        archive = ROOT / 'reports/sdk-artifact-boundary-20261107/android'
        artifacts = [(archive / (module + '-release.aar'), entry['aar_sha256'])
                     for module, entry in historical['android']['aar'].items()]
    jars = []
    for artifact, expected in artifacts:
        assert file_sha(artifact) == expected, f'candidate pin mismatch: {artifact.name}'
        jar = output / (artifact.stem + '.jar')
        with zipfile.ZipFile(artifact) as archive:
            jar.write_bytes(archive.read('classes.jar'))
        jars.append(jar)
    jars.append(args.stdlib.resolve())

    host_command = [str(java), '-cp', os.pathsep.join(map(str, jars)), str(DECODE)]
    imported = decode(host_command, INPUTS['legacy.ncp'])
    original = decode(host_command, INPUTS['existing.ncp'])
    assert imported.document_id == 'legacy-fixture' and imported.revision == 2
    assert original.document_id != imported.document_id and original.canonical == INPUTS['existing.ncp']

    def device(label, *argv, **kwargs):
        return invoke(label, adb, '-s', args.serial, *argv, **kwargs)

    def shell(label, *argv, **kwargs):
        return device(label, 'shell', *argv, **kwargs).decode('utf-8').strip()

    runtime = None
    if not args.build_only:
        assert re.fullmatch(r'emulator-\d+', args.serial), 'disposable emulator required'
        assert re.fullmatch(r'NoteCoreDev041[A-Za-z0-9_-]*', args.avd), 'use a dedicated DEV-041 AVD'
        avd = shell('avd', 'getprop', 'ro.kernel.qemu.avd_name')
        if not avd:
            avd = shell('avd-fallback', 'getprop', 'ro.boot.qemu.avd_name')
        assert avd == args.avd, (avd, args.avd)
        runtime = {name: shell(name, *command) for name, command in {
            'api': ('getprop', 'ro.build.version.sdk'),
            'abi': ('getprop', 'ro.product.cpu.abi'),
            'fingerprint': ('getprop', 'ro.build.fingerprint'),
            'kernel': ('uname', '-r'),
            'selinux': ('getenforce',),
            'boot_completed': ('getprop', 'sys.boot_completed'),
        }.items()}
        assert runtime['api'] == '26' and runtime['abi'] == args.abi
        assert runtime['selinux'] == 'Enforcing' and runtime['boot_completed'] == '1'

    classes = output / 'classes'
    invoke('javac', javac, '--release', '17', '-cp', os.pathsep.join(map(str, [android, *jars])),
           '-d', classes, PROBE)
    dex = output / 'dex.zip'
    d8 = build_tools / 'lib/d8.jar'
    invoke('d8', java, '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', '26',
           '--lib', android, '--output', dex, *sorted(classes.rglob('*.class')), *jars)
    native = output / 'libnotecore_atomic_promote_probe.so'
    invoke('jni', clang.resolve(), f'--target={ABIS[args.abi]}-linux-android26', '-shared', '-fPIC', '-fuse-ld=lld', '-rtlib=compiler-rt',
           '-Wall', '-Wextra', '-Werror', '-Wl,-z,noexecstack', '-Wl,-z,relro', '-Wl,-z,now',
           '-Wl,-soname,' + native.name,
           '-DJava_dev_notecore_probes_AtomicPromoteProbe_renameNoReplace='
           'Java_dev_notecore_probes_AndroidJointCheckpointProbe_renameNoReplace', NATIVE, '-o', native)
    package = 'dev.notecore.probes.dev041.p' + uuid.uuid4().hex
    manifest = output / 'AndroidManifest.xml'
    manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
    <application android:label="Disposable DEV-041 checkpoint probe" android:debuggable="true"
        android:allowBackup="false" android:extractNativeLibs="true" />
    <instrumentation android:name="{CLASSES}" android:targetPackage="{package}" />
</manifest>
''', encoding='utf-8', newline='\n')
    unsigned = output / 'unsigned.apk'
    invoke('aapt2', executable(build_tools, 'aapt2'), 'link', '-I', android, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(dex) as dex_files, zipfile.ZipFile(unsigned, 'a') as apk:
        for name in dex_files.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                apk.writestr(name, dex_files.read(name))
        apk.write(native, f'lib/{args.abi}/{native.name}')
        for name, data in INPUTS.items():
            apk.writestr('assets/' + name, data)
        apk.writestr('assets/schema.json', json.dumps(SCHEMA))
    key = output / 'probe.keystore'
    invoke('key', executable(jdk / 'bin', 'keytool'), '-genkeypair', '-keystore', key,
           '-storepass', 'disposable', '-keypass', 'disposable', '-alias', 'probe',
           '-dname', 'CN=Disposable DEV-041 Probe', '-keyalg', 'RSA', '-validity', '2')
    signed = output / 'probe.apk'
    invoke('sign', java, '-jar', build_tools / 'lib/apksigner.jar', 'sign', '--ks', key,
           '--ks-pass', 'pass:disposable', '--key-pass', 'pass:disposable', '--out', signed, unsigned)

    report = {
        'milestone': 'DEV-041', 'status': 'built' if args.build_only else 'running',
        'runtime': runtime, 'package': package, 'probe_apk_sha256': file_sha(signed),
        'native_sha256': file_sha(native), 'abi': args.abi,
        'build_tools_sha256': {path.name: file_sha(path) for path in (clang, d8, android)},
        'ndk': (args.ndk / 'source.properties').read_text(encoding='utf-8').strip(),
        'aar_pin_source': pin_source.relative_to(ROOT).as_posix(), 'aar_source_commit': candidate,
        'aar_pins': {path.name: expected for path, expected in artifacts},
        'stdlib_sha256': stdlib_sha, 'source_sha256': {
            path.relative_to(ROOT).as_posix(): file_sha(path) for path in (
                Path(__file__).resolve(), PROBE, NATIVE, PIN, HISTORICAL, DECODE, V1, V2,
                ROOT / 'tools/sqlite_checkpoint_fixture.py', ROOT / 'tools/verify_sqlite_checkpoint_fixture.py')
        },
        'host_oracle': {'input_sha256': {key: sha(value) for key, value in INPUTS.items()},
                        'canonical_legacy_sha256': sha(imported.canonical),
                        'canonical_existing_sha256': sha(original.canonical)},
        'production_connected': False, 'physical_verified': False, 'power_loss_verified': False,
    }
    save(output / 'verification.json', report)
    if args.build_only:
        return

    events = {}
    pids = set()
    app_uid = None

    def step(label, stage, status, *, cut=False):
        nonlocal app_uid
        raw_result = shell(label, 'am', 'instrument', '-w', '-r', '-e', 'stage', stage, package + '/' + CLASSES)
        if cut:
            assert 'Process crashed' in raw_result, raw_result
            event = json.loads(device(label + '-marker', 'exec-out', 'run-as', package, 'cat', f'files/dev041/{stage}.json'))
            assert not shell(label + '-dead', 'pidof', package, codes=(0, 1))
            death = shell(label + '-signal', 'logcat', '-d', '--pid=' + str(event['pid']), '-s', 'Process:I', '*:S')
            assert re.search(r'Sending signal\. PID: ' + str(event['pid']) + r' SIG: 9\b', death), death
        else:
            prefix = 'INSTRUMENTATION_RESULT: dev041='
            assert 'INSTRUMENTATION_CODE: -1' in raw_result and 'probe_error' not in raw_result, raw_result
            event, = (json.loads(line[len(prefix):]) for line in raw_result.splitlines() if line.startswith(prefix))
        assert event['stage'] == stage and event['status'] == status, (label, event)
        assert event['pid'] > 0 and event['pid'] not in pids, (label, event)
        assert event['domain'].startswith('u:r:untrusted_app:') and event['uid'] >= 10000
        assert app_uid is None or app_uid == event['uid']
        app_uid = event['uid']
        pids.add(event['pid'])
        events[label] = event
        return event

    payloads = {sha(value): value for key, value in INPUTS.items() if key.startswith('resource-')}
    first, second = sorted(payloads)
    manifest_rows = [[key, len(payloads[key])] for key in sorted(payloads)]
    operation = 'attach-legacy'

    def document_row(checkpoint):
        return [checkpoint.document_id, checkpoint.revision, checkpoint.canonical.hex()]

    def operation_row(identifier, checkpoint, source):
        return [identifier, checkpoint.document_id, None, checkpoint.revision, sha(checkpoint.canonical), sha(source)]

    seeded = {
        'documents': [document_row(original)],
        'sources': [[original.document_id, INPUTS['existing.ncp'].hex()]],
        'operations': [operation_row('existing', original, INPUTS['existing.ncp'])],
        'resources': [], 'references': [], 'tasks': [], 'pins': [],
    }
    pending = {**seeded, 'tasks': [[operation, imported.document_id]],
               'pins': [[operation, *row] for row in manifest_rows]}
    committed = {
        **seeded, 'documents': sorted([document_row(imported), *seeded['documents']]),
        'sources': sorted([[imported.document_id, INPUTS['legacy.ncp'].hex()], *seeded['sources']]),
        'operations': [operation_row(operation, imported, INPUTS['legacy.ncp']), *seeded['operations']],
        'resources': manifest_rows, 'references': [[operation, row[0]] for row in manifest_rows],
    }
    inodes = {}

    def state(event, expected, targets):
        observed = event['state']
        assert observed['journal_mode'] == 'wal' and observed['writer_synchronous'] == '2'
        assert observed['integrity'] == 'ok' and observed['foreign_keys'] == [] and observed['schema_version'] == 2
        assert observed['inputs'] == report['host_oracle']['input_sha256']
        assert {key: observed[key] for key in expected} == expected, event['stage']
        files = observed['resources_on_disk']
        assert files.keys() == targets.keys(), (event['stage'], files)
        for key, data in targets.items():
            digest, size, inode = files[key]
            assert digest == sha(data) and size == len(data) and inode > 0
            assert inodes.setdefault(key, inode) == inode, (key, event['stage'])
        return files

    limited = {first: payloads[first]}
    damage = bytes([payloads[second][0] ^ 1]) + payloads[second][1:]
    collision = {**limited, second: damage}

    before = sorted(shell('packages-before', 'pm', 'list', 'packages', '-f').splitlines())
    assert all('dev.notecore.preview' not in line and package not in line for line in before), (
        'use a fresh disposable AVD; do not target an original install')
    try:
        assert device('install', 'install', signed).strip().endswith(b'Success')
        installed_apk = shell('installed-path', 'pm', 'path', package).removeprefix('package:')
        assert re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~:-]+/base.apk', installed_apk), installed_apk
        installed_digest = shell('installed-sha256', 'sha256sum', installed_apk).split()[0]
        assert installed_digest == report['probe_apk_sha256']
        initial = step('seed', 'init', 'SEEDED')
        assert initial['normal_control'] == '1'
        state(initial, seeded, {})

        cut1 = step('first-publish-cut', 'resource-published', 'CUT', cut=True)
        assert not cut1['inside_transaction'] and set(cut1['publications']) == {first}
        assert cut1['publications'][first]['errno'] == 0
        files = state(step('reopen-after-first', 'inspect', 'INSPECTED'), pending, limited)
        assert files[first][2] == cut1['publications'][first]['staged_inode']
        state(step('pending-conflict', 'conflict', 'OPERATION_CONFLICT'), pending, limited)

        state(step('inject-occupied', 'inject-occupied', 'INJECTED'), pending, collision)
        refuses = step('refuse-occupied', 'replay', 'REPAIR_REQUIRED')
        assert refuses['publications'][second]['errno'] == 17 and refuses['publications'][second]['source_preserved']
        state(refuses, pending, collision)
        state(step('remove-injected', 'remove-occupied', 'INJECTED'), pending, limited)
        del inodes[second]

        cut2 = step('before-commit-cut', 'before-commit', 'CUT', cut=True)
        assert cut2['inside_transaction'] and cut2['uncommitted_state'] == committed
        assert cut2['publications'][first]['errno'] == 17 and cut2['publications'][second]['errno'] == 0
        files = state(step('reopen-after-rollback', 'inspect', 'INSPECTED'), pending, payloads)
        assert files[second][2] == cut2['publications'][second]['staged_inode']

        cut3 = step('after-commit-cut', 'after-commit', 'CUT', cut=True)
        assert not cut3['inside_transaction']
        assert set(cut3['publications']) == set(payloads)
        assert all(item['errno'] == 17 and item['source_preserved'] for item in cut3['publications'].values())
        state(step('reopen-after-commit', 'inspect', 'INSPECTED'), committed, payloads)
        replay = step('exact-replay', 'replay', 'REPLAYED')
        assert not replay['publications']
        state(replay, committed, payloads)
        state(step('committed-conflict', 'conflict', 'OPERATION_CONFLICT'), committed, payloads)
        state(step('damage-committed', 'damage-committed', 'INJECTED'), committed, collision)
        state(step('damaged-replay', 'replay', 'REPAIR_REQUIRED'), committed, collision)
    finally:
        installed = shell('probe-package', 'pm', 'path', package, codes=(0, 1))
        if installed:
            assert device('uninstall', 'uninstall', package).strip() == b'Success'
        after = sorted(shell('packages-after', 'pm', 'list', 'packages', '-f').splitlines())
        assert after == before
    # SQL checks above compare exact bytes. Keep those bytes in raw logs and summarize them here.
    for event in events.values():
        for key in ('state', 'uncommitted_state'):
            if key in event:
                snapshot = event[key]
                for table in ('documents', 'sources'):
                    snapshot[table] = [[*row[:-1], sha(bytes.fromhex(row[-1]))] for row in snapshot[table]]
    report.update(status='passed', events=events, resource_manifest=manifest_rows,
                  document_and_source_columns='Final column is SHA-256 of the exact bytes compared in raw logs',
                  installed_apk_sha256=installed_digest, emulator_avd=avd,
                  emulator_package_inventory_unchanged=True, probe_removed=True)
    log_archive = output / 'raw-logs.zip'
    with zipfile.ZipFile(log_archive, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(raw.iterdir()):
            archive.write(path, path.name)
    report['raw_logs_sha256'] = file_sha(log_archive)
    save(output / 'verification.json', report)
    print(f'{output / "verification.json"}: {len(events)} API 26 stages, 3 SIGKILL cuts')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('ndk', 'stdlib', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--repository', type=Path, help='Use the exact 2026-11-17 pinned Maven AARs instead of the checked-in historical pins')
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME'), required=not os.environ.get('ANDROID_HOME'))
    parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME'), required=not os.environ.get('JAVA_HOME'))
    parser.add_argument('--build-tools', default='36.1.0')
    parser.add_argument('--compile-sdk', default='36')
    parser.add_argument('--abi', choices=ABIS, required=True)
    parser.add_argument('--serial')
    parser.add_argument('--avd')
    parser.add_argument('--build-only', action='store_true')
    args = parser.parse_args()
    if not args.build_only and not (args.serial and args.avd):
        parser.error('device verification needs --serial and --avd')
    if not __debug__:
        parser.error('assertions must be enabled')
    run(args)


if __name__ == '__main__':
    main()
