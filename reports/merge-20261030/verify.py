"""Assemble this integration receipt from actual current results; no device input or rebuild."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from verification_hash import verification_digest

OUT = Path(__file__).resolve().parent
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
ADB = '/Users/lixiaofan/Library/Android/sdk/platform-tools/adb'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args):
    return subprocess.check_output([str(a) for a in args], cwd=ROOT, timeout=120)


def main():
    reports = {name: load(ROOT/'reports'/(name+'.json')) for name in
               ('verification', 'preview-build', 'android-input-host-tests', 'ios-preview-build', 'ios-host-tests', 'ios-preview-restored')}
    verification = reports['verification']
    assert verification['status'] == 'passed'
    for path, digest in verification['verification_input_sha256'].items():
        assert verification_digest(ROOT/path) == digest, path
    for name, fields in [('preview-build', ['source_and_build_input_sha256']),
                         ('android-input-host-tests', ['source_and_artifact_sha256']),
                         ('ios-preview-build', ['source_and_build_input_sha256']),
                         ('ios-host-tests', ['source_and_framework_sha256', 'test_build_sha256'])]:
        assert reports[name]['status'] == 'passed'
        for field in fields:
            for path, digest in reports[name][field].items():
                assert sha(ROOT/path) == digest, (name, path)
    assert verification['jvm_contract_tests'] == {'tests': 584, 'failures': 0, 'errors': 0, 'skipped': 0}
    host = reports['ios-host-tests']
    assert host['test_count'] == 66 and host['test_summary']['failedTests'] == host['test_summary']['skippedTests'] == 0
    android = reports['android-input-host-tests']
    assert android['apk_sha256'] == reports['preview-build']['android']['sha256']
    totals = {}
    xml_paths = []
    for module, target, expected in [('canvas-core', 'jvmTest', 234), ('shared-core', 'jvmTest', 350),
                                     ('canvas-core', 'iosSimulatorArm64Test', 211), ('shared-core', 'iosSimulatorArm64Test', 331),
                                     ('android-app', 'testDebugUnitTest', 35)]:
        paths = sorted((ROOT/module/'build/test-results'/target).glob('TEST-*.xml'))
        sums = {key: sum(int(ET.parse(p).getroot().get(key, 0)) for p in paths) for key in ('tests', 'failures', 'errors', 'skipped')}
        assert sums == {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}
        totals[module+'/'+target] = sums
        for path in paths:
            retained = OUT/'xml'/module/target/path.name
            retained.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(path, retained); xml_paths.append(path)
    before = load(OUT/'original-files-before.json')
    raw = command([ADB, '-s', 'emulator-5554', 'shell', "run-as dev.notecore.preview sh -c 'sha256sum files/preview-notes/*.ncp'"])
    android_files = {Path(line.split(None, 1)[1]).name: line.split(None, 1)[0] for line in raw.decode().splitlines()}
    data = Path(command(['xcrun', 'simctl', 'get_app_container', DEVICE, 'dev.notecore.preview', 'data']).decode().strip())
    apple_files = {p.name: sha(p) for p in (data/'Documents/PreviewNotes').glob('*.ncp')}
    after = {'android': android_files, 'apple': apple_files}
    assert after == before and len(android_files) == 66 and len(apple_files) == 16
    (OUT/'original-files-after.json').write_text(json.dumps(after, indent=2)+'\n', encoding='utf-8', newline='\n')
    installed = {}
    for package, expected in [('dev.notecore.preview', reports['preview-build']['android']['sha256']),
                              ('dev.notecore.document.poc.test', load(OUT/'android-additional.json')['model']['apk_sha256'])]:
        remote = command([ADB, '-s', 'emulator-5554', 'shell', 'pm', 'path', package]).decode().strip().removeprefix('package:')
        assert re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~:-]+/base.apk', remote)
        digest = hashlib.sha256(command([ADB, '-s', 'emulator-5554', 'exec-out', 'cat', remote])).hexdigest()
        assert digest == expected; installed[package] = digest
    app = Path(command(['xcrun', 'simctl', 'get_app_container', DEVICE, 'dev.notecore.preview', 'app']).decode().strip())
    payload = {p.relative_to(app).as_posix(): sha(p) for p in app.rglob('*') if p.is_file()}
    assert payload == reports['ios-preview-restored']['installed_payload_sha256']
    assert not any('.xctest/' in p for p in payload)
    apple_pid = int((ROOT/'build/merge-20261030/apple-launch.log').read_text(encoding='utf-8').strip().split(': ')[1])
    jobs = command(['xcrun', 'simctl', 'spawn', DEVICE, 'launchctl', 'list']).decode()
    assert any(line.split()[0] == str(apple_pid) and 'UIKitApplication:dev.notecore.preview[' in line for line in jobs.splitlines())
    android_pid = command([ADB, '-s', 'emulator-5554', 'shell', 'pidof', 'dev.notecore.preview']).decode().strip()
    assert android_pid.isdigit()
    for label in ('gradle', 'refresh', 'bridge-fix'):
        assert 'BUILD SUCCESSFUL' in (ROOT/'build/merge-20261030'/(label+'.log')).read_text(encoding='utf-8')
    for path in (ROOT/'build/merge-20261030').glob('*.log'):
        if path.name != 'emulator.log': shutil.copyfile(path, OUT/path.name)
    shutil.copyfile(ROOT/'ios-app/build/host-tests/test.log', OUT/'apple-xctest.log')
    for suffix in ('summary', 'tests'):
        (OUT/('apple-xctest-'+suffix+'.json')).write_bytes(command(['xcrun', 'xcresulttool', 'get', 'test-results', suffix,
                                                                  '--path', ROOT/host['xcresult'], '--format', 'json']))
    additional = load(OUT/'android-additional.json')
    assert sum(additional['model']['suites'].values()) == 50
    for name in ('verification', 'preview-build', 'android-input-host-tests', 'ios-preview-build', 'ios-host-tests', 'ios-preview-restored'):
        shutil.copyfile(ROOT/'reports'/(name+'.json'), OUT/(name+'.json'))
    source_paths = [ROOT/p for p in ('shared-core/build.gradle.kts', 'tools/verify_ios_host.py', 'tools/verify_apple_pdf_metadata.py', 'tools/verify_apple_pdf_ci.py',
                                     'ios-app/NoteCorePreview/NoteCorePreviewApp.swift', 'ios-app/NoteCorePreviewTests/PagedHostTests.swift',
                                     'android-app/src/androidTest/java/dev/notecore/app/ViewportInstrumentation.kt')]
    result = {'schema': 'parallel-merge-20261030-1', 'status': 'passed',
              'parents': ['4faad67e9fc2d480464ca06dc5ce009ecf9ce902', '32c5b27'],
              'scope': 'Merged original common models, window/input ownership, cancellation feedback and platform consumers; no new ordinary GUI journey or production SQLite.',
              'actual_counterexample': 'Both platform flags plus Android instrumented-test dependsOn disabled shared automatic hierarchy; framework omitted ApplePreviewInput and actual Swift compile failed. Explicit iosMain edges restore the original bridge.',
              'test_totals': totals, 'swift_host_tests': 66, 'android_model_art_tests': 50,
              'android_original_host_report': 'reports/android-input-host-tests.json',
              'android_additional_cases': ['image_budget', 'image_ownership'],
              'preservation': {'android_originals': 66, 'apple_originals': 16, 'all_original_hashes_unchanged': True,
                               'apple_exact_ordinary_payload_no_xctest': True},
              'installed_android_sha256': installed, 'apple_ordinary_zip_sha256': reports['ios-preview-build']['sha256'],
              'final_ordinary_pids': {'android': int(android_pid), 'apple': apple_pid},
              'limitations': ['No inherited GUI/Files success for these new artifacts; old reports retain their own package scopes.',
                             'Independent remote Apple sketch CI failed and remains failed; this merge host pass is a different target.',
                             'Physical M-1/heap/stylus, unified peak admission, complete C1 and DEV-041/production SQLite remain pending; all 129 requirement statuses unchanged.'],
              'c1_gate_complete': False, 'whole_requirement_accepted': False,
              'source_and_evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in [*source_paths, *OUT.rglob('*')] if p.is_file()}}
    (ROOT/'reports/parallel-merge-20261030.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: result[k] for k in ('status', 'test_totals', 'swift_host_tests', 'android_model_art_tests', 'final_ordinary_pids')}))


if __name__ == '__main__':
    main()
