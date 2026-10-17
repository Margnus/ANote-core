#!/usr/bin/env python3
"""Verify the app-host Canvas gate and source bindings; never equate it to ordinary UI integration."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'dev.notecore.preview'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    args = parser.parse_args()
    sdk = Path(args.adb).resolve().parent.parent
    assert args.serial == 'emulator-5580', 'Only the existing dedicated NoteCore emulator is authorized'

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], cwd=ROOT, capture_output=True,
                              check=True, timeout=120).stdout

    api = int(adb('shell', 'getprop', 'ro.build.version.sdk').decode().strip())
    assert api >= 29, 'Hardware RecordingCanvas regression requires public API 29+'
    build = json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))
    assert build['status'] == 'passed'
    apk = ROOT/build['android']['artifact']
    assert digest(apk) == build['android']['sha256']
    stamp = dict(line.split('  ', 1)[::-1] for line in (ROOT/'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    for relative, sha in {**build['source_and_build_input_sha256'], **stamp}.items():
        assert digest(ROOT/relative) == sha, ('Stale build', relative)
    baseline = json.loads((ROOT/'build/dev036e-android-files-before.json').read_text(encoding='utf-8'))

    def files():
        names = adb('shell', 'run-as', BUNDLE, 'ls', 'files/preview-notes').decode().splitlines()
        return {name: hashlib.sha256(adb('exec-out', 'run-as', BUNDLE, 'cat', 'files/preview-notes/'+name)).hexdigest()
                for name in names if re.fullmatch(r'[a-zA-Z0-9-]+\.ncp', name)}

    assert files() == baseline, 'App files changed before target verification; preserve and investigate'
    test = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install', '-r', str(apk)); adb('install', '-r', str(test))
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'render_target',
                 BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation').decode()
    (ROOT/'build/dev036e-target-host.log').write_text(output, encoding='utf-8')
    assert 'render_target_status=passed' in output and 'target_hardware=true' in output, output

    def integer(key):
        return int(re.search(r'\b'+key+r'=(\d+)', output).group(1))

    pid = integer('target_pid')
    raw = adb('logcat', '-d', '-v', 'epoch', '-s', 'NoteCoreRender:D').decode()
    events = []
    for line in raw.splitlines():
        match = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+D\s+NoteCoreRender\s*:\s*(.*)$', line)
        if match and int(match.group(2)) == pid:
            events.append({'epoch': match.group(1), 'pid': pid, 'message': match.group(3)})
    assert any('target-observed hardware=true backend=canonical-path' in event['message'] for event in events)
    (ROOT/'build/dev036e-target-events.json').write_text(json.dumps(events, indent=2)+'\n', encoding='utf-8')
    assert files() == baseline, 'Target test must not write canonical documents'
    # Explicitly restore a normal process after instrumentation; no uninstall/data clear.
    adb('shell', 'am', 'force-stop', BUNDLE)
    launch = adb('shell', 'am', 'start', '-W', '-n', BUNDLE+'/dev.notecore.app.MainActivity').decode()
    assert 'Status: ok' in launch
    ordinary_pid = int(adb('shell', 'pidof', BUNDLE).decode().strip())
    assert ordinary_pid != pid
    package_path = adb('shell', 'pm', 'path', BUNDLE).decode().strip()
    assert package_path.startswith('package:') and '\n' not in package_path
    assert hashlib.sha256(adb('exec-out', 'cat', package_path.removeprefix('package:'))).hexdigest() == digest(apk)
    assert files() == baseline
    holder = json.loads((ROOT/'reports/android-brush-common-tests.json').read_text(encoding='utf-8'))
    assert holder['status'] == 'passed' and holder['test_count'] == 94
    assert holder['hardware_recording_rejection_test_count'] == 1 and int(holder['api']) >= 29
    for relative, sha in holder['source_and_build_sha256'].items():
        assert digest(ROOT/relative) == sha, ('Stale hardware gate test', relative)
    assert digest(ROOT/holder['test_apk']) == holder['test_apk_sha256']
    assert digest(ROOT/'canvas-core/build/android-brush-tests/run.log') == holder['test_output_sha256']
    source_paths = set(stamp) | set(build['source_and_build_input_sha256']) | {
        'canvas-core/src/androidMain/kotlin/dev/notecore/canvas/prototype/AndroidBrushImagePoc.kt',
        'canvas-core/src/androidInstrumentedTest/kotlin/dev/notecore/canvas/prototype/AndroidBrushImagePocTest.kt',
        'tools/verify_android_render_target.py', 'reports/preview-build.json',
        'reports/android-brush-build-inputs.sha256', 'build/dev036e-android-files-before.json',
        'build/dev036e-target-host.log', 'build/dev036e-target-events.json',
        'reports/android-brush-common-tests.json', 'canvas-core/build/android-brush-tests/run.log',
        holder['test_apk']}
    sdk_sources = [sdk/'sources/android-36'/name for name in (
        'android/graphics/Bitmap.java', 'android/view/ViewTreeObserver.java', 'android/view/SurfaceControl.java')]
    report = {
        'schema': 'android-render-integration-1', 'status': 'target_verified_integration_blocked',
        'evidence_status': 'passed',
        'artifact_sha256': digest(apk), 'serial': args.serial, 'api': api,
        'device_fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint').decode().strip(),
        'app_host_pid': pid, 'app_host_events': events,
        'app_host_target': {'class': 'InkCanvasView.onDraw', 'hardware_accelerated': True,
                            'width': integer('target_width'), 'height': integer('target_height'),
                            'layer_type': 'LAYER_TYPE_NONE', 'input': 'isolated in-memory empty canonical page'},
        'hardware_recording_gate_test': {'actual_recording_canvas': True, 'holder_rejects_before_bitmap_draw': True,
                                         'charge_preserved_during_rejection': True, 'closed_after_no_native_escape': True,
                                         'art_tests': 94, 'scope': 'RenderNode recording only; no GPU reader or fence submitted'},
        'ordinary_process_restored': True, 'ordinary_pid_after_host': ordinary_pid,
        'ordinary_installed_apk_matches': True,
        'ordinary_screen_target_observed': False, 'ordinary_new_backend_consumption_verified': False,
        'desktop_ui_interaction_verified': False,
        'ui_limitation': 'No Android emulator app/window appeared in CUA inventory; emulator log reports EGL display-surface errors. Actual app-host window draws hardware onDraw, but neither it nor source inspection proves ordinary-package UI/backend consumption.',
        'original_file_count': len(baseline), 'all_original_files_unchanged': True,
        'no_files_added_or_deleted_by_target_test': True,
        'source_evidence_sha256': {relative: digest(ROOT/relative) for relative in sorted(source_paths)},
        'sdk_contract_source_sha256': {str(path): digest(path) for path in sdk_sources},
        'source_contracts': [
            {'url': 'https://developer.android.com/reference/android/graphics/Bitmap#recycle()',
             'conclusion': 'Avoid recycle for Bitmap used in a View or referenced elsewhere. isRecycled only confirms that caller-side recycle happened; it is not a precondition proof that hardware readers finished.'},
            {'url': 'https://developer.android.com/reference/android/graphics/Bitmap#reconfigure(int,int,android.graphics.Bitmap.Config)',
             'conclusion': 'View invalidation followed by a draw pass is documented for reconfiguration; it does not supply an independent source-release callback, all-view detach evidence or a GPU fence.'},
            {'url': 'https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable)',
             'conclusion': 'Hardware frame submitted to swap chain; may not be visible. Callback does not carry a source Bitmap release receipt or SyncFence.'},
            {'url': 'https://developer.android.com/reference/android/view/SurfaceControl.Transaction#setBuffer(android.view.SurfaceControl,android.hardware.HardwareBuffer,android.hardware.SyncFence,java.util.function.Consumer)',
             'conclusion': 'API33+ explicit compositor buffer release callback; valid returned SyncFence must signal before reuse. This is a candidate separate presentation boundary, not an existing Bitmap/View release mechanism.'},
        ],
        'decision': 'Keep hardware rejection and canonical Path. Do not infer source completion from draw/invalidate/post/GC/frame commit. SurfaceControl release-fence route requires concrete upload, composition, bounded owner/ACK and detach protocol before adoption; no new GPU backend selected here.',
        'remaining_gates': ['Android same-NoteStore shared-executor owner seam and restricted module boundary',
                            'bounded CPU RGBA to HardwareBuffer upload, stride/format/copy accounting',
                            'API33+ release callback plus valid-fence wait without blocking UI/storage worker',
                            'static frame/dynamic ink ordering and atomic fallback under clip/viewport changes',
                            'detach/background/close retaining callback/fence/UNKNOWN responsibility',
                            'ordinary-package UI, data-preserving reopen and actual hardware presentation'],
        'scope': 'Actual app-host hardware destination observation plus source-bound safety decision. Ordinary Android preview remains Path; separate ART hardware rejection regression is not backend acceptance. No physical stylus/frame/heap/GPU-fence/C1/SQLite claim.',
        'gpu_fence_verified': False, 'physical_device_or_heap_measured': False, 'c1_gate_complete': False,
    }
    (ROOT/'reports/android-render-integration.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('status', 'app_host_pid', 'ordinary_pid_after_host',
                     'original_file_count', 'all_original_files_unchanged', 'ordinary_new_backend_consumption_verified')}))


if __name__ == '__main__':
    main()
