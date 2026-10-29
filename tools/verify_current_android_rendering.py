#!/usr/bin/env python3
"""Validate current source-bound emulator compositor/owner checks and ordinary PID drain."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/current-api35-rendering'
REQUIRED = '''app_hierarchy_pixels active_static_alpha page_zoom resize pending_complete_fallback
actual_release_callback valid_release_fence_observed late_view_ack shared_storage_after_close
saved_new_owner_reopen weak_saved_identity_rewrites_after_clear
export_picker_single_capture_budget_identity_and_io export_activity_state_and_stale_cancel
typed_reader_rejects_original_open_import_without_overwrite
lost_view_retires_on_owner_progress_without_bypassing_receipts
queued_draw_removal_lost_endpoint_without_draw lost_view_late_worker_ack_without_publication
unknown_readonly_reconciled ambiguous_handoff_recovered deferred_completion_keeps_charge
storage_during_deferred_release new_frame_after_handoff_recovery stopped_backend_keeps_accepted_write
stopped_backend_rejects_only_pending terminal_save_notification_drops_receiver_retains_content
retiring_owner_waits_native_receipts retiring_owner_acks_late_work_and_save
retiring_owner_unknown_readonly_reconciled'''.split()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    build = json.loads((ROOT / 'reports/current-api35-runtime.json').read_text(encoding='utf-8'))
    for relative, expected in build['source_and_build_input_sha256'].items():
        assert sha(ROOT / relative) == expected, ('stale source/build', relative)
    log = (CASE / 'host/run.log').read_text(encoding='utf-8')
    assert 'surface_status=passed' in log and 'INSTRUMENTATION_CODE: -1' in log
    observed = json.loads(re.search(r'surface_frames=(\{.*\})', log).group(1))
    assert all(observed[key] is True for key in REQUIRED)
    assert observed['startup_no_transient_blank_verified'] is False
    assert observed['long_unsignaled_gpu_fence_tested'] is False
    assert observed['unknown_retained_bytes'] > 0 and observed['handoff_unknown_retained_bytes'] > 0
    host_pid = int(re.search(r'surface_pid=(\d+)', log).group(1))
    ordinary_pid = int((CASE / 'ordinary/pid.txt').read_text(encoding='utf-8'))
    assert host_pid != ordinary_pid
    render = (CASE / 'ordinary/render.log').read_text(encoding='utf-8').splitlines()
    events = [line for line in render if 'NoteCoreRender:' in line]
    assert events and all(re.search(r'\s' + str(ordinary_pid) + r'\s+' + str(ordinary_pid) + r'\s+D\s', line) for line in events)
    assert any('target-observed hardware=true' in line for line in events)
    schedule = next(i for i, line in enumerate(events) if 'frame-scheduled' in line and 'backend=surface-control' in line)
    assert int(re.search(r'charged=(\d+)', events[schedule]).group(1)) > 0
    receipt = next(i for i, line in enumerate(events) if 'release-receipt charged=0' in line)
    release = next(i for i, line in enumerate(events) if 'source-released charged=0' in line)
    drain = next(i for i, line in enumerate(events) if 'owner-drained charged=0' in line)
    assert schedule < receipt < release < drain
    before = json.loads((CASE / 'host/before.json').read_text(encoding='utf-8'))
    assert len(before) == 5
    assert json.loads((CASE / 'host/after.json').read_text(encoding='utf-8')) == before
    assert json.loads((CASE / 'ordinary/files-after.json').read_text(encoding='utf-8')) == before
    result = {
        'schema': 1, 'status': 'passed', 'avd': 'NoteCoreCurrent35', 'serial': 'emulator-5590',
        'api': 35, 'gpu': 'SwiftShader', 'host_pid': host_pid, 'ordinary_pid': ordinary_pid,
        'host_observations': observed, 'host_true_contract_fields': REQUIRED, 'ordinary_render_events': events,
        'files_before_and_after': before,
        'scope': 'Current original InkCanvasView/NoteStore/worker/SurfaceControl instrumentation with isolated cache fixtures, plus independently launched ordinary App opening a saved two-stroke note and returning to library. Actual release callback/fence in host; ordinary log proves source release and owner charge drain. Logical charge is not heap measurement.',
        'physical_device_verified': False, 'long_unsignaled_gpu_fence_verified': False,
        'complete_owner_loss_or_thread_transfer_verified': False, 'apple_verified': False,
        'startup_no_blank_verified': False, 'c1_complete': False, 'goal_complete': False,
        'source_build_report_sha256': sha(ROOT / 'reports/current-api35-runtime.json'),
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in CASE.rglob('*') if p.is_file()},
        'verifier_sha256': sha(Path(__file__)),
    }
    (ROOT / 'reports/current-api35-rendering.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': result['status'], 'host_true_fields': len(REQUIRED),
                      'ordinary_owner_drained': True, 'preserved_files': len(before)}))


if __name__ == '__main__':
    main()
