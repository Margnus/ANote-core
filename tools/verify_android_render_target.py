#!/usr/bin/env python3
"""Verify original app-host surface integration and source bindings; never equate it to ordinary UI integration."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from verify_android_brush_common import EXPECTED_ART_TEST_COUNT

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
    assert api >= 33, 'SurfaceControl release callback integration requires API 33+'
    build = json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))
    assert build['status'] == 'passed'
    apk = ROOT/build['android']['artifact']
    assert digest(apk) == build['android']['sha256']
    stamp = dict(line.split('  ', 1)[::-1] for line in (ROOT/'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    for relative, sha in {**build['source_and_build_input_sha256'], **stamp}.items():
        assert digest(ROOT/relative) == sha, ('Stale build', relative)
    baseline = json.loads((ROOT/'build/dev036g-files-before.json').read_text(encoding='utf-8'))
    baseline_drift = json.loads((ROOT/'build/dev036g-baseline-drift.json').read_text(encoding='utf-8'))
    assert baseline_drift['current_before_test_count'] == len(baseline)

    def files():
        names = adb('shell', 'run-as', BUNDLE, 'ls', 'files/preview-notes').decode().splitlines()
        return {name: hashlib.sha256(adb('exec-out', 'run-as', BUNDLE, 'cat', 'files/preview-notes/'+name)).hexdigest()
                for name in names if re.fullmatch(r'[a-zA-Z0-9-]+\.ncp', name)}

    assert files() == baseline, 'App files changed before target verification; preserve and investigate'
    test = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install', '-r', str(apk)); adb('install', '-r', str(test))
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'surface_frames',
                 BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation').decode()
    (ROOT/'build/dev036g-target-host.log').write_text(output, encoding='utf-8', newline='\n')
    assert 'surface_status=passed' in output, output
    observed = json.loads(re.search(r'surface_frames=(\{.*\})', output).group(1))
    assert all(observed[key] for key in ('app_hierarchy_pixels', 'active_static_alpha', 'page_zoom',
        'actual_release_callback', 'valid_release_fence_observed', 'late_view_ack', 'resize', 'pending_complete_fallback',
        'shared_storage_after_close', 'saved_new_owner_reopen', 'weak_saved_identity_rewrites_after_clear', 'unknown_readonly_reconciled',
        'ambiguous_handoff_recovered', 'deferred_completion_keeps_charge', 'storage_during_deferred_release',
        'new_frame_after_handoff_recovery', 'stopped_backend_keeps_accepted_write', 'stopped_backend_rejects_only_pending'))
    assert observed['unknown_retained_bytes'] > 0 and observed['handoff_unknown_retained_bytes'] > 0
    assert observed['long_unsignaled_gpu_fence_tested'] is False

    def integer(key):
        return int(re.search(r'\b'+key+r'=(\d+)', output).group(1))

    pid = integer('surface_pid')
    raw = adb('logcat', '-d', '-v', 'epoch', '-s', 'NoteCoreRender:D').decode()
    events = []
    for line in raw.splitlines():
        match = re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+D\s+NoteCoreRender\s*:\s*(.*)$', line)
        if match and int(match.group(2)) == pid:
            events.append({'epoch': match.group(1), 'pid': pid, 'message': match.group(3)})
    assert any('frame-scheduled' in event['message'] and 'backend=surface-control' in event['message'] for event in events)
    assert any('release-receipt' in event['message'] for event in events)
    assert events[-1]['message'] == 'owner-drained charged=0'
    (ROOT/'build/dev036g-target-events.json').write_text(json.dumps(events, indent=2)+'\n', encoding='utf-8', newline='\n')
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
    assert holder['status'] == 'passed' and holder['test_count'] == EXPECTED_ART_TEST_COUNT
    assert holder['hardware_recording_rejection_test_count'] == 1 and int(holder['api']) >= 29
    for relative, sha in holder['source_and_build_sha256'].items():
        assert digest(ROOT/relative) == sha, ('Stale hardware gate test', relative)
    assert digest(ROOT/holder['test_apk']) == holder['test_apk_sha256']
    assert digest(ROOT/'canvas-core/build/android-brush-tests/run.log') == holder['test_output_sha256']
    source_paths = set(stamp) | set(build['source_and_build_input_sha256']) | {
        'canvas-core/src/androidMain/kotlin/dev/notecore/canvas/prototype/AndroidBrushImagePoc.kt',
        'canvas-core/src/androidInstrumentedTest/kotlin/dev/notecore/canvas/prototype/AndroidBrushImagePocTest.kt',
        'tools/verify_android_render_target.py', 'reports/preview-build.json',
        'reports/android-brush-build-inputs.sha256', 'build/dev036g-files-before.json', 'build/dev036g-baseline-drift.json',
        'build/dev036g-target-host.log', 'build/dev036g-target-events.json',
        'reports/android-brush-common-tests.json', 'canvas-core/build/android-brush-tests/run.log',
        holder['test_apk']}
    sdk_sources = [sdk/'sources/android-36'/name for name in (
        'android/graphics/Bitmap.java', 'android/view/ViewTreeObserver.java', 'android/view/SurfaceControl.java', 'android/view/AttachedSurfaceControl.java',
        'android/view/SurfaceView.java', 'android/hardware/SyncFence.java', 'android/media/ImageReader.java',
        'android/media/ImageWriter.java')]
    report = {
        'schema': 'android-render-integration-3', 'status': 'host_surface_verified_ordinary_ui_pending',
        'evidence_status': 'passed',
        'artifact_sha256': digest(apk), 'serial': args.serial, 'api': api,
        'device_fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint').decode().strip(),
        'app_host_pid': pid, 'app_host_events': events,
        'app_host_original_hierarchy_compositor_verified': True,
        'app_host_surface_observations': observed,
        'app_host_target': {'class': 'InkSurfaceHost + original InkCanvasView + original NoteStore',
                            'hardware_accelerated': True, 'layer_type': 'LAYER_TYPE_NONE',
                            'input': 'isolated canonical three-stroke/two-page fixture, actual active touch events; no personal file edits'},
        'hardware_recording_gate_test': {'actual_recording_canvas': True, 'holder_rejects_before_bitmap_draw': True,
                                         'charge_preserved_during_rejection': True, 'closed_after_no_native_escape': True,
                                         'art_tests': holder['test_count'], 'scope': 'RenderNode recording only; no GPU reader or fence submitted'},
        'ordinary_process_restored': True, 'ordinary_pid_after_host': ordinary_pid,
        'ordinary_installed_apk_matches': True,
        'ordinary_screen_target_observed': False, 'ordinary_new_backend_consumption_verified': False,
        'desktop_ui_interaction_verified': False,
        'ui_limitation': 'This collector executes native app-host hierarchy/compositor tests and restores an ordinary process. It does not perform or verify desktop UI interactions. Ordinary-process UI and backend consumption require separately retained source-bound evidence; app-host success is not a substitute.',
        'original_file_count': len(baseline), 'all_original_files_unchanged': True,
        'original_files_scope': 'Unchanged relative to the current pre-G-test baseline after AVD restore, not equivalence to F historical baseline.',
        'historical_baseline_comparison': baseline_drift,
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
             'conclusion': 'API33+ explicit compositor buffer release callback; valid returned SyncFence must signal before reuse. This is the adopted unfrozen surface boundary, not an existing Bitmap/View release mechanism.'},
        ],
        'decision': 'Adopt only as an unfrozen API33+ host-verified integration. One NoteStore-owned render adapter shares the storage executor; ACK only after drain. Full canonical fallback until ViewRoot-synchronized complete frame submission; dynamic ink stays in the original overlay. Real compositor release callback plus valid SyncFence zero-time completion gates all uploaded/native image release. Ambiguous handoff can recover only after the corresponding actual callback and fence completion; destructive cleanup UNKNOWN remains quarantined. A test-only software gate may delay settlement but cannot bypass the real fence. Original CPU holder hardware rejection remains. API26-32 retain Path. Ordinary UI acceptance is pending.',
        'resource_scope': 'Single native reader; 64MiB total/48MiB resident logical budget. Upload checks measured plane row/pixel stride against reserved allowance (three tight RGBA copies plus 4096-aligned row upper bound). ImageWriter has exactly one fresh dequeue, CPU producer queueing passes acquired producer fence into transaction; no buffer re-use, UI fence wait or new worker. SurfaceView destination queues/driver allocation headers/GC/actual process heap are unmeasured and not bounded by logical charge.',
        'ordinary_backend_code_connected': True,
        'compositor_release_fence_verified_on_emulator': True,
        'handoff_recovery_verified': True, 'long_unsignaled_gpu_fence_tested': False,
        'fault_scope': 'One injected exception after an actual successful ViewRoot submission, then actual compositor release receipt. A software settlement gate retains charge and blocks new pool while storage saves; it does not create an unsignaled GPU fence. Gate can only defer and is checked after the real fence check. Cleanup UNKNOWN still requires prior native facts and explicit read-only reconciliation.',
        'remaining_gates': ['ordinary-package CUA UI input/navigation/background/reopen',
                            'API26-32 fallback runtime and broader Android/vendor matrix',
                            'real long-lived unsignaled fence; broader ambiguous native handoff/cleanup failure matrix (no timeout release)',
                            'physical latency, frame/heap/driver allocation and production GPU comparison',
                            'arbitrary transforms and complete owner loss/cross-thread transfer'],
        'scope': 'Actual original Android app-host hierarchy consumes complete compositor frames and active/static replacement, page/zoom, source release/fence, detach/late ACK, cleanup UNKNOWN, actual-receipt recovery after injected post-handoff confirmation loss, delayed owner settlement/backpressure/shared storage and saved new-owner reopen. Ordinary code is connected, but no ordinary-process UI acceptance. No physical stylus/frame/heap, production GPU backend, C1 or SQLite claim.',
        'gpu_fence_verified': False, 'physical_device_or_heap_measured': False, 'c1_gate_complete': False,
    }
    (ROOT/'reports/android-render-integration.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: report[key] for key in ('status', 'app_host_pid', 'ordinary_pid_after_host',
                     'original_file_count', 'all_original_files_unchanged', 'ordinary_new_backend_consumption_verified')}))


if __name__ == '__main__':
    main()
