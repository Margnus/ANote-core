#!/usr/bin/env python3
"""Current-source Apple CI on an isolated simulator; UI scope is separate from host/physical evidence."""
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/apple-candidate'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    paths = set()
    for module in ('canvas-core', 'shared-core'):
        for source_set in ('commonMain', 'commonTest', 'iosMain', 'iosSimulatorArm64Test'):
            paths.update((ROOT / module / 'src' / source_set).rglob('*.kt'))
    paths.update((ROOT / 'ios-app').rglob('*.swift'))
    paths.update((ROOT / 'fixtures').rglob('*.json'))
    paths.update((ROOT / 'fixtures/transfer').glob('*.ncp'))
    paths.update(ROOT / p for p in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'canvas-core/build.gradle.kts', 'shared-core/build.gradle.kts',
        'ios-app/NoteCorePreview.xcodeproj/project.pbxproj',
        'ios-app/NoteCorePreview.xcodeproj/xcshareddata/xcschemes/NoteCorePreview.xcscheme',
        'gradlew', 'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
        '.github/workflows/apple-candidate.yml', 'ci/verify_apple_candidate.py', 'tools/verify_ios_host.py'))
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(paths)}


def expected_native(module):
    expected = set()
    for source_set in ('commonTest', 'iosSimulatorArm64Test'):
        for path in (ROOT / module / 'src' / source_set).rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            methods = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', text)
            if not methods:
                continue
            classes = re.findall(r'^class (\w+Test)\b', text, re.MULTILINE)
            package = re.search(r'^package (\S+)', text, re.MULTILINE)
            assert len(classes) == 1 and package and '@Ignore' not in text, path
            expected.update(package.group(1) + '.' + classes[0] + '.' + method for method in methods)
    assert expected
    return expected


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Only the isolated hosted CI environment is intended'
    assert platform.system() == 'Darwin' and platform.machine() == 'arm64'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {'status': 'running', 'stage': 'environment',
              'scope': 'New GitHub-hosted arm64 Mac and isolated simulator, current-source Native and app-host tests. No original Mac/AVD evidence inherited.',
              'ordinary_ui_verified': False, 'physical_device_verified': False,
              'c1_complete': False, 'goal_complete': False, 'native': {}}

    def capture(*command, timeout=120):
        return subprocess.run(command, cwd=ROOT, capture_output=True, check=True, text=True,
                              encoding='utf-8', timeout=timeout).stdout

    def run(label, command, timeout):
        path = OUTPUT / (label + '.log')
        print(label, flush=True)
        with path.open('w', encoding='utf-8', newline='\n') as log:
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                           timeout=timeout, check=True)
        return path.read_text(encoding='utf-8')

    before = inputs()
    try:
        report.update(commit=capture('git', 'rev-parse', 'HEAD').strip(), run_id=os.environ['GITHUB_RUN_ID'],
                      xcode=capture('xcodebuild', '-version').strip(), os=capture('sw_vers').strip(),
                      source_and_build_input_sha256=before)
        assert report['commit'] == os.environ['GITHUB_SHA']
        os.environ['GRADLE_USER_HOME'] = str(ROOT / '.gradle-user-home')
        os.environ['KONAN_DATA_DIR'] = str(ROOT / '.konan')
        report['stage'] = 'kotlin_native_build'
        run('native-build', ['bash', str(ROOT / 'gradlew'), '--no-daemon', '--console=plain',
            '-PenableAppleTargets=true', ':canvas-core:linkDebugTestIosSimulatorArm64',
            ':shared-core:linkDebugTestIosSimulatorArm64', ':shared-core:linkDebugFrameworkIosSimulatorArm64'], 1500)
        framework = ROOT / 'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework/NoteCanvasPrototype'
        report['framework_sha256'] = sha(framework)
        report['stage'] = 'isolated_simulator'
        devices = json.loads(capture('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))['devices']
        choices = [(runtime, d) for runtime, group in devices.items() if '.iOS-' in runtime
                   for d in group if d.get('isAvailable') and d.get('deviceTypeIdentifier')]
        assert choices, 'No installed available iOS runtime/device combination'
        runtime, template = next(((rt, d) for rt, d in choices if 'iPad' in d['name']), choices[0])
        name = 'NoteCoreCI-' + uuid.uuid4().hex[:12]
        device = capture('xcrun', 'simctl', 'create', name, template['deviceTypeIdentifier'], runtime).strip()
        report['device'] = {'udid': device, 'name': name, 'runtime': runtime, 'type': template['deviceTypeIdentifier']}
        capture('xcrun', 'simctl', 'boot', device)
        run('simulator-boot', ['xcrun', 'simctl', 'bootstatus', device, '-b'], 240)
        for module in ('canvas-core', 'shared-core'):
            report['stage'] = module + '_native_tests'
            binary = ROOT / module / 'build/bin/iosSimulatorArm64/debugTest/test.kexe'
            expected = expected_native(module)
            text = run(module + '-native', ['xcrun', 'simctl', 'spawn', device, str(binary), '--ktest_logger=GTEST'], 240)
            passed = re.findall(r'^\[\s+OK\s+\]\s+(\S+)', text, re.MULTILINE)
            assert len(passed) == len(expected) and set(passed) == expected, (module, len(passed), len(expected))
            assert f'[  PASSED  ] {len(expected)} tests.' in text
            report['native'][module] = {'tests': len(passed), 'checks': sorted(expected), 'binary_sha256': sha(binary)}
        report['stage'] = 'swift_app_host_tests'
        result = OUTPUT / 'host.xcresult'
        run('app-host', ['xcodebuild', '-quiet', '-project', 'ios-app/NoteCorePreview.xcodeproj',
            '-scheme', 'NoteCorePreview', '-sdk', 'iphonesimulator', '-destination', 'platform=iOS Simulator,id=' + device,
            '-derivedDataPath', str(OUTPUT / 'xcode'), 'CODE_SIGNING_ALLOWED=NO',
            '-resultBundlePath', str(result), '-collect-test-diagnostics', 'never',
            '-only-testing:NoteCorePreviewTests', '-parallel-testing-enabled', 'NO', 'test'], 900)
        summary = json.loads(capture('xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(result), '--format', 'json'))
        tree = json.loads(capture('xcrun', 'xcresulttool', 'get', 'test-results', 'tests', '--path', str(result), '--format', 'json'))
        (OUTPUT / 'xctest-summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8', newline='\n')
        (OUTPUT / 'xctest-tests.json').write_text(json.dumps(tree, indent=2) + '\n', encoding='utf-8', newline='\n')
        def cases(nodes):
            for node in nodes:
                if node.get('nodeType') == 'Test Case':
                    yield node
                yield from cases(node.get('children', []))
        actual = list(cases(tree['testNodes']))
        sys.path.insert(0, str(ROOT / 'tools'))
        from verify_ios_host import EXPECTED
        assert {node['name'] for node in actual} == EXPECTED and all(node['result'] == 'Passed' for node in actual)
        assert summary['result'] == 'Passed' and summary['totalTestCount'] == len(EXPECTED)
        assert summary['failedTests'] == summary['skippedTests'] == summary['expectedFailures'] == 0
        assert any(d['device']['deviceId'] == device for d in summary['devicesAndConfigurations'])
        report['app_host'] = {'tests': len(actual), 'checks': sorted(EXPECTED), 'summary': summary}
        report['stage'] = 'ordinary_app_ui_tests'
        container = Path(capture('xcrun', 'simctl', 'get_app_container', device, 'dev.notecore.preview', 'data').strip())
        notes = container / 'Documents/PreviewNotes'
        originals = {p.name: sha(p) for p in notes.glob('*.ncp')}
        ui_result = OUTPUT / 'ui.xcresult'
        run('ordinary-ui', ['xcodebuild', '-quiet', '-project', 'ios-app/NoteCorePreview.xcodeproj',
            '-scheme', 'NoteCorePreview', '-sdk', 'iphonesimulator', '-destination', 'platform=iOS Simulator,id=' + device,
            '-derivedDataPath', str(OUTPUT / 'xcode'), 'CODE_SIGNING_ALLOWED=NO',
            '-resultBundlePath', str(ui_result), '-collect-test-diagnostics', 'never',
            '-only-testing:NoteCorePreviewUITests', '-parallel-testing-enabled', 'NO', 'test'], 900)
        ui_summary = json.loads(capture('xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(ui_result), '--format', 'json'))
        ui_tree = json.loads(capture('xcrun', 'xcresulttool', 'get', 'test-results', 'tests', '--path', str(ui_result), '--format', 'json'))
        (OUTPUT / 'ui-summary.json').write_text(json.dumps(ui_summary, indent=2) + '\n', encoding='utf-8', newline='\n')
        (OUTPUT / 'ui-tests.json').write_text(json.dumps(ui_tree, indent=2) + '\n', encoding='utf-8', newline='\n')
        ui_cases = list(cases(ui_tree['testNodes']))
        ui_expected = {'testTwoPageInkHistoryBackgroundSaveAndProcessReopen()'}
        assert {c['name'] for c in ui_cases} == ui_expected and all(c['result'] == 'Passed' for c in ui_cases)
        assert ui_summary['result'] == 'Passed' and ui_summary['totalTestCount'] == 1
        assert ui_summary['failedTests'] == ui_summary['skippedTests'] == ui_summary['expectedFailures'] == 0
        assert any(d['device']['deviceId'] == device for d in ui_summary['devicesAndConfigurations'])
        capture('xcrun', 'xcresulttool', 'export', 'attachments', '--path', str(ui_result), '--output-path', str(OUTPUT / 'ui-attachments'))
        assert list((OUTPUT / 'ui-attachments').rglob('*.png')), 'No UI screenshots retained'
        # Re-resolve the installed container rather than assuming its path survived launch.
        container = Path(capture('xcrun', 'simctl', 'get_app_container', device, 'dev.notecore.preview', 'data').strip())
        notes = container / 'Documents/PreviewNotes'
        after = {p.name: sha(p) for p in notes.glob('*.ncp')}
        assert all(after.get(name) == digest for name, digest in originals.items()), 'Original files changed'
        added = set(after) - set(originals)
        assert len(added) == 1, added
        actual_file = notes / next(iter(added))
        lines = [line.split('\t') for line in actual_file.read_text(encoding='utf-8').splitlines()]
        assert lines[0] == ['NCPREVIEW', '2'] and lines[1][0] == 'D' and lines[1][2].startswith('AppleUI-')
        pages = [line for line in lines if line[0] == 'G']
        strokes = [line for line in lines if line[0] == 'S']
        assert len(pages) == 2 and len(strokes) == 3
        assert [sum(s[2] == page[2] for s in strokes) for page in pages] == [2, 1]
        assert all(int(s[8]) > 1 for s in strokes), 'No real dragged sample sequences'
        (OUTPUT / 'ordinary-ui-note.ncp').write_bytes(actual_file.read_bytes())
        report['ordinary_ui'] = {'tests': 1, 'checks': sorted(ui_expected), 'summary': ui_summary,
            'original_files_sha256': originals, 'final_files_sha256': after,
            'saved_note_sha256': sha(actual_file), 'pages': 2, 'strokes_per_page': [2, 1],
            'scope': 'Separate XCUITest process, ordinary App UI, simulated finger input; saved-write process reopen only. No Pencil, in-flight IO kill or physical performance claim.'}
        report['ordinary_ui_verified'] = True
        assert before == inputs(), 'Inputs changed during candidate verification'
        assert sha(framework) == report['framework_sha256']
        report.update(status='passed', stage='complete')
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        report['evidence_sha256'] = {p.relative_to(ROOT).as_posix(): sha(p)
                                     for p in OUTPUT.rglob('*') if p.is_file() and p.name != 'report.json'
                                     and not any(part in ('xcode', 'host.xcresult', 'ui.xcresult') for part in p.relative_to(OUTPUT).parts)}
        (OUTPUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
