#!/usr/bin/env python3
"""Run the current App's existing contracts on an isolated, disposable ARM64 API32.

Requires already-built APKs and an already-installed image. No SDK download,
original-device boot, production API change, or API33 SurfaceControl claim.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from types import SimpleNamespace
import zipfile

import verify_android_input_host as host

ROOT = Path(__file__).resolve().parents[1]
SDK = Path('/Users/lixiaofan/Library/Android/sdk')
RAW = ROOT / 'reports/android-app-api32-20261031'
WORK = ROOT / 'output/api32-app/device'
SERIAL = 'emulator-5586'
AVD = 'NoteCorePath32'


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main(*, main_owner_crash=False):
    scope_dir = ROOT / 'reports/android-owner-main-crash-api32-20261031' if main_owner_crash else RAW
    report_path = ROOT / 'reports/android-owner-main-crash-api32.json' if main_owner_crash else ROOT / 'reports/android-app-api32.json'
    scope_dir.mkdir(exist_ok=False)
    WORK.mkdir(parents=True, exist_ok=False)
    image = SDK / 'system-images/android-32/google_apis/arm64-v8a'
    assert (image / 'package.xml').is_file()
    apk = ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk'
    test = ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    provenance = None
    if main_owner_crash:
        prior_path = ROOT / 'reports/android-app-api32.json'
        prior = json.loads(prior_path.read_text(encoding='utf-8'))
        assert prior['status'] == 'passed'
        old_raw = ROOT / 'reports/android-app-api32-20261031'
        retained = old_raw / 'executables.zip'
        stamp = old_raw / 'build-inputs.sha256'
        for path in (retained, stamp, old_raw / 'build.log'):
            assert sha(path) == prior['evidence_sha256'][path.relative_to(ROOT).as_posix()]
        apk = WORK / 'retained-app.apk'; test = WORK / 'retained-test.apk'
        with zipfile.ZipFile(retained) as archive:
            apk.write_bytes(archive.read('app.apk')); test.write_bytes(archive.read('instrumentation.apk'))
        assert sha(apk) == prior['apk_sha256'] and sha(test) == prior['test_apk_sha256']
        provenance = {'report': prior_path.relative_to(ROOT).as_posix(), 'sha256': sha(prior_path),
                      'archive_sha256': sha(retained), 'rebuilt': False}
        build_log = old_raw / 'build.log'
    else:
        stamp = ROOT / 'reports/android-brush-build-inputs.sha256'
        build_log = ROOT / 'output/api32-app/build.log'
    inputs = dict(line.split('  ', 1)[::-1] for line in stamp.read_text(encoding='utf-8').splitlines())
    overrides = {'android-app/build/outputs/apk/debug/android-app-debug.apk': apk,
                 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk': test}
    assert all(sha(overrides.get(path, ROOT / path)) == digest for path, digest in inputs.items()), 'Stale build'
    shutil.copy2(stamp, scope_dir / 'build-inputs.sha256')
    shutil.copy2(build_log, scope_dir / 'build.log')
    shutil.copy2(Path(__file__), scope_dir / 'executed-runner.py.txt')
    sources = {path: digest for path, digest in inputs.items() if '/build/' not in path}
    sources[Path(__file__).relative_to(ROOT).as_posix()] = sha(Path(__file__))
    sources['tools/verify_android_input_host.py'] = sha(ROOT / 'tools/verify_android_input_host.py')
    with zipfile.ZipFile(scope_dir / 'executables.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(apk, 'app.apk'); archive.write(test, 'instrumentation.apk')
    original = ROOT / '.android-avd/NoteCorePreview.avd'

    def original_files():
        processes = subprocess.check_output(['ps', '-axo', 'command='], text=True)
        assert not any('qemu-system' in row and ('NoteCorePreview' in row or str(original) in row)
                       for row in processes.splitlines()), 'Original device must stay stopped'
        return {p.relative_to(original).as_posix(): {'bytes': p.stat().st_size, 'sha256': sha(p)}
                for p in sorted(original.rglob('*')) if p.is_file()}

    before = original_files(); assert before
    save(scope_dir / 'original-before.json', before)
    env = {**os.environ, 'ANDROID_AVD_HOME': str(WORK / 'avds'),
           'ANDROID_USER_HOME': str(WORK / 'user'), 'ANDROID_EMULATOR_HOME': str(WORK / 'user')}
    records = []

    def run(command, label, timeout=120):
        result = subprocess.run([str(a) for a in command], capture_output=True, timeout=timeout, env=env, cwd=ROOT)
        (scope_dir / (label + '.stdout.log')).write_bytes(result.stdout)
        (scope_dir / (label + '.stderr.log')).write_bytes(result.stderr)
        records.append({'label': label, 'argv': [str(a) for a in command], 'returncode': result.returncode})
        save(scope_dir / 'execution.json', records)
        assert result.returncode == 0, (label, result.stderr[-1500:], result.stdout[-1500:])
        return result.stdout

    count = 0

    def adb(*args):
        nonlocal count
        count += 1
        return run([SDK / 'platform-tools/adb', '-s', SERIAL, *args], 'adb-%04d' % count)

    assert SERIAL not in run([SDK / 'platform-tools/adb', 'devices'], 'devices-before').decode()
    assert shutil.disk_usage(ROOT).free > 8 * 2**30, 'Insufficient room for temporary device'
    avd = WORK / 'avds' / (AVD + '.avd'); avd.mkdir(parents=True)
    (WORK / 'user').mkdir()
    values = dict(line.split('=', 1) for line in (original / 'config.ini').read_text(encoding='utf-8').splitlines() if '=' in line)
    values = {key.strip(): value.strip() for key, value in values.items()}
    values.update({'AvdId': AVD, 'avd.ini.displayname': AVD, 'image.sysdir.1': str(image) + '/',
                   'tag.id': 'google_apis', 'tag.display': 'Google APIs', 'PlayStore.enabled': 'false',
                   'hw.cpu.ncore': '2', 'hw.ramSize': '1536', 'disk.dataPartition.size': '6442450944'})
    config = ''.join(key + ' = ' + value + '\n' for key, value in values.items())
    (avd / 'config.ini').write_text(config, encoding='utf-8')
    (WORK / 'avds' / (AVD + '.ini')).write_text('avd.ini.encoding=UTF-8\npath=' + str(avd) + '\ntarget=android-32\n', encoding='utf-8')
    (scope_dir / 'initial-config.ini').write_text(config, encoding='utf-8')
    log = (WORK / 'emulator.log').open('wb')
    command = [str(SDK / 'emulator/emulator'), '-avd', AVD, '-port', '5586', '-no-window', '-no-audio',
               '-no-snapshot', '-no-boot-anim', '-gpu', 'swiftshader_indirect', '-memory', '1536', '-cores', '2']
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
    save(scope_dir / 'emulator-process.json', {'pid': process.pid, 'argv': command})
    completed = False
    try:
        deadline = time.monotonic() + 480
        while True:
            assert process.poll() is None, 'Emulator exited; inspect log'
            probe = subprocess.run([str(SDK / 'platform-tools/adb'), '-s', SERIAL, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, timeout=20)
            if probe.returncode == 0 and probe.stdout.strip() == b'1':
                break
            assert time.monotonic() < deadline, 'Boot observation deadline; inspect this PID'
            time.sleep(2)
        assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name').decode().strip() == AVD
        assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == b'32'
        assert adb('shell', 'getprop', 'ro.product.cpu.abi').strip() == b'arm64-v8a'
        environment = {key: adb('shell', 'getprop', key).decode().strip() for key in
                       ('ro.build.fingerprint', 'ro.build.version.sdk', 'ro.product.cpu.abi')}
        assert not adb('shell', 'pm', 'list', 'packages', host.BUNDLE).strip()
        adb('install', '-r', str(apk)); adb('install', '-r', str(test))
        for package, local in ((host.BUNDLE, apk), (host.BUNDLE + '.test', test)):
            remote = adb('shell', 'pm', 'path', package).decode().strip().removeprefix('package:')
            assert remote.startswith('/data/app/') and remote.endswith('/base.apk')
            assert hashlib.sha256(adb('exec-out', 'cat', remote)).hexdigest() == sha(local)
        assert b'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', host.BUNDLE + '/dev.notecore.app.MainActivity')

        def files():
            names = adb('shell', 'run-as', host.BUNDLE, 'ls', 'files/preview-notes').decode().splitlines()
            return {name: hashlib.sha256(adb('exec-out', 'run-as', host.BUNDLE, 'cat', 'files/preview-notes/' + name)).hexdigest()
                    for name in names if re.fullmatch(r'[a-zA-Z0-9-]+\.ncp', name)}

        baseline = files()
        observations = {}
        for case, prefix, checks in ([] if main_owner_crash else [('input_batches', 'input', host.CHECKS),
                                     ('storage_retention', 'storage', host.STORAGE_CHECKS),
                                     ('owner_retirement', 'owner', host.OWNER_CHECKS), ('minimum_api', 'minimum_api', None)]):
            raw = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', case,
                      host.BUNDLE + '.test/dev.notecore.app.ViewportInstrumentation').decode()
            (scope_dir / (case + '.log')).write_text(raw, encoding='utf-8')
            assert prefix + '_status=passed' in raw and 'INSTRUMENTATION_CODE: -1' in raw, raw
            observed = json.loads(re.search(prefix + r'_observations=(\{.*\})', raw).group(1))
            if checks is not None:
                assert set(observed['checks']) == checks and len(observed['checks']) == len(checks)
            observations[case] = observed
            assert files() == baseline
        if not main_owner_crash:
            assert observations['minimum_api']['surface_render_bytes'] == 0
            assert observations['minimum_api']['line_dot_undo_redo_page_pixels']
        bindings = {}
        checkpoint = host.process_checkpoint(SimpleNamespace(path_api32=True, serial=SERIAL, avd=AVD,
            adb=str(SDK / 'platform-tools/adb')), adb, scope_dir, files, baseline, bindings, main_owner_crash=main_owner_crash)
        assert files() == baseline
        adb('shell', 'am', 'force-stop', host.BUNDLE)
        assert b'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', host.BUNDLE + '/dev.notecore.app.MainActivity')
        assert files() == baseline
        ordinary_pid = int(adb('shell', 'pidof', host.BUNDLE).strip())
        adb('uninstall', host.BUNDLE + '.test'); adb('uninstall', host.BUNDLE)
        report = {'status': 'passed', 'environment': environment, 'observations': observations,
                  'process_checkpoint': checkpoint, 'ordinary_launch_pid': ordinary_pid,
                  'isolated_note_baseline': baseline, 'apk_sha256': sha(apk), 'test_apk_sha256': sha(test),
                  'scope': 'Current original App/Store/View on isolated API32 ARM64. Synthetic callbacks, software Bitmap pixels, explicit owner retirement, confirmed and visible-undelivered writes followed by process force-stop. No ordinary UI gestures, physical stylus/heap, API33 native overlap, partial-write/power-loss, production SQLite or C1 freeze.'}
        if main_owner_crash:
            report['scope'] = checkpoint['scope'] + ' Retained unchanged App/instrumentation APKs; no input/storage/owner/pixel matrix rerun.'
            report['artifact_reuse'] = provenance
        completed = True
    finally:
        if process.poll() is None:
            identity = adb('shell', 'getprop', 'ro.boot.qemu.avd_name').decode().strip()
            assert identity == AVD
            adb('emu', 'kill'); process.wait(timeout=60)
        log.close()
        with gzip.open(scope_dir / 'emulator.log.gz', 'wb') as output:
            output.write((WORK / 'emulator.log').read_bytes())
        after = original_files(); save(scope_dir / 'original-after.json', after)
        assert after == before, 'Original AVD changed'
        shutil.rmtree(WORK)
        save(scope_dir / 'cleanup.json', {'original_avd_all_bytes_unchanged': True,
             'temporary_device_removed': not WORK.exists(), 'emulator_returncode': process.returncode,
             'runtime_completed': completed})
    report['source_sha256'] = sources
    report['evidence_sha256'] = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(scope_dir.rglob('*')) if p.is_file()}
    save(report_path, report)
    print('API32 current App contracts passed; isolated device removed', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--main-owner-crash', action='store_true', help='Reuse source-bound retained APKs for two confirmed-checkpoint uncaught main exceptions only')
    main(main_owner_crash=parser.parse_args().main_owner_crash)
