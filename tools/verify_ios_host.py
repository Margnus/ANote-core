#!/usr/bin/env python3
"""Run source-bound iOS app-host API/file/render tests, independently of desktop UI automation."""
import argparse
import hashlib
import json
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT/'ios-app/build/host-tests'
EXPECTED = {
    'testSharedBrushContoursMatchAnalyticCoverageOffscreen()',
    'testActualAndroidFileImportsFreshIdsAndExportsAllPages()',
    'testFailedSaveRetainsRootAcrossCloseAndRepair()',
    'testLateImportCannotReplaceNewActiveOwner()',
    'testLateOpenCannotReplaceNewActiveOwner()',
    'testLegacyNoEditPreservesBytesThenEditMigratesOriginalExtent()',
    'testNativeRendererChangesPageCacheAndAppliesFullAffineDotWidth()',
    'testPagedSaveCloseAndNewOwnerReadAllPages()',
    'testUnsupportedBrushAndCorruptUTF8DoNotCreateCopyOrChangeSource()',
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    files = [*ROOT.glob('ios-app/NoteCorePreview/*.swift'), *ROOT.glob('ios-app/NoteCorePreviewTests/*.swift'),
             *ROOT.glob('shared-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/commonMain/**/*.kt'),
             ROOT/'ios-app/NoteCorePreview.xcodeproj/project.pbxproj',
             ROOT/'ios-app/NoteCorePreview.xcodeproj/xcshareddata/xcschemes/NoteCorePreview.xcscheme',
             ROOT/'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework/NoteCanvasPrototype',
             ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'gradle.properties',
             ROOT/'canvas-core/build.gradle.kts', ROOT/'shared-core/build.gradle.kts',
             ROOT/'fixtures/transfer/android-paged-v2.ncp', ROOT/'fixtures/transfer/android-paged-v2.json',
             ROOT/'fixtures/geometry/brush-paths.json', Path(__file__)]
    return {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(files)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    args = parser.parse_args()

    def command(*args):
        return subprocess.run(args, check=True, capture_output=True, text=True, cwd=ROOT, timeout=120).stdout

    devices = json.loads(command('xcrun', 'simctl', 'list', 'devices', '-j'))['devices']
    device = next(d for group in devices.values() for d in group if d['udid'] == args.device)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted' and device['isAvailable']
    fixture = ROOT/'fixtures/transfer/android-paged-v2.ncp'
    provenance = json.loads(fixture.with_suffix('.json').read_text(encoding='utf-8'))
    assert digest(fixture) == provenance['sha256'] and provenance['source_real_user_data'] is False
    before = inputs()
    # Xcode does not rebuild Kotlin itself. Require a successful package build binding this framework
    # to the complete current common source set; hashing stale framework + new source is insufficient.
    built = json.loads((ROOT/'reports/ios-preview-build.json').read_text(encoding='utf-8'))['source_and_build_input_sha256']
    def framework_input(path):
        return '/src/commonMain/' in path or path.endswith('.kts') or path == 'gradle.properties' or path.endswith('.framework/NoteCanvasPrototype')
    expected_framework = {path: sha for path, sha in before.items() if framework_input(path)}
    recorded_framework = {path: sha for path, sha in built.items() if framework_input(path)}
    assert expected_framework == recorded_framework, 'Kotlin framework/source binding is stale; run package-ios-preview.sh first'
    BUILD.mkdir(parents=True, exist_ok=True)
    result = BUILD/('run-' + uuid.uuid4().hex + '.xcresult')
    log = BUILD/'test.log'
    with log.open('w') as output:
        run = subprocess.run(['xcodebuild', '-quiet', '-project', 'ios-app/NoteCorePreview.xcodeproj',
            '-scheme', 'NoteCorePreview', '-sdk', 'iphonesimulator', '-destination', 'platform=iOS Simulator,id='+args.device,
            '-derivedDataPath', str(BUILD), 'CODE_SIGNING_ALLOWED=NO', '-resultBundlePath', str(result), 'test'],
            cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=600)
    if run.returncode:
        raise RuntimeError(log.read_text(encoding='utf-8')[-16000:])
    assert inputs() == before, 'Source changed during native tests'
    summary = json.loads(command('xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(result), '--format', 'json'))
    tree = json.loads(command('xcrun', 'xcresulttool', 'get', 'test-results', 'tests', '--path', str(result), '--format', 'json'))

    def cases(nodes):
        for node in nodes:
            if node.get('nodeType') == 'Test Case':
                yield node
            yield from cases(node.get('children', []))

    actual = list(cases(tree['testNodes']))
    assert {node['name'] for node in actual} == EXPECTED and all(node['result'] == 'Passed' for node in actual)
    assert summary['result'] == 'Passed' and summary['totalTestCount'] == len(EXPECTED)
    assert summary['failedTests'] == summary['skippedTests'] == summary['expectedFailures'] == 0
    assert any(d['device']['deviceId'] == args.device for d in summary['devicesAndConfigurations'])
    host = BUILD/'Build/Products/Debug-iphonesimulator/NoteCorePreview.app'
    executable = host/'NoteCorePreview'
    plugin = host/'PlugIns/NoteCorePreviewTests.xctest/NoteCorePreviewTests'
    assert digest(host/'PlugIns/NoteCorePreviewTests.xctest/brush-paths.json') == digest(ROOT/'fixtures/geometry/brush-paths.json')
    report = {
        'status': 'passed', 'platform': 'iOS Simulator ARM64 app-host XCTest',
        'device': {k: device[k] for k in ('name', 'udid', 'state')}, 'test_count': len(actual),
        'checks': sorted(EXPECTED), 'test_summary': summary,
        'xcresult': result.relative_to(ROOT).as_posix(), 'source_and_framework_sha256': before,
        'framework_verified_build_inputs': expected_framework,
        'test_build_sha256': {p.relative_to(ROOT).as_posix(): digest(p) for p in (executable, host/'NoteCorePreview.debug.dylib', plugin)},
        'android_fixture_sha256': digest(fixture), 'android_fixture_provenance': provenance,
        'native_model_file_reopen_verified': True, 'cross_platform_fixture_import_verified': True,
        'native_offscreen_render_pixels_verified': True,
        'shared_brush_contour_offscreen_verified': True,
        'brush_fixture_execution_scope': 'JVM-generated common contour output consumed by real iOS CoreGraphics; not Kotlin/Native runtime generation, AA edge equivalence or app integration',
        'desktop_ui_interaction_verified': False, 'real_touch_input_verified': False,
        'system_file_picker_verified': False, 'process_restart_verified': False,
        'device_performance_verified': False, 'production_c1_gate_complete': False,
        'scope': 'Actual iOS app-host APIs, isolated temporary files and CoreGraphics offscreen pixels. Separate test build; not distributable binary UI, real touch, system picker, force-stop or power-loss recovery evidence.'}
    (ROOT/'reports/ios-host-tests.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('source_and_framework_sha256', 'test_summary')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
