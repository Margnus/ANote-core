"""Independent ENG19 UIKit consumer on a fresh simulator; no Preview App sources or storage."""
import hashlib
import json
import os
from pathlib import Path
import platform
import plistlib
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/apple-sketch'
SAMPLE = ROOT / 'samples/apple-sketch'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    paths = set(SAMPLE.rglob('*.swift')) | set(SAMPLE.rglob('*.pbxproj')) | set(SAMPLE.rglob('*.xcscheme'))
    for module in ('canvas-core', 'shared-core'):
        for source_set in ('commonMain', 'iosMain'):
            paths.update((ROOT/module/'src'/source_set).rglob('*.kt'))
    paths.update(ROOT/p for p in ('ci/verify_apple_sketch.py', '.github/workflows/apple-sketch.yml',
        'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'canvas-core/build.gradle.kts',
        'shared-core/build.gradle.kts', 'gradlew', 'gradle/wrapper/gradle-wrapper.properties',
        'gradle/wrapper/gradle-wrapper.jar'))
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(paths)}


def cases(nodes):
    for node in nodes:
        if node.get('nodeType') == 'Test Case':
            yield node
        yield from cases(node.get('children', []))


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert platform.system() == 'Darwin' and platform.machine() == 'arm64'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    before = inputs()
    report = {'status': 'running', 'stage': 'environment', 'source_sha256': before,
              'physical_device_verified': False, 'c1_complete': False, 'goal_complete': False,
              'scope': 'Independent UIKit memory sketch, original Notebook/framework; no Preview App or production storage'}

    def capture(*args, timeout=120):
        return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding='utf-8',
                              check=True, timeout=timeout).stdout

    def run(label, args, timeout):
        print(label, flush=True)
        with (OUTPUT/(label+'.log')).open('w', encoding='utf-8', newline='\n') as log:
            subprocess.run(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=timeout)

    try:
        report.update(commit=capture('git', 'rev-parse', 'HEAD').strip(), run_id=os.environ['GITHUB_RUN_ID'],
                      xcode=capture('xcodebuild', '-version').strip(), os=capture('sw_vers').strip())
        assert report['commit'] == os.environ['GITHUB_SHA']
        project = SAMPLE/'NoteCoreSketch.xcodeproj/project.pbxproj'
        objects = plistlib.loads(project.read_bytes())['objects']
        references = [v['path'] for v in objects.values() if v['isa'] == 'PBXFileReference']
        assert sorted(p for p in references if p.endswith('.swift')) == sorted([
            'SketchApp.swift', 'SketchCanvas.swift', 'SketchHostTests.swift', 'NoteCoreSketchUITests/SketchJourneyUITests.swift'])
        assert not any('ios-app' in p or 'NoteCorePreview' in p for p in references)
        report['independent_source_references'] = references
        report['stage'] = 'framework'
        run('framework', ['./gradlew', '--no-daemon', '--console=plain', '-PenableAppleTargets=true',
            ':shared-core:linkDebugFrameworkIosSimulatorArm64'], 1500)
        framework = ROOT/'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework/NoteCanvasPrototype'
        report['framework_sha256'] = sha(framework)
        devices = json.loads(capture('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))['devices']
        choices = [(rt, d) for rt, group in devices.items() if '.iOS-' in rt for d in group
                   if d.get('isAvailable') and d.get('deviceTypeIdentifier')]
        runtime, template = next((pair for pair in choices if 'iPad' in pair[1]['name']), choices[0])
        device = capture('xcrun', 'simctl', 'create', 'NoteCoreSketch-'+uuid.uuid4().hex[:12],
                         template['deviceTypeIdentifier'], runtime).strip()
        report['device'] = {'id': device, 'runtime': runtime, 'type': template['deviceTypeIdentifier']}
        capture('xcrun', 'simctl', 'boot', device)
        run('boot', ['xcrun', 'simctl', 'bootstatus', device, '-b'], 240)
        for label, target, source in (
            ('host', 'NoteCoreSketchTests', SAMPLE/'NoteCoreSketchTests/SketchHostTests.swift'),
            ('ui', 'NoteCoreSketchUITests', SAMPLE/'NoteCoreSketchUITests/SketchJourneyUITests.swift')):
            report['stage'] = label
            result = OUTPUT/(label+'.xcresult')
            execution_failure = None
            try:
                run(label, ['xcodebuild', '-quiet', '-project', str(project.parent), '-scheme', 'NoteCoreSketch',
                '-sdk', 'iphonesimulator', '-destination', 'platform=iOS Simulator,id='+device,
                '-derivedDataPath', str(OUTPUT/'xcode'), 'CODE_SIGNING_ALLOWED=NO', '-resultBundlePath', str(result),
                '-collect-test-diagnostics', 'never', '-parallel-testing-enabled', 'NO', '-only-testing:'+target, 'test'], 900)
            except subprocess.CalledProcessError as failure:
                execution_failure = failure
                report[label+'_execution_failure'] = repr(failure)
            # A failing XCTest still has an authoritative result bundle. Keep its assertions before refusing.
            summary = json.loads(capture('xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(result), '--format', 'json'))
            tree = json.loads(capture('xcrun', 'xcresulttool', 'get', 'test-results', 'tests', '--path', str(result), '--format', 'json'))
            for name, value in (('summary', summary), ('tests', tree)):
                (OUTPUT/(label+'-'+name+'.json')).write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8', newline='\n')
            expected = {name+'()' for name in re.findall(r'func\s+(test\w+)\(', source.read_text(encoding='utf-8'))}
            actual = list(cases(tree['testNodes']))
            report[label] = {'tests': len(actual), 'checks': sorted(c['name'] for c in actual), 'summary': summary}
            if execution_failure is not None:
                raise execution_failure
            assert {c['name'] for c in actual} == expected and all(c['result'] == 'Passed' for c in actual)
            assert summary['result'] == 'Passed' and summary['totalTestCount'] == len(expected)
            assert summary['failedTests'] == summary['skippedTests'] == summary['expectedFailures'] == 0
            assert any(d['device']['deviceId'] == device for d in summary['devicesAndConfigurations'])
            report[label] = {'tests': len(expected), 'checks': sorted(expected), 'summary': summary}
        capture('xcrun', 'xcresulttool', 'export', 'attachments', '--path', str(OUTPUT/'ui.xcresult'),
                '--output-path', str(OUTPUT/'attachments'))
        assert list((OUTPUT/'attachments').rglob('*.png'))
        app = OUTPUT/'xcode/Build/Products/Debug-iphonesimulator/NoteCoreSketch.app'
        report['app_binary_sha256'] = sha(app/'NoteCoreSketch')
        symbols = capture('nm', str(app/'NoteCoreSketch'))
        assert 'PreviewModel' not in symbols and 'PreviewFileIO' not in symbols
        report['preview_app_symbols_absent'] = True
        container = Path(capture('xcrun', 'simctl', 'get_app_container', device, 'dev.notecore.sample.sketch.ios', 'data').strip())
        report['documents_files'] = [str(p.relative_to(container)) for p in (container/'Documents').rglob('*') if p.is_file()]
        assert report['documents_files'] == []
        assert inputs() == before
        report.update(status='passed', stage='complete')
    except Exception as failure:
        report.update(status='failed', failure=repr(failure))
        raise
    finally:
        report['evidence_sha256'] = {p.relative_to(OUTPUT).as_posix(): sha(p) for p in OUTPUT.rglob('*')
            if p.is_file() and (p.suffix in ('.json', '.log', '.png')) and p.name != 'report.json'
            and not any(part in ('xcode', 'host.xcresult', 'ui.xcresult') for part in p.relative_to(OUTPUT).parts)}
        (OUTPUT/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
