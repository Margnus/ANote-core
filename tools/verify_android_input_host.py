#!/usr/bin/env python3
"""Run source-bound original Android View input callbacks with native synthetic MotionEvents."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'dev.notecore.preview'
CHECKS = {'double_scene_mapping_before_float_path', 'history_terminal_order_equal_time_and_undo',
          'invalid_native_sample_cancels_and_recovers', 'resize_cancels_and_late_up_preserves_root',
          'callback_batch_limit_explicit_cancel_and_recover', 'document_capacity_refusal_and_undo_recovery',
          'live_byte_refusal_and_undo_recovery', 'input_byte_budget_refusal_cancel_and_recovery',
          'history_capacity_refuses_down_without_owning_samples', 'journal_identity_capacity_refuses_before_real_view_ownership'}
STORAGE_CHECKS = {'read_failure_classification_to_owner', 'validated_creation_refusal_and_independent_authority', 'pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen', 'validated_open_refusal_preserves_original_store_owner', 'saved_unchanged_skips_io', 'changed_revision_failure_retained', 'repair_retries_exact_root',
                  'single_export_snapshot_preserves_original', 'reopened_saved_marker_skips_stopped_backend',
                  'foreign_same_revision_is_not_acknowledged_as_saved'}
OWNER_CHECKS = {'wrong_thread_retirement_rejected', 'accepted_root_saved_before_terminal_worker_stop',
                'failed_root_retained_until_explicit_retry', 'active_input_and_picker_refuse_without_loss',
                'pending_read_and_delivery_refuse_retirement', 'full_ledger_retry_frees_slot_before_retirement'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--avd', choices=('NoteCorePreview', 'NoteCoreWindows'), default='NoteCorePreview')
    args = parser.parse_args()
    assert args.serial in ('emulator-5580', 'emulator-5554'), 'Only the existing dedicated emulator is authorized'
    folder = ROOT/'build/android-input-host'; folder.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], cwd=ROOT,
                              check=True, capture_output=True, timeout=120).stdout

    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name').decode('utf-8').strip() == args.avd

    def files():
        output = adb('shell', 'run-as', BUNDLE, 'ls', 'files/preview-notes').decode('utf-8')
        result = {}
        for name in output.splitlines():
            if not re.fullmatch(r'[a-zA-Z0-9-]+\.ncp', name):
                continue
            relative = 'files/preview-notes/' + name
            result[relative] = hashlib.sha256(
                adb('exec-out', 'run-as', BUNDLE, 'cat', relative)).hexdigest()
        return result

    before = files()
    build = json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))
    assert build['status'] == 'passed'
    apk = ROOT/build['android']['artifact']
    assert digest(apk) == build['android']['sha256']
    stamp = dict(line.split('  ', 1)[::-1] for line in
                 (ROOT/'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    bindings = {**build['source_and_build_input_sha256'], **stamp}
    assert 'android-app/src/androidTest/java/dev/notecore/app/AndroidInputHostChecks.kt' in stamp
    for path, sha in bindings.items():
        assert digest(ROOT/path) == sha, ('stale input host build', path)
    test = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install', '-r', str(apk)); adb('install', '-r', str(test))
    installed = [adb('shell', 'pm', 'path', package).decode('utf-8').strip().removeprefix('package:')
                 for package in (BUNDLE, BUNDLE+'.test')]
    for remote, local in zip(installed, (apk, test)):
        assert re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk', remote)
        assert hashlib.sha256(adb('exec-out', 'cat', remote)).hexdigest() == digest(local)
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'input_batches',
                 BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation').decode('utf-8')
    log = folder/'run.log'; log.write_text(output, encoding='utf-8', newline='\n')
    assert 'input_status=passed' in output and 'INSTRUMENTATION_CODE: -1' in output, output
    observed = json.loads(re.search(r'input_observations=(\{.*\})', output).group(1))
    assert len(observed['checks']) == len(CHECKS) and set(observed['checks']) == CHECKS
    assert observed['callback_real_batch_limit'] == 1024 and observed['down_history_included'] is True
    assert observed['canonical_max_error'] <= 1e-9 and observed['old_float_witness_error'] > 1e-5
    assert observed['batch_sample_count'] == 7 and observed['all_test_notes_in_memory']
    assert observed['active_input_reservation_bytes'] == 1_130_624 and observed['input_reservation_released_after_completion']
    storage_output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'storage_retention',
                         BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation').decode('utf-8')
    storage_log = folder/'storage-retention.log'; storage_log.write_text(storage_output, encoding='utf-8', newline='\n')
    assert 'storage_status=passed' in storage_output and 'INSTRUMENTATION_CODE: -1' in storage_output, storage_output
    storage = json.loads(re.search(r'storage_observations=(\{.*\})', storage_output).group(1))
    assert len(storage['checks']) == len(STORAGE_CHECKS) and set(storage['checks']) == STORAGE_CHECKS
    assert storage['original_note_files_used'] is False and storage['system_file_picker_verified'] is False
    owner_output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'owner_retirement',
                       BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation').decode('utf-8')
    owner_log = folder/'owner-retirement.log'; owner_log.write_text(owner_output, encoding='utf-8', newline='\n')
    assert 'owner_status=passed' in owner_output and 'INSTRUMENTATION_CODE: -1' in owner_output, owner_output
    owner = json.loads(re.search(r'owner_observations=(\{.*\})', owner_output).group(1))
    assert len(owner['checks']) == len(OWNER_CHECKS) and set(owner['checks']) == OWNER_CHECKS
    assert owner['original_note_files_used'] is False and owner['gc_timing_verified'] is False
    assert owner['abrupt_owner_or_thread_loss_verified'] is False
    assert files() == before, 'Original Android file set or bytes changed'
    baseline = folder/'original-files.json'
    baseline.write_text(json.dumps(before, indent=2)+'\n', encoding='utf-8', newline='\n')
    adb('shell', 'am', 'force-stop', BUNDLE)
    restored = adb('shell', 'am', 'start', '-W', '-n', BUNDLE+'/dev.notecore.app.MainActivity').decode('utf-8')
    assert 'Status: ok' in restored
    assert files() == before, 'Original Android files changed after ordinary process restore'
    pid = adb('shell', 'pidof', BUNDLE).decode('utf-8').strip()
    assert re.fullmatch(r'\d+', pid)
    for path in (apk, test, log, storage_log, owner_log, baseline, Path(__file__), ROOT/'reports/preview-build.json',
                 ROOT/'reports/android-brush-build-inputs.sha256'):
        bindings[path.relative_to(ROOT).as_posix()] = digest(path)
    sdk = Path(args.adb).resolve().parent.parent
    contracts = [sdk/'sources/android-36/android/view'/name for name in ('MotionEvent.java', 'View.java')]
    report = {
        'schema': 'android-input-host-1', 'status': 'passed', 'serial': args.serial,
        'api': adb('shell', 'getprop', 'ro.build.version.sdk').decode('utf-8').strip(),
        'checks': sorted(CHECKS), 'check_count': len(CHECKS), 'observations': observed,
        'storage_retention_checks': storage, 'storage_retention_check_count': len(STORAGE_CHECKS),
        'owner_retirement_checks': owner, 'owner_retirement_check_count': len(OWNER_CHECKS),
        'apk_sha256': digest(apk), 'test_apk_sha256': digest(test),
        'ordinary_pid_after_host': int(pid), 'original_file_count': len(before),
        'all_original_files_unchanged': True, 'ordinary_process_restored': True,
        'source_and_artifact_sha256': bindings,
        'sdk_contract_sha256': {str(path): digest(path) for path in contracts},
        'scope': 'Synthetic native MotionEvent history/terminal batches on the original InkCanvasView, six original NoteStore/worker file/export retention checks and six explicit Store retirement checks in unique cache directories. Input oracle uses SDK event scalars and double scene mapping. No injected OS events, ordinary UI, system file picker, GC timing, abrupt owner loss, physical stylus or power-loss evidence.',
        'decision': 'Use double viewport scale/offset for canonical input; float conversion is reserved for derived Path coordinates. Keep current owner, immutable real samples, explicit invalid/cancel behavior and MONOLINE scope.',
        'ordinary_ui_interaction_verified': False, 'real_stylus_verified': False,
        'physical_device_or_heap_measured': False, 'runtime_source_equivalence_verified': False,
        'c1_gate_complete': False,
    }
    (ROOT/'reports/android-input-host-tests.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: report[key] for key in ('status', 'check_count', 'observations', 'storage_retention_check_count', 'owner_retirement_check_count', 'original_file_count', 'ordinary_pid_after_host')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
