"""Revalidate native SQLite capabilities on disposable Android and Apple simulators."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid
import zipfile

from verify_sqlite_capabilities import JAVA, ROOT, SWIFT, SWIFT_SUPPORT, matrix, sha

MATRIX = ROOT / 'tools/verify_sqlite_capabilities.py'


def command(*args, timeout=60, codes=(0,)):
    result = subprocess.run(list(map(str, args)), cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode not in codes:
        raise RuntimeError((result.args, result.returncode, result.stdout, result.stderr))
    return result


def android(args, output, work):
    sdk, jdk = (Path(os.environ[key]) for key in ('ANDROID_HOME', 'JAVA_HOME'))
    tools = sdk / 'build-tools/36.0.0'
    android_jar = sdk / 'platforms/android-36/android.jar'
    adb = sdk / 'platform-tools/adb'
    serial = f'emulator-{args.port}'
    assert not any(line.split()[:1] == [serial] for line in command(adb, 'devices').stdout.splitlines())
    suffix = uuid.uuid4().hex
    package = 'dev.notecore.probes.sqlitecapabilities.p' + suffix
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">'
                        '<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>'
                        '<application android:debuggable="true" android:allowBackup="false"/></manifest>',
                        encoding='utf-8')
    classes, dex, unsigned, key = (work / name for name in ('classes', 'dex.zip', 'unsigned.apk', 'probe.keystore'))
    apk = output / 'sqlite-probe.apk'
    command(jdk / 'bin/javac', '--release', '8', '-cp', android_jar, '-d', classes, ROOT / JAVA)
    command(jdk / 'bin/java', '-Xmx512m', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26',
            '--lib', android_jar, '--output', dex, *sorted(classes.rglob('*.class')))
    command(tools / 'aapt2', 'link', '-I', android_jar, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(dex) as compiled, zipfile.ZipFile(unsigned, 'a') as merged:
        for name in compiled.namelist():
            if re.fullmatch(r'classes[0-9]*\.dex', name):
                merged.writestr(name, compiled.read(name))
    command(jdk / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'disposable', '-alias', 'probe',
            '-dname', 'CN=Disposable NoteCore Probe', '-keyalg', 'RSA', '-validity', '2')
    command(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:disposable', '--out', apk, unsigned)
    command(tools / 'apksigner', 'verify', apk)

    name = 'NoteCoreSqliteMatrix-' + suffix
    avds = work / 'avd'
    avd = avds / (name + '.avd')
    avd.mkdir(parents=True)
    (avds / (name + '.ini')).write_text(f'avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-26\n', encoding='utf-8')
    configuration = {'AvdId': name, 'avd.ini.displayname': name, 'abi.type': 'arm64-v8a', 'hw.cpu.arch': 'arm64',
                     'hw.cpu.ncore': '4', 'hw.ramSize': '2048', 'image.sysdir.1': str(args.image.resolve()) + '/',
                     'tag.id': 'default', 'tag.display': 'Default Android System Image',
                     'disk.dataPartition.size': '2147483648', 'hw.lcd.width': '1080', 'hw.lcd.height': '1920',
                     'hw.lcd.density': '420'}
    (avd / 'config.ini').write_text(''.join(f'{key}={value}\n' for key, value in configuration.items()), encoding='utf-8')
    env = {**os.environ, 'ANDROID_AVD_HOME': str(avds), 'ANDROID_EMULATOR_HOME': str(work), 'ANDROID_USER_HOME': str(work)}

    def device(*arguments, **kwargs):
        return command(adb, '-s', serial, *arguments, **kwargs)

    with (output / 'emulator.log').open('wb') as emulator_log:
        emulator = subprocess.Popen([sdk / 'emulator/emulator', '-avd', name, '-port', str(args.port),
                                    '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect'],
                                   env=env, stdout=emulator_log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                assert emulator.poll() is None, 'emulator exited; see emulator.log'
                if device('shell', 'getprop', 'sys.boot_completed', codes=(0, 1, 255), timeout=10).stdout.strip() == '1':
                    break
                time.sleep(2)
            else:
                raise RuntimeError('isolated emulator did not boot')
            assert device('shell', 'getprop', 'ro.kernel.qemu.avd_name').stdout.strip() == name
            runtime = {key: device('shell', *query).stdout.strip() for key, query in {
                'api': ('getprop', 'ro.build.version.sdk'), 'fingerprint': ('getprop', 'ro.build.fingerprint'),
                'abi': ('getprop', 'ro.product.cpu.abi'), 'selinux': ('getenforce',),
            }.items()}
            assert (runtime['api'], runtime['abi'], runtime['selinux']) == ('26', 'arm64-v8a', 'Enforcing')
            assert device('install', apk).stdout.splitlines()[-1] == 'Success'
            installed = device('shell', 'pm', 'path', package).stdout.strip().removeprefix('package:')
            assert installed.startswith('/data/app/') and installed.endswith('/base.apk')
            actual = subprocess.check_output([str(adb), '-s', serial, 'exec-out', 'cat', installed], timeout=30)
            assert hashlib.sha256(actual).hexdigest() == sha(apk)
            uid = device('shell', 'run-as', package, 'id').stdout.strip()
            assert re.match(r'uid=\d+\(u0_a\d+\)', uid), uid
            folder = f'cache/sqlite-capability-{suffix}'
            def invoke(stage):
                return device('shell', 'run-as', package, '/system/bin/env', f'CLASSPATH={installed}',
                              '/system/bin/app_process', '/system/bin', 'dev.notecore.app.probes.SqliteCrashProbe',
                              folder, stage, codes=(0, 137))
            with (output / 'android-matrix.log').open('w', encoding='utf-8') as log:
                values = matrix(invoke, log)
        finally:
            if emulator.poll() is None:
                emulator.terminate()
                try:
                    emulator.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    emulator.kill()
                    emulator.wait(timeout=10)
    return {'runtime': runtime, 'app_uid': uid, 'probe_apk_sha256': sha(apk), 'observations': values}


def apple(args, output, work):
    run = ('xcrun', 'simctl')
    binary = output / 'SqliteCrashProbe'
    compilation = command('xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios17.0-simulator',
                          '-module-cache-path', work / 'modules', ROOT / SWIFT_SUPPORT, ROOT / SWIFT,
                          '-lsqlite3', '-o', binary)
    (output / 'apple-build.log').write_text(compilation.stdout + compilation.stderr, encoding='utf-8')
    link = command('xcrun', 'vtool', '-show-build', binary).stdout
    assert 'minos 17.0' in link and 'IOSSIMULATOR' in link
    runtime_info = json.loads(command(*run, 'list', 'runtimes', '-j').stdout)
    runtime, = [row for row in runtime_info['runtimes'] if row['identifier'] == args.runtime and row['isAvailable']]
    device = command(*run, 'create', 'NoteCoreSqliteMatrix-' + uuid.uuid4().hex, args.device_type,
                     args.runtime).stdout.strip()
    uuid.UUID(device)
    try:
        command(*run, 'boot', device)
        command(*run, 'bootstatus', device, '-b', timeout=180)
        with tempfile.TemporaryDirectory(prefix='sqlite-capability-', dir=ROOT / 'build') as folder:
            def invoke(stage):
                return command(*run, 'spawn', device, binary, folder, stage, codes=(0, 137))
            with (output / 'apple-matrix.log').open('w', encoding='utf-8') as log:
                values = matrix(invoke, log)
    finally:
        try:
            command(*run, 'shutdown', device, codes=(0, 1, 149))
        finally:
            command(*run, 'delete', device)
    return {'runtime': {'identifier': args.runtime, 'version': runtime['version'], 'build': runtime['buildversion']},
            'probe_binary_sha256': sha(binary), 'binary_build_version': link, 'observations': values}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', default=5588, type=int)
    parser.add_argument('--runtime', default='com.apple.CoreSimulator.SimRuntime.iOS-27-0')
    parser.add_argument('--device-type', default='com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='dev041-sqlite-capability-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        android_report = android(args, output, work)
        apple_report = apple(args, output, work)
    shared = {'sqlite_version'}
    assert [{key: value for key, value in item.items() if key not in shared} for item in android_report['observations']] == [
        {key: value for key, value in item.items() if key not in shared} for item in apple_report['observations']]
    report = {
        'status': 'passed', 'scope': 'Native SQLite recovery and refusal matrix on disposable simulators',
        'android': android_report, 'apple': apple_report, 'same_observations': True,
        'source_sha256': {path.relative_to(ROOT).as_posix(): sha(path) for path in (
            Path(__file__).resolve(), MATRIX, ROOT / JAVA, ROOT / SWIFT, ROOT / SWIFT_SUPPORT)},
        'retained_artifact_sha256': {path.name: sha(path) for path in sorted(output.iterdir()) if path.is_file()},
    }
    (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
