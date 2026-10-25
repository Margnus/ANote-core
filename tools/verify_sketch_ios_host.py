#!/usr/bin/env python3
"""Build/run the independent Apple memory consumer; preserve the original NoteCore app."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / 'samples/sketch-host-ios'
CASE = ROOT / 'build/sketch-host-ios'
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
BUNDLE = 'dev.notecore.sample.sketch.ios'
FRAMEWORK = 'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework'
EXPECTED = {
    'testValidatedCreationAndHostInjectedToolsUseOriginalNotebook()',
    'testReadOnlyInvalidToolAndRejectedBatchKeepAcceptedPrefix()',
    'testCoalescedUIKitAndResizeReplacementKeepCanonicalSession()',
    'testBorrowedBackgroundDrawsActualPixels()',
    'testForeignOwnerRefusesBeforeCoreAndImmutableCheckpointEncodesOffOwner()',
    'testExplicitCancelAndRetirementKeepCompletedInk()',
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def command(*args):
    return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True, encoding='utf-8', timeout=120).stdout


def sim(*args):
    return command('xcrun', 'simctl', *args)


def file_set(folder):
    return {p.relative_to(folder).as_posix(): sha(p) for p in sorted(folder.rglob('*')) if p.is_file()}


def original():
    app = Path(sim('get_app_container', DEVICE, 'dev.notecore.preview', 'app').strip())
    data = Path(sim('get_app_container', DEVICE, 'dev.notecore.preview', 'data').strip()) / 'Documents/PreviewNotes'
    assert not list(app.rglob('*.xctest'))
    payload = file_set(app)
    assert payload == read(ROOT / 'reports/ios-preview-restored.json')['installed_payload_sha256']
    notes = {p.name: sha(p) for p in sorted(data.glob('*.ncp'))}
    known = read(ROOT / 'build/catalog-budget/ios-after.json')
    assert all(notes.get(name) == digest for name, digest in known.items())
    backup = CASE / 'notecore-originals'; backup.mkdir(exist_ok=True)
    for name, digest in notes.items():
        target = backup / name
        if target.exists():
            assert sha(target) == digest, ('Existing baseline changed; preserve and investigate', name)
        else:
            shutil.copy2(data / name, target)
    return {'payload': payload, 'notes': notes}


def sources():
    receipt = read(ROOT / 'reports/ios-preview-build.json')
    assert receipt['status'] == 'passed'
    framework_inputs = {key: value for key, value in receipt['source_and_build_input_sha256'].items()
                        if '/src/commonMain/' in key or '/src/iosMain/' in key or key.endswith('.kts')
                        or key == 'gradle.properties' or key.endswith('.framework/NoteCanvasPrototype')}
    assert framework_inputs and all(sha(ROOT / key) == value for key, value in framework_inputs.items())
    paths = [*SAMPLE.glob('Sources/*.swift'), *SAMPLE.glob('Tests/*.swift'), SAMPLE / 'README.md',
             SAMPLE / 'SketchHost.xcodeproj/project.pbxproj',
             SAMPLE / 'SketchHost.xcodeproj/xcshareddata/xcschemes/SketchHost.xcscheme',
             ROOT / FRAMEWORK / 'Headers/NoteCanvasPrototype.h', Path(__file__),
             ROOT / 'docs/最小预览消费者契约.md']
    result = {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}
    result.update(framework_inputs)
    result['reports/ios-preview-build.json'] = sha(ROOT / 'reports/ios-preview-build.json')
    return result


def dependency_check():
    project = plistlib.loads((SAMPLE / 'SketchHost.xcodeproj/project.pbxproj').read_bytes())['objects']
    app = next(o for o in project.values() if o.get('isa') == 'PBXNativeTarget' and o['name'] == 'SketchHost')
    assert app['dependencies'] == []
    linked = []
    compiled = []
    for phase_id in app['buildPhases']:
        phase = project[phase_id]
        for build_file in phase['files']:
            path = project[project[build_file]['fileRef']]['path']
            if phase['isa'] == 'PBXSourcesBuildPhase':
                compiled.append(path)
            elif phase['isa'] == 'PBXFrameworksBuildPhase':
                linked.append(path)
    assert sorted(compiled) == ['Sources/SketchCanvas.swift', 'Sources/SketchHostApp.swift', 'Sources/SketchSession.swift']
    assert linked == ['../../' + FRAMEWORK]
    allowed = {'Foundation', 'CoreGraphics', 'UIKit', 'Combine', 'NoteCanvasPrototype', 'SwiftUI'}
    for path in SAMPLE.glob('Sources/*.swift'):
        imports = set(re.findall(r'^import (\w+)', path.read_text(encoding='utf-8'), re.MULTILINE))
        assert imports <= allowed and 'NoteCorePreview' not in imports
    return {'app_sources': compiled, 'linked_framework': linked, 'app_target_dependencies': []}


def execute():
    devices = read_json_command('xcrun', 'simctl', 'list', 'devices', '--json')['devices']
    device = next(d for group in devices.values() for d in group if d['udid'] == DEVICE)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted' and device['isAvailable']
    CASE.mkdir(parents=True, exist_ok=True)
    before = original(); save(CASE / 'notecore-before.json', before)
    inputs = sources(); dependencies = dependency_check()
    ordinary = SAMPLE / 'build/ordinary'
    build = ['xcodebuild', '-quiet', '-project', str(SAMPLE / 'SketchHost.xcodeproj'), '-scheme', 'SketchHost',
             '-sdk', 'iphonesimulator', 'CODE_SIGNING_ALLOWED=NO']
    def run(args, name):
        print(name, flush=True)
        with (CASE / name).open('w', encoding='utf-8') as log:
            subprocess.run(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
    run([*build, '-destination', 'generic/platform=iOS Simulator', '-derivedDataPath', str(ordinary), 'build'], 'ordinary-build.log')
    app = ordinary / 'Build/Products/Debug-iphonesimulator/SketchHost.app'
    assert plistlib.loads((app / 'Info.plist').read_bytes())['CFBundleIdentifier'] == BUNDLE
    assert not list(app.rglob('*.xctest'))
    result = CASE / ('run-' + uuid.uuid4().hex + '.xcresult')
    test_derived = SAMPLE / 'build/host-tests'
    try:
        run([*build, '-destination', 'platform=iOS Simulator,id=' + DEVICE, '-derivedDataPath', str(test_derived),
             '-resultBundlePath', str(result), '-collect-test-diagnostics', 'never', 'test'], 'test.log')
    finally:
        # Only this sample's payload is restored; never install, remove or repair the NoteCore App.
        sim('install', DEVICE, str(app))
        installed = Path(sim('get_app_container', DEVICE, BUNDLE, 'app').strip())
        for name in ('SketchHostTests.xctest', 'SketchHostTests.xctest.dSYM'):
            leftover = installed / 'PlugIns' / name
            if leftover.exists():
                target = CASE / 'quarantine' / uuid.uuid4().hex / name
                target.parent.mkdir(parents=True, exist_ok=True); shutil.move(str(leftover), str(target))
        assert not list(installed.rglob('*.xctest')) and file_set(installed) == file_set(app)
        after = original(); save(CASE / 'notecore-after.json', after)
        assert after == before, 'Original NoteCore payload or notes changed'
    assert sources() == inputs, 'Consumer/core inputs changed during build/test'
    summary = read_json_command('xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(result), '--format', 'json')
    tree = read_json_command('xcrun', 'xcresulttool', 'get', 'test-results', 'tests', '--path', str(result), '--format', 'json')
    def cases(nodes):
        for node in nodes:
            if node.get('nodeType') == 'Test Case':
                yield node
            yield from cases(node.get('children', []))
    tests = list(cases(tree['testNodes']))
    assert {node['name'] for node in tests} == EXPECTED and all(node['result'] == 'Passed' for node in tests)
    assert summary['result'] == 'Passed' and summary['totalTestCount'] == 6
    assert summary['failedTests'] == summary['skippedTests'] == summary['expectedFailures'] == 0
    assert any(d['device']['deviceId'] == DEVICE for d in summary['devicesAndConfigurations'])
    save(CASE / 'summary.json', summary)
    symbols = ''
    for binary in [app / 'SketchHost', app / 'SketchHost.debug.dylib']:
        if binary.exists():
            symbols += command('xcrun', 'nm', '-j', str(binary))
    assert '$s15NoteCorePreview' not in symbols
    (CASE / 'symbols.txt').write_text(symbols, encoding='utf-8')
    archive = ROOT / 'dist/NoteCore-SketchHost-0.1.0-ios-simulator-arm64.app.zip'
    archive.parent.mkdir(exist_ok=True)
    shutil.make_archive(str(archive.with_suffix('')), 'zip', root_dir=app.parent, base_dir=app.name)
    launched = sim('launch', '--terminate-running-process', DEVICE, BUNDLE)
    pid = int(launched.strip().rsplit(':', 1)[1])
    paths = [CASE / name for name in ('notecore-before.json', 'notecore-after.json', 'ordinary-build.log', 'test.log', 'summary.json', 'symbols.txt')]
    paths += [archive, test_derived / 'Build/Products/Debug-iphonesimulator/SketchHost.app/PlugIns/SketchHostTests.xctest/SketchHostTests']
    inputs.update({p.relative_to(ROOT).as_posix(): sha(p) for p in paths})
    inputs.update({(app / relative).relative_to(ROOT).as_posix(): digest for relative, digest in file_set(app).items()})
    save(CASE / 'evidence.json', {'status': 'passed', 'tests': sorted(EXPECTED), 'device': DEVICE,
         'artifact': archive.relative_to(ROOT).as_posix(), 'artifact_sha256': sha(archive), 'ordinary_launch_pid': pid,
         'ordinary_sample_restored_no_xctest': True, 'original_payload_unchanged': True,
         'original_note_count': len(before['notes']), 'dependencies': dependencies, 'bindings': inputs})


def read_json_command(*args):
    return json.loads(command(*args))


def verify():
    path = CASE / 'evidence.json'; receipt = read(path)
    assert receipt['status'] == 'passed' and set(receipt['tests']) == EXPECTED
    assert all(sha(ROOT / relative) == digest for relative, digest in receipt['bindings'].items())
    assert dependency_check() == receipt['dependencies']
    assert read(CASE / 'notecore-before.json') == read(CASE / 'notecore-after.json')
    bindings = dict(receipt['bindings']); bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    observations = CASE / 'cua-observations.json'
    ui = None
    if observations.exists():
        ui = read(observations)
        assert ui['artifact_sha256'] == receipt['artifact_sha256'] and ui['ordinary_pid'] == receipt['ordinary_launch_pid']
        assert ui['ordinary_ui'] and ui['blue_and_red_strokes_visible'] and ui['readonly_keeps_strokes']
        bindings[observations.relative_to(ROOT).as_posix()] = sha(observations)
    report = {'schema': 'second-host-ios-1', 'status': 'passed', 'bundle': BUNDLE, 'device': DEVICE,
        'test_count': len(EXPECTED), 'tests': sorted(EXPECTED), 'dependencies': receipt['dependencies'],
        'artifact': receipt['artifact'], 'artifact_sha256': receipt['artifact_sha256'],
        'ordinary_launch_pid': receipt['ordinary_launch_pid'], 'ordinary_sample_restored_no_xctest': True,
        'original_notecore_payload_unchanged': True, 'original_notecore_note_count_unchanged': receipt['original_note_count'],
        'ordinary_ui_verified': ui is not None, 'ordinary_ui_observations': ui,
        'scope': 'Independent Apple memory sketch consumer of the original core framework; actual separate-app XCTest, source/target/symbol dependency checks and ordinary package launch. UI observations only when separately present.',
        'decision': 'Use original validated factory notebook directly; host injects tools/background and owner/read-only policy. No NoteCore App import, persistence, second document authority, worker or native pool.',
        'limits': ['Unfrozen subset only; original Core value/index/owner preconditions and other exported symbols remain',
                   'Foreign mutable host calls are refused, not transferred; only immutable checkpoint encoding crosses threads',
                   'No persistence, process recovery, physical heap/stylus/latency, full SDK or C1 approval'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    save(ROOT / 'reports/second-host-ios.json', report)
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--device', required=True); parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args(); assert args.device == DEVICE
    if not args.verify_only:
        execute()
    verify()


if __name__ == '__main__':
    main()
