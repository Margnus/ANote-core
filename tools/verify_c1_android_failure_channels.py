"""Run an independent Java consumer of the fixed C1 AARs on an isolated API 26 AVD."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / 'reports/c1-pinned-sdk-20261117.json'
APPLE = ROOT / 'reports/c1-apple-failure-channels-20261118.json'
STDLIB_PIN = ROOT / 'reports/apple-sqlite-joint-checkpoint-20261117/verification.json'
CONSUMER = ROOT / 'tools/probes/C1AndroidFailureConsumer.java'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(args, work):
    sdk, jdk = (Path(os.environ[key]) for key in ('ANDROID_HOME', 'JAVA_HOME'))
    android_jar = sdk / 'platforms/android-36/android.jar'
    adb = sdk / 'platform-tools/adb'
    log = args.output / 'commands.log'

    def run(*command, codes=(0,), timeout=60):
        command = list(map(str, command))
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        with log.open('a', encoding='utf-8') as stream:
            stream.write(f'{result.returncode} {shlex.join(command)}\n{result.stdout}{result.stderr}')
        if result.returncode not in codes:
            raise RuntimeError(f'{shlex.join(command)} exited {result.returncode}; see {log}')
        return result.stdout.strip()

    pin = json.loads(PIN.read_bytes())
    apple = json.loads(APPLE.read_bytes())
    assert apple['candidate_source_commit'] == pin['candidate_source_commit']
    stdlib_sha = json.loads(STDLIB_PIN.read_bytes())['host_jvm_sha256']['kotlin-stdlib-2.2.21.jar']
    assert sha(args.stdlib) == stdlib_sha
    jars = [args.stdlib]
    aars = {}
    for name, spec in pin['android']['rebuilt_aars'].items():
        artifact = ROOT / spec['artifact']
        aars[name] = sha(artifact)
        assert aars[name] == spec['sha256'], name
        classes = work / (name + '.jar')
        with zipfile.ZipFile(artifact) as archive:
            classes.write_bytes(archive.read('classes.jar'))
        jars.append(classes)

    fixtures = {Path(name).name: ROOT / name for name in pin['apple']['fixture_sha256']}
    for path in fixtures.values():
        assert sha(path) == pin['apple']['fixture_sha256'][path.relative_to(ROOT).as_posix()], path
    classes = work / 'classes'
    dex = work / 'consumer.jar'
    run(jdk / 'bin/javac', '--release', '8', '-cp', os.pathsep.join(map(str, (android_jar, *jars))),
        '-d', classes, CONSUMER)
    run(jdk / 'bin/java', '-Xmx512m', '-cp', sdk / 'build-tools/36.0.0/lib/d8.jar',
        'com.android.tools.r8.D8', '--min-api', '26', '--lib', android_jar, '--output', dex,
        *sorted(classes.rglob('*.class')), *jars)

    serial = f'emulator-{args.port}'
    assert not any(line.split()[:1] == [serial] for line in run(adb, 'devices').splitlines())
    name = 'NoteCoreC1Failure26-' + uuid.uuid4().hex
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
    emulator_env = {**os.environ, 'ANDROID_AVD_HOME': str(avds), 'ANDROID_EMULATOR_HOME': str(work),
                    'ANDROID_USER_HOME': str(work)}

    def device(*command, **kwargs):
        return run(adb, '-s', serial, *command, **kwargs)

    with (args.output / 'emulator.log').open('wb') as emulator_log:
        emulator = subprocess.Popen([sdk / 'emulator/emulator', '-avd', name, '-port', str(args.port),
                                    '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect'],
                                   env=emulator_env, stdout=emulator_log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                assert emulator.poll() is None, 'emulator exited; see emulator.log'
                if device('shell', 'getprop', 'sys.boot_completed', codes=(0, 1, 255), timeout=10) == '1':
                    break
                time.sleep(2)
            else:
                raise RuntimeError('isolated emulator did not boot')
            assert device('shell', 'getprop', 'ro.kernel.qemu.avd_name') == name
            runtime = {key: device('shell', *command) for key, command in {
                'api': ('getprop', 'ro.build.version.sdk'), 'fingerprint': ('getprop', 'ro.build.fingerprint'),
                'abi': ('getprop', 'ro.product.cpu.abi'), 'selinux': ('getenforce',), 'kernel': ('uname', '-r'),
            }.items()}
            assert (runtime['api'], runtime['abi'], runtime['selinux']) == ('26', 'arm64-v8a', 'Enforcing')
            remote = '/data/local/tmp/' + name
            device('shell', 'mkdir', remote)
            for path in (dex, *fixtures.values()):
                deployed = f'{remote}/{path.name}'
                device('push', path, deployed)
                assert device('shell', 'sha256sum', deployed).split()[0] == sha(path)
            result = device('shell', f'CLASSPATH={remote}/{dex.name} /system/bin/app_process /system/bin '
                            f'dev.notecore.probes.C1AndroidFailureConsumer '
                            f'{remote}/prepare.ncp {remote}/confirmed-final.ncp')
            receipt = json.loads(result)
        finally:
            if emulator.poll() is None:
                emulator.terminate()
                try:
                    emulator.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    emulator.kill()
                    emulator.wait(timeout=10)

    assert receipt.pop('pid') > 0
    exceptions = {key: receipt.pop(key) for key in (
        'budget_constructor_exception', 'budget_copy_exception', 'malformed_recovery_exception')}
    # The Java probe already requires assignability to IllegalArgumentException.
    assert all(exceptions.values())
    apple_typed = dict(apple['selected_typed_channels_and_recovery'])
    apple_typed.pop('pid')
    assert apple_typed == receipt, (apple_typed, receipt)

    return {
        'status': 'passed', 'candidate_source_commit': pin['candidate_source_commit'],
        'scope': 'Independent Java executable under Android API 26 ART via app_process on an isolated AVD',
        'runtime': runtime, 'selected_typed_channels_and_recovery': receipt, 'compatibility_exceptions': exceptions,
        'compatibility_exception_base': 'IllegalArgumentException', 'typed_observations_match_apple': True, 'android_aars_sha256': aars, 'kotlin_stdlib_sha256': stdlib_sha,
        'consumer_dex_sha256': sha(dex),
        'source_sha256': {path.relative_to(ROOT).as_posix(): sha(path)
                          for path in (Path(__file__).resolve(), CONSUMER, PIN, APPLE, STDLIB_PIN, *fixtures.values())},
        'raw_sha256': {path.name: sha(path) for path in sorted(args.output.iterdir())},
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--stdlib', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', default=5586, type=int)
    args = parser.parse_args()
    args.stdlib = args.stdlib.resolve()
    args.output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='c1-android-failure-', dir=ROOT / 'build') as directory:
        report = verify(args, Path(directory))
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
