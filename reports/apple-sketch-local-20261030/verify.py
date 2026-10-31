"""Bind the original failure, local repair and separately observed CUA journey."""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
BUILD = ROOT/'build/apple-sketch-local-20261030'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')


def trace(folder):
    return next(value for path in folder.glob('*.json')
                if isinstance(value := read(path), dict) and 'center_rgba' in value)


def main():
    OUT.mkdir(exist_ok=True)
    before, after, ordinary = read(BUILD/'before.json'), read(BUILD/'after.json'), read(BUILD/'ordinary-launch.json')
    assert before['notes'] == after['notes'] and len(after['notes']) == 16
    assert before['payload'] == after['payload'] and ordinary['no_xctest']
    assert not after['process_continuity_verified']
    assert 'UIApplicationSupportsMultipleScenes' in ordinary['info_scene_manifest']
    baseline = read(BUILD/'before-summary.json')
    assert baseline['result'] == 'Failed' and baseline['passedTests'] == 0
    assert 'bootstrapping' in baseline['testFailures'][0]['failureText']
    scene = read(BUILD/'scene-summary.json')
    assert scene['totalTestCount'] == 5 and scene['passedTests'] == 4 and scene['failedTests'] == 1
    final = read(BUILD/'final-summary.json')
    assert final['totalTestCount'] == final['passedTests'] == 5 and final['failedTests'] == final['skippedTests'] == 0
    diagnostic, corrected = trace(BUILD/'diagnostic-attachments'), trace(BUILD/'final-attachments')
    assert diagnostic['center_rgba'] == [255, 255, 255, 255] and diagnostic['colored_bounds'] == [95, 737, 104, 746]
    assert corrected['center_rgba'] == [18, 86, 52, 255] and corrected['colored_bounds'] == [95, 95, 104, 104]
    assert diagnostic['colored_count'] == corrected['colored_count'] == 88
    for item in (diagnostic, corrected):
        assert item['stroke_count'] == 1 and item['point_count'] == 2 and item['point_x'] == item['point_y'] == 100
    original_source = OUT/'original-source'; original_source.mkdir(exist_ok=True)
    for name, digest in before['sample_source_sha256'].items():
        data = subprocess.check_output(['git', 'show', 'c56ecb0:'+name], cwd=ROOT)
        assert hashlib.sha256(data).hexdigest() == digest
        path = original_source/Path(name).name; path.write_bytes(data)
    canvas = ROOT/'samples/apple-sketch/NoteCoreSketch/SketchCanvas.swift'
    assert sha(canvas) == before['sample_source_sha256'][canvas.relative_to(ROOT).as_posix()]
    strict = 'XCTAssertEqual(Array(bytes[offset..<offset + 4]), [0x12, 0x56, 0x34, 255])'
    assert strict in (original_source/'SketchHostTests.swift').read_text(encoding='utf-8')
    assert strict in (ROOT/'samples/apple-sketch/NoteCoreSketchTests/SketchHostTests.swift').read_text(encoding='utf-8')
    framework = read(ROOT/'reports/ios-preview-build.json')['source_and_build_input_sha256']
    framework = {path: digest for path, digest in framework.items() if '/src/commonMain/' in path or '/src/iosMain/' in path
                 or path.endswith('.kts') or path == 'gradle.properties' or path.endswith('.framework/NoteCanvasPrototype')}
    assert framework and all(sha(ROOT/path) == digest for path, digest in framework.items())
    for path in BUILD.iterdir():
        if path.is_file() and path.suffix in {'.json', '.log', '.ips'}: shutil.copyfile(path, OUT/path.name)
    for name in ('diagnostic-attachments', 'after-attachments', 'final-attachments'):
        shutil.copytree(BUILD/name, OUT/name, dirs_exist_ok=True)
    sample = ROOT/'samples/apple-sketch'
    source = {p.relative_to(ROOT).as_posix(): sha(p) for p in sample.rglob('*')
              if p.is_file() and p.suffix in {'.swift', '.pbxproj', '.xcscheme', '.md'}}
    host = BUILD/'scene-xcode/Build/Products/Debug-iphonesimulator/NoteCoreSketch.app'
    plugin = host/'PlugIns/NoteCoreSketchTests.xctest/NoteCoreSketchTests'
    app = BUILD/'ordinary/Build/Products/Debug-iphonesimulator/NoteCoreSketch.app'
    assert ordinary['payload'] == {p.relative_to(app).as_posix(): sha(p) for p in app.rglob('*') if p.is_file()}
    with zipfile.ZipFile(OUT/'ordinary-and-test-executable.zip', 'w', zipfile.ZIP_DEFLATED) as zipped:
        for path in app.rglob('*'):
            if path.is_file(): zipped.write(path, 'NoteCoreSketch.app/'+path.relative_to(app).as_posix())
        zipped.write(plugin, 'host-test/NoteCoreSketchTests')
    ui = read(OUT/'manual-cua-observations.json')
    assert ui['ordinary_pid_at_observation'] == 26462 and ui['final_state'] == '2 笔 · 红笔'
    assert ui['automated_event_or_pixel_oracle'] is False
    previous_ordinary = read(BUILD/'ordinary-launch-before-scene-callbacks.json')
    with zipfile.ZipFile(OUT/'ordinary-before-scene-callbacks.zip') as previous:
        prefix = 'NoteCoreSketch.app/'
        assert {name.removeprefix(prefix): hashlib.sha256(previous.read(name)).hexdigest()
                for name in previous.namelist() if name.startswith(prefix)} == previous_ordinary['payload']
    save(OUT/'source-and-artifacts.json', {'sample_source_sha256': source, 'framework_inputs_sha256': framework,
         'host_test_executable_sha256': sha(plugin), 'ordinary_payload_sha256': ordinary['payload']})
    save(ROOT/'reports/apple-sketch-local.json', {
        'schema': 'apple-sketch-local-1', 'status': 'passed_with_original_failures_retained',
        'execution_dates': ['2026-10-30', '2026-10-31'], 'preservation_verified_date': '2026-10-31',
        'scope': 'Independent samples/apple-sketch target only. Final five iOS27 App-host tests include actual Scene delegate cancellation. Manual ordinary CUA tools/read-only/history journey belongs to the earlier pre-callback artifact, archived separately.',
        'ci_diagnostic': 'reports/apple-sketch-ci-41035025084/verification.json',
        'repairs': ['Adopt standard UIWindowScene lifecycle after actual iOS27 bootstrap trap.',
                    'Apply UIKit caller y transform to raw bitmap test fixture. Original strict center RGBA assertion and SketchCanvas renderer unchanged.',
                    'Route Scene deactivation/disconnection to the original unfinished-input cancel method; existing lifecycle test verifies active charge releases and committed root remains.'],
        'host_tests': 5, 'failed_tests': 0, 'skipped_tests': 0,
        'preservation': {'preview_original_files': 16, 'all_hashes_and_complete_payload_unchanged': True,
                         'preview_no_xctest': True, 'final_preview_pid': after['ordinary_pid'],
                         'same_pid_continuity_verified': False},
        'limitations': ['The old iOS18.5 remote runs remain failed; these fixes were actually executed locally on iOS27 only.',
                       'The full manual GUI journey belongs to the archived ordinary package before explicit Scene callbacks. Do not inherit it to the final callback build, whose host tests/build/launch are separately verified.',
                       'CUA observations are manually transcribed from actual AX/screenshot results; no automated UI/pixel oracle or saved screenshots.',
                       'Interruption left simulator Shutdown; same UDID cold-booted and original Preview launched with a new PID. No same-process continuity claim.',
                       'No stable SDK/public-whitelist completion, physical M-1/heap/stylus, unified peak, production storage or full C1 acceptance.',
                       'The other samples/sketch-host-ios target and its seven tests remain separate; this does not replace or inherit that evidence.'],
        'c1_complete': False, 'whole_requirement_accepted': False,
        'source_and_evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in [
            *[ROOT/path for path in source], *OUT.rglob('*'),
            ROOT/'reports/apple-sketch-ci-41035025084/verification.json'] if p.is_file()}})
    print(json.dumps({'status': 'passed', 'host_tests': 5, 'preview_originals': 16,
                      'original_pixel_assertion_unchanged': True, 'final_preview_pid': after['ordinary_pid']}))


if __name__ == '__main__':
    main()
