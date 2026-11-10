"""Rebuild and run the original low-API consumer suites in a dedicated AVD; never overwrite historical reports."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
from verify_android_input_host import CHECKS, STORAGE_CHECKS, OWNER_CHECKS

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.notecore.preview'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--api', type=int, choices=(26, 32), required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    serial, avd = ('emulator-5582', 'NoteCoreMin26') if args.api == 26 else ('emulator-5586', 'NoteCorePath32')
    out = ROOT / args.output
    out.mkdir(parents=True, exist_ok=False)

    def adb(*command):
        return subprocess.run([args.adb, '-s', serial, *command], cwd=ROOT,
                              capture_output=True, check=True, timeout=180).stdout

    property_name = 'ro.kernel.qemu.avd_name' if args.api == 26 else 'ro.boot.qemu.avd_name'
    assert adb('shell', 'getprop', property_name).decode().strip() == avd
    assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == str(args.api).encode()
    paths = set()
    for module in ('canvas-core', 'shared-core'):
        for name in ('commonMain', 'jvmAndAndroidMain', 'androidMain'):
            paths.update((ROOT / module / 'src' / name).rglob('*.kt'))
    for name in ('main', 'androidTest'):
        paths.update(p for p in (ROOT / 'android-app/src' / name).rglob('*') if p.is_file())
    paths.update(ROOT / p for p in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'canvas-core/build.gradle.kts', 'shared-core/build.gradle.kts', 'android-app/build.gradle.kts',
        'tools/verify_android_fallback_current.py', 'tools/verify_android_input_host.py', 'fixtures/transfer/android-paged-v2.ncp'))
    before_source = {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in sorted(paths)}
    with (out / 'build.log').open('w', encoding='utf-8', newline='\n') as log:
        subprocess.run([str(ROOT / 'gradlew.bat'), '-PenableAndroidTargets=true', ':android-app:assembleDebug',
            ':android-app:assembleDebugAndroidTest', '--console=plain'], cwd=ROOT,
            check=True, timeout=600, stdout=log, stderr=subprocess.STDOUT)

    def notes():
        data = adb('exec-out', 'run-as', PACKAGE, 'tar', 'cf', '-', 'files/preview-notes')
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            return {p.name: sha(archive.extractfile(p).read()) for p in archive.getmembers() if p.isfile()}

    installed = adb('shell', 'pm', 'list', 'packages', PACKAGE).decode().splitlines()
    previous_notes = None
    if 'package:' + PACKAGE in installed:
        previous_notes = notes()
    apks = [('android-app/build/outputs/apk/debug/android-app-debug.apk', PACKAGE),
            ('android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk', PACKAGE + '.test')]
    apk_hashes = {}
    for path, package in apks:
        apk = ROOT / path
        (out / (package + '-install.log')).write_bytes(adb('install', '-r', str(apk)))
        remote = adb('shell', 'pm', 'path', package).decode().strip().removeprefix('package:')
        assert re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk', remote)
        apk_hashes[package] = sha(apk.read_bytes())
        assert sha(adb('exec-out', 'cat', remote)) == apk_hashes[package]
    adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files/preview-notes')
    if previous_notes is not None:
        assert notes() == previous_notes
    fixture = ROOT / 'fixtures/transfer/android-paged-v2.ncp'
    identity = fixture.read_text(encoding='utf-8').splitlines()[1].split('\t')[1]
    target = 'files/preview-notes/' + identity + '.ncp'
    before_seed = notes()
    if target not in before_seed:
        remote = '/data/local/tmp/notecore-fallback-' + str(args.api) + '.ncp'
        adb('push', str(fixture), remote)
        adb('shell', 'run-as', PACKAGE, 'cp', remote, target)
    assert sha(adb('exec-out', 'run-as', PACKAGE, 'cat', target)) == sha(fixture.read_bytes())
    before = notes()
    (out / 'notes-before.json').write_text(json.dumps(before, indent=2) + '\n', encoding='utf-8', newline='\n')
    observed = {}
    for case, prefix, expected in [('input_batches', 'input', CHECKS), ('storage_retention', 'storage', STORAGE_CHECKS),
                                   ('owner_retirement', 'owner', OWNER_CHECKS), ('minimum_api', 'minimum_api', None)]:
        raw = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', case,
                  PACKAGE + '.test/dev.notecore.app.ViewportInstrumentation')
        (out / (case + '.log')).write_bytes(raw)
        text = raw.decode('utf-8')
        assert prefix + '_status=passed' in text and 'INSTRUMENTATION_CODE: -1' in text, text
        result = json.loads(re.search(prefix + r'_observations=(\{.*\})', text).group(1))
        if expected is not None:
            assert len(result['checks']) == len(expected) and set(result['checks']) == expected
        else:
            assert result['api'] == args.api and result['original_view_store']
            assert result['line_dot_undo_redo_page_pixels'] and result['surface_render_bytes'] == 0
        observed[case] = result
        assert notes() == before
    adb('shell', 'am', 'force-stop', PACKAGE)
    launch = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/dev.notecore.app.MainActivity')
    (out / 'ordinary-launch.log').write_bytes(launch)
    assert b'Status: ok' in launch
    assert re.fullmatch(r'\d+', adb('shell', 'pidof', PACKAGE).decode().strip())
    after = notes()
    assert after == before
    (out / 'ordinary-screen.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    assert before_source == {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in sorted(paths)}
    report = {'status': 'passed', 'api': args.api, 'avd': avd, 'serial': serial,
        'fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint').decode().strip(),
        'source_sha256': before_source, 'installed_apk_hashes_verified': apk_hashes,
        'previous_notes': previous_notes, 'seed_source': 'fixtures/transfer/android-paged-v2.ncp',
        'notes_before': before, 'notes_after': after, 'observations': observed,
        'evidence_sha256': {p.name: sha(p.read_bytes()) for p in out.iterdir()},
        'scope': 'Current original View/Store low-API Path pixels, callback input, isolated real save/owner suites and ordinary process launch. Synthetic seeded file protection, no ordinary gesture journey, hardware Canvas or physical performance claim.',
        'physical_verified': False, 'c1_complete': False, 'requirement_accepted': False}
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': 'passed', 'api': args.api, 'notes_preserved': len(before),
                      'checks': {k: len(v.get('checks', [])) for k, v in observed.items()}}))


if __name__ == '__main__':
    main()
