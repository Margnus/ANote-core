"""Run the pending-preview SQLite migration in a disposable Android API 26 App sandbox."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import uuid
import zipfile

from sqlite_checkpoint_fixture import SCHEMA
from verify_sqlite_pending_recovery import ROOT, PIN, STDLIB_PIN, sha

PROBE = ROOT / 'tools/probes/SqlitePendingCheckpointAndroid.java'
MANIFEST = ROOT / 'tools/probes/atomic-promote-AndroidManifest.xml'
HOST = ROOT / 'reports/dev041-pending-recovery-20261118.json'
CLASS = 'dev.notecore.probes.SqlitePendingCheckpointAndroid'


def verify(args, work):
    sdk, jdk = (Path(os.environ[key]) for key in ('ANDROID_HOME', 'JAVA_HOME'))
    tools = sdk / 'build-tools/36.0.0'
    android_jar = sdk / 'platforms/android-36/android.jar'
    adb = sdk / 'platform-tools/adb'
    log = args.output / 'commands.log'

    def run(*command, codes=(0,), timeout=45, binary=False):
        command = list(map(str, command))
        result = subprocess.run(command, capture_output=True, timeout=timeout)
        with log.open('a', encoding='utf-8') as stream:
            stream.write(f'{result.returncode} {shlex.join(command)}\n{result.stderr.decode(errors="replace")}')
            if not binary:
                stream.write(result.stdout.decode(errors='replace'))
        if result.returncode not in codes:
            raise RuntimeError(f'{shlex.join(command)} exited {result.returncode}; see {log}')
        return result.stdout if binary else result.stdout.decode().strip()

    pin = json.loads(PIN.read_bytes())
    host = json.loads(HOST.read_bytes())
    assert host['candidate_source_commit'] == pin['candidate_source_commit'] and host['status'] == 'passed'
    fixtures = {Path(name).name: ROOT / name for name in pin['apple']['fixture_sha256']}
    for path in fixtures.values():
        assert sha(path) == pin['apple']['fixture_sha256'][path.relative_to(ROOT).as_posix()], path
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

    suffix = uuid.uuid4().hex
    package = 'dev.notecore.probes.sqlitepending.p' + suffix
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text(MANIFEST.read_text(encoding='utf-8')
                        .replace('dev.notecore.probes.atomicpromote', package)
                        .replace('dev.notecore.probes.AtomicPromoteProbe', CLASS)
                        .replace('Disposable atomic promote probe', 'Disposable SQLite pending recovery probe'),
                        encoding='utf-8')
    classes = work / 'classes'
    dex = work / 'dex.zip'
    run(jdk / 'bin/javac', '--release', '8', '-cp', os.pathsep.join(map(str, (android_jar, *jars))),
        '-d', classes, PROBE)
    run(jdk / 'bin/java', '-Xmx512m', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '26', '--lib', android_jar, '--output', dex, *sorted(classes.rglob('*.class')), *jars)
    unsigned, apk, key = (work / name for name in ('unsigned.apk', 'probe.apk', 'probe.keystore'))
    run(tools / 'aapt2', 'link', '-I', android_jar, '--manifest', manifest, '-o', unsigned)
    with zipfile.ZipFile(dex) as compiled, zipfile.ZipFile(unsigned, 'a') as merged:
        for name in compiled.namelist():
            if re.fullmatch(r'classes[0-9]*\.dex', name):
                merged.writestr(name, compiled.read(name))
        for name, path in fixtures.items():
            merged.write(path, 'assets/' + name)
        merged.writestr('assets/schema.json', json.dumps(SCHEMA))
    run(jdk / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'disposable', '-alias', 'probe',
        '-dname', 'CN=Disposable NoteCore Probe', '-keyalg', 'RSA', '-validity', '2')
    run(tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:disposable', '--out', apk, unsigned)
    run(tools / 'apksigner', 'verify', apk)

    serial = f'emulator-{args.port}'
    assert not any(line.split()[:1] == [serial] for line in run(adb, 'devices').splitlines())
    name = 'NoteCorePending26-' + suffix
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
    env = {**os.environ, 'ANDROID_AVD_HOME': str(avds), 'ANDROID_EMULATOR_HOME': str(work),
           'ANDROID_USER_HOME': str(work)}

    def device(*command, **kwargs):
        return run(adb, '-s', serial, *command, **kwargs)

    def private_file(filename):
        return device('exec-out', 'run-as', package, 'cat', 'files/' + filename, binary=True)

    observations = {}
    with (args.output / 'emulator.log').open('wb') as stream:
        emulator = subprocess.Popen([sdk / 'emulator/emulator', '-avd', name, '-port', str(args.port),
                                    '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect'],
                                   env=env, stdout=stream, stderr=subprocess.STDOUT)
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
                'abi': ('getprop', 'ro.product.cpu.abi'), 'selinux': ('getenforce',),
            }.items()}
            assert (runtime['api'], runtime['abi'], runtime['selinux']) == ('26', 'arm64-v8a', 'Enforcing')
            assert device('install', apk).splitlines()[-1] == 'Success'
            installed = device('shell', 'pm', 'path', package).removeprefix('package:')
            assert installed.startswith('/data/app/') and installed.endswith('/base.apk')
            assert device('exec-out', 'cat', installed, binary=True) == apk.read_bytes()
            for stage, status in (('seed', 'SEEDED'), ('invalid', 'INVALID_SOURCE'), ('capacity', 'INPUT_CAPACITY'),
                                  ('before-commit', None), ('empty', 'EMPTY'), ('after-commit', None),
                                  ('present', 'PRESENT'), ('replay', 'REPLAYED'), ('duplicate', 'STALE_BASE'),
                                  ('present', 'PRESENT')):
                result = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'stage', stage,
                                f'{package}/{CLASS}', codes=(0, 1))
                prefix = 'INSTRUMENTATION_RESULT: pending='
                if status is None:
                    assert 'Process crashed' in result and prefix not in result, result
                    event = json.loads(private_file(stage + '.json'))
                else:
                    assert 'INSTRUMENTATION_CODE: -1' in result, result
                    line, = [line for line in result.splitlines() if line.startswith(prefix)]
                    event = json.loads(line[len(prefix):])
                    assert event['status'] == status, event
                assert event['stage'] == stage and event['pid'] > 0
                assert event['pid'] not in {entry['pid'] for entry in observations.values()}, event
                assert re.fullmatch(r'u:r:untrusted_app(?:_[0-9]+)?:s0.*', event['domain']), event
                assert private_file('legacy-recovery.ncp') == fixtures['prepare.ncp'].read_bytes()
                observations[f'{len(observations):02d}-{stage}'] = event
        finally:
            if emulator.poll() is None:
                emulator.terminate()
                try:
                    emulator.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    emulator.kill()
                    emulator.wait(timeout=10)

    return {
        'status': 'passed', 'scope': 'Android API 26 instrumentation in a fresh App sandbox; process kills',
        'candidate_source_commit': pin['candidate_source_commit'], 'runtime': runtime, 'observations': observations,
        'source_unchanged': True, 'checkpoint_revision': host['checkpoint_revision'],
        'source_archive_sha256': sha(fixtures['prepare.ncp']), 'canonical_sha256': sha(fixtures['confirmed-final.ncp']),
        'apk_sha256': sha(apk), 'aars_sha256': aars, 'stdlib_sha256': stdlib_sha,
        'inputs_sha256': {path.relative_to(ROOT).as_posix(): sha(path) for path in (
            Path(__file__).resolve(), PROBE, MANIFEST, ROOT / 'tools/sqlite_checkpoint_fixture.py',
            PIN, STDLIB_PIN, HOST, *fixtures.values())},
        'raw_sha256': {path.name: sha(path) for path in sorted(args.output.iterdir())},
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--stdlib', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', type=int, default=5588)
    args = parser.parse_args()
    args.stdlib = args.stdlib.resolve()
    args.output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='dev041-android-pending-', dir=ROOT / 'build') as directory:
        report = verify(args, Path(directory))
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
