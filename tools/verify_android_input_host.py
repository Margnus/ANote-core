#!/usr/bin/env python3
"""Run source-bound original Android View input callbacks with native synthetic MotionEvents."""
import argparse
import hashlib
import json
import re
import subprocess
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'dev.notecore.preview'
CHECKS = {'double_scene_mapping_before_float_path', 'history_terminal_order_equal_time_and_undo',
          'invalid_native_sample_cancels_and_recovers', 'resize_cancels_and_late_up_preserves_root',
          'callback_batch_limit_explicit_cancel_and_recover', 'document_capacity_refusal_and_undo_recovery',
          'live_byte_refusal_and_undo_recovery', 'input_byte_budget_refusal_cancel_and_recovery',
          'history_capacity_refuses_down_without_owning_samples', 'journal_identity_capacity_refuses_before_real_view_ownership'}
STORAGE_CHECKS = {'derived_text_capacity_checkpoint_save_and_reopen', 'read_failure_classification_to_owner', 'validated_creation_refusal_and_independent_authority', 'pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen', 'validated_open_refusal_preserves_original_store_owner', 'saved_unchanged_skips_io', 'changed_revision_failure_retained', 'repair_retries_exact_root',
                  'single_export_snapshot_preserves_original', 'reopened_saved_marker_skips_stopped_backend',
                  'foreign_same_revision_is_not_acknowledged_as_saved'}
OWNER_CHECKS = {'invalid_store_configuration_does_not_start_worker', 'wrong_thread_store_creation_and_lookup_refused', 'unadmitted_release_refuses_before_retiring_authority', 'wrong_thread_retirement_rejected', 'accepted_root_saved_before_terminal_worker_stop',
                'failed_root_retained_until_explicit_retry', 'active_input_and_picker_refuse_without_loss',
                'pending_read_and_delivery_refuse_retirement', 'full_ledger_retry_frees_slot_before_retirement'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()



def process_checkpoint(args, adb, folder, files, before, bindings):
    """Stop only isolated test processes after observing original worker bytes; separate confirmed and undelivered owner results."""
    assert args.path_api32 and args.serial == 'emulator-5586' and args.avd == 'NoteCorePath32'
    scope = str(uuid.uuid4())
    local = folder / ('process-checkpoint-' + scope)
    local.mkdir()
    remote = 'cache/process-recovery-' + scope
    document = remote + '/document/process-recovery.ncp'
    killed = []
    def stop_stage(phase, marker, revision, strokes, pending, wait_visible=False):
        log = local / (phase + '.log')
        with log.open('wb') as output:
            process = subprocess.Popen([args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'notecore_case', 'process_checkpoint_' + phase, '-e', 'process_checkpoint_scope', scope,
                BUNDLE + '.test/dev.notecore.app.ViewportInstrumentation'], cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                ready = None
                while ready is None and time.monotonic() < deadline:
                    assert process.poll() is None, ('checkpoint host ended before readiness', log.read_text(encoding='utf-8'))
                    try:
                        ready = json.loads(adb('exec-out', 'run-as', BUNDLE, 'cat', remote + '/' + marker).decode('utf-8'))
                    except (subprocess.CalledProcessError, json.JSONDecodeError):
                        time.sleep(.1)
                assert ready is not None, 'Checkpoint host did not confirm readiness'
                assert ready['scope'] == scope and ready['owner_not_retired'] and ready['worker_not_stopped']
                assert ready['pending'] == pending
                assert ready['stroke_count'] == strokes
                assert ready['revision'] == revision
                pid = adb('shell', 'pidof', BUNDLE).decode('utf-8').strip()
                assert pid == str(ready['pid']) and process.poll() is None
                assert not killed or ready['pid'] != killed[-1]['pid']
                if wait_visible:
                    assert ready['save_callback_count'] == 0 and ready['ledger_in_flight'] and ready['owner_callback_blocked']
                while True:
                    try:
                        data = adb('exec-out', 'run-as', BUNDLE, 'cat', document)
                    except subprocess.CalledProcessError:
                        assert wait_visible and time.monotonic() < deadline and process.poll() is None, 'Worker file unavailable'
                        time.sleep(.1)
                        continue
                    if hashlib.sha256(data).hexdigest() == ready['document_sha256']:
                        break
                    assert wait_visible and time.monotonic() < deadline and process.poll() is None, 'Expected worker bytes not visible'
                    time.sleep(.1)
                assert process.poll() is None and adb('shell','pidof',BUNDLE).decode('utf-8').strip() == str(ready['pid'])
                (local / marker).write_text(json.dumps(ready, indent=2)+'\n', encoding='utf-8', newline='\n')
                (local / (phase + '.ncp')).write_bytes(data)
                assert files() == before
                adb('shell', 'am', 'force-stop', BUNDLE)
                remaining = subprocess.run([args.adb, '-s', args.serial, 'shell', 'pidof', BUNDLE],
                    cwd=ROOT, capture_output=True, timeout=30)
                assert remaining.returncode == 1 and not remaining.stdout.strip(), 'Old owner process survived force-stop'
                process.wait(timeout=30)
                assert adb('exec-out', 'run-as', BUNDLE, 'cat', document) == data
                killed.append({**ready, 'instrumentation_command_returncode':process.returncode,
                    'process_absent_after_force_stop':True, 'file_unchanged_after_kill':True,
                    'owner_confirmation_received':not wait_visible})
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
    stop_stage('prepare','checkpoint-ready.json',1,0,True)
    stop_stage('resume','committed-ready.json',2,1,False)
    raw = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'process_checkpoint_verify',
        '-e', 'process_checkpoint_scope', scope, BUNDLE + '.test/dev.notecore.app.ViewportInstrumentation').decode('utf-8')
    (local / 'verify.log').write_text(raw, encoding='utf-8', newline='\n')
    assert 'process_checkpoint_status=passed' in raw and 'INSTRUMENTATION_CODE: -1' in raw, raw
    observed = json.loads(re.search(r'process_checkpoint_observations=(\{.*\})', raw).group(1))
    assert observed['pid'] not in [entry['pid'] for entry in killed]
    assert observed['scope'] == scope and observed['canonical_sha256'] == killed[-1]['document_sha256']
    assert observed['stroke_count'] == 1 and observed['point_count'] == 3 and observed['revision'] == 2 and not observed['pending']
    assert observed['checkpoint_worker_write_verified'] and observed['repeated_commit_did_not_duplicate']
    assert not observed['unconfirmed_io_kill_verified'] and not observed['power_loss_verified']
    final = adb('exec-out', 'run-as', BUNDLE, 'cat', document)
    assert hashlib.sha256(final).hexdigest() == observed['canonical_sha256']
    (local / 'confirmed-final.ncp').write_bytes(final)
    assert files() == before
    result = {'scope_id':scope, 'killed_processes':list(killed), 'final_reopen':observed,
        'ordinary_ui_verified':False, 'native_pool_or_unsignaled_receipt_verified':False,
        'scope':'Confirmed-write subcase: original isolated Store/worker wrote and confirmed a pending checkpoint before full process force-stop. New PID reopened, committed once and confirmed canonical replacement before a second force-stop. Third PID reopened exact canonical bytes. Synthetic input/owner fault; no manually seeded checkpoint, partial-write kill, power loss or ordinary UI claim. The separate undelivered_completion subcase records visible worker bytes before owner delivery.'}
    stop_stage('undelivered','undelivered-ready.json',3,2,False,wait_visible=True)
    raw = adb('shell','am','instrument','-w','-r','-e','notecore_case','process_checkpoint_verify_undelivered',
        '-e','process_checkpoint_scope',scope,BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation').decode('utf-8')
    (local/'verify-undelivered.log').write_text(raw,encoding='utf-8',newline='\n')
    assert 'process_checkpoint_status=passed' in raw and 'INSTRUMENTATION_CODE: -1' in raw, raw
    resumed = json.loads(re.search(r'process_checkpoint_observations=(\{.*\})',raw).group(1))
    assert resumed['pid'] not in [entry['pid'] for entry in killed] and resumed['scope']==scope
    assert resumed['canonical_sha256']==killed[-1]['document_sha256'] and resumed['owner_delivery_absent_before_kill']
    assert resumed['revision']==3 and resumed['stroke_count']==2 and resumed['point_count']==5 and not resumed['pending']
    assert not resumed['partial_write_kill_verified'] and not resumed['power_loss_verified']
    final=adb('exec-out','run-as',BUNDLE,'cat',document)
    assert hashlib.sha256(final).hexdigest()==resumed['canonical_sha256'] and files()==before
    (local/'final.ncp').write_bytes(final)
    result['undelivered_completion']={'killed_process':killed[-1],'reopen':resumed,
        'scope':'Original worker new canonical bytes were visible while the main owner was blocked inside its save action with zero callbacks and an in-flight ledger. Full process force-stop before owner delivery; new PID reopened the exact two-stroke canonical root. Not a partial-write or power-loss test.'}
    (local / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    for path in local.iterdir():
        bindings[path.relative_to(ROOT).as_posix()] = digest(path)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--avd', choices=('NoteCorePreview', 'NoteCoreWindows', 'NoteCoreMin26', 'NoteCorePath32'), default='NoteCorePreview')
    parser.add_argument('--minimum-api', action='store_true')
    parser.add_argument('--path-api32', action='store_true')
    args = parser.parse_args()
    assert not (args.minimum_api and args.path_api32), 'Select one OS endpoint'
    fallback = args.minimum_api or args.path_api32
    assert (args.minimum_api and args.serial == 'emulator-5582' and args.avd == 'NoteCoreMin26') or (args.path_api32 and args.serial == 'emulator-5586' and args.avd == 'NoteCorePath32') or (not fallback and args.serial in ('emulator-5580', 'emulator-5554') and args.avd in ('NoteCorePreview', 'NoteCoreWindows')), 'Only the dedicated emulator for this OS is authorized'
    folder = ROOT/('build/android-path-api32-host' if args.path_api32 else 'build/android-min-api-host' if args.minimum_api else 'build/android-input-host'); folder.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], cwd=ROOT,
                              check=True, capture_output=True, timeout=120).stdout

    assert adb('shell', 'getprop', 'ro.kernel.qemu.avd_name' if args.minimum_api else 'ro.boot.qemu.avd_name').decode('utf-8').strip() == args.avd

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
    if fallback:
        assert adb('shell','getprop','ro.build.version.sdk').decode().strip() == ('32' if args.path_api32 else '26')
    build = json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))
    assert build['status'] == 'passed'
    apk = ROOT/build['android']['artifact']
    assert digest(apk) == build['android']['sha256']
    stamp = dict(line.split('  ', 1)[::-1] for line in
                 (ROOT/'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    bindings = {**build['source_and_build_input_sha256'], **stamp}
    bindings[Path(__file__).relative_to(ROOT).as_posix()] = digest(Path(__file__))
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
    assert observed['active_input_reservation_bytes'] == 1_329_280 and observed['input_reservation_released_after_completion']
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
    minimum = None
    minimum_environment = None
    if fallback:
        api = 32 if args.path_api32 else 26
        image_bytes = 1538792859 if args.path_api32 else 474178332
        image_sha1 = '1b52794699c6889a2fd155bf235b67a592eb8f1d' if args.path_api32 else '432f149c048bffce7f9de526ec65b336daf7a0a3'
        tag = 'google_apis' if args.path_api32 else 'android'
        archive=ROOT.parent/('.codex/android-host/api32/x86_64-32_r08.zip' if args.path_api32 else '.codex/android-host/api26/x86_64-26_r01.zip')
        assert archive.stat().st_size == image_bytes
        with archive.open('rb') as stream:
            assert hashlib.file_digest(stream,'sha1').hexdigest() == image_sha1
        metadata=ROOT/f'build/api{api}-support/repository.xml'
        bindings[metadata.relative_to(ROOT).as_posix()] = digest(metadata)
        minimum_environment={'image_package':f'system-images;android-{api};'+('google_apis' if args.path_api32 else 'default')+';x86_64','image_revision':8 if args.path_api32 else 1,
            'official_image_bytes':image_bytes,'official_image_sha1':image_sha1,
            'image_archive_sha256':digest(archive),'repository_url':f'https://dl.google.com/android/repository/sys-img/{tag}/sys-img2-3.xml',
            'build_fingerprint':adb('shell','getprop','ro.build.fingerprint').decode().strip(),'isolated_avd':args.avd}
        raw = adb('shell','am','instrument','-w','-r','-e','notecore_case','minimum_api',BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation').decode('utf-8')
        path = folder/'minimum-api.log'; path.write_text(raw,encoding='utf-8',newline='\n')
        assert 'minimum_api_status=passed' in raw and 'INSTRUMENTATION_CODE: -1' in raw, raw
        minimum = json.loads(re.search(r'minimum_api_observations=(\{.*\})',raw).group(1))
        assert minimum['api'] == api
        assert minimum['line_dot_undo_redo_page_pixels'] and minimum['surface_render_bytes']==0
        bindings[path.relative_to(ROOT).as_posix()] = digest(path)
        assert files() == before
        adb('shell','am','force-stop',BUNDLE)
        assert 'Status: ok' in adb('shell','am','start','-W','-n',BUNDLE+'/dev.notecore.app.MainActivity').decode('utf-8')
        pid = adb('shell','pidof',BUNDLE).decode().strip()
        assert re.fullmatch(r'\d+',pid) and files()==before
    checkpoint_process = process_checkpoint(args, adb, folder, files, before, bindings) if args.path_api32 else None
    if checkpoint_process is not None:
        adb('shell','am','force-stop',BUNDLE)
        assert 'Status: ok' in adb('shell','am','start','-W','-n',BUNDLE+'/dev.notecore.app.MainActivity').decode('utf-8')
        pid = adb('shell','pidof',BUNDLE).decode('utf-8').strip()
        assert re.fullmatch(r'\d+',pid) and files()==before
    sdk = Path(args.adb).resolve().parent.parent
    contracts = [sdk/'sources/android-36/android/view'/name for name in ('MotionEvent.java', 'View.java')]
    report = {
        'confirmed_checkpoint_process_recovery':checkpoint_process,
        'minimum_api_environment':minimum_environment, 'minimum_api_fallback': minimum, 'schema': 'android-input-host-1', 'status': 'passed', 'serial': args.serial,
        'api': adb('shell', 'getprop', 'ro.build.version.sdk').decode('utf-8').strip(),
        'checks': sorted(CHECKS), 'check_count': len(CHECKS), 'observations': observed,
        'storage_retention_checks': storage, 'storage_retention_check_count': len(STORAGE_CHECKS),
        'owner_retirement_checks': owner, 'owner_retirement_check_count': len(OWNER_CHECKS),
        'apk_sha256': digest(apk), 'test_apk_sha256': digest(test),
        'ordinary_pid_after_host': int(pid), 'original_file_count': len(before),
        'all_original_files_unchanged': True, 'ordinary_process_restored': True,
        'source_and_artifact_sha256': bindings,
        'sdk_contract_sha256': {str(path): digest(path) for path in contracts},
        'scope': f'Synthetic native MotionEvent history/terminal batches on the original InkCanvasView, {len(STORAGE_CHECKS)} original NoteStore/worker file/export retention checks including long-numeric recovery and full canonical reopen, and {len(OWNER_CHECKS)} explicit Store retirement checks in unique cache directories. Input oracle uses SDK event scalars and double scene mapping. No injected OS events, ordinary UI, system file picker, GC timing, arbitrary uncheckpointed owner loss, physical stylus or power-loss evidence. Confirmed-checkpoint process-stop coverage, when present, is recorded separately below.',
        'decision': 'Use double viewport scale/offset for canonical input; float conversion is reserved for derived Path coordinates. Keep current owner, immutable real samples, explicit invalid/cancel behavior and MONOLINE scope.',
        'ordinary_ui_interaction_verified': False, 'real_stylus_verified': False,
        'physical_device_or_heap_measured': False, 'runtime_source_equivalence_verified': False,
        'c1_gate_complete': False,
    }
    (ROOT/('reports/android-path-api32-host-tests.json' if args.path_api32 else 'reports/android-min-api-host-tests.json' if args.minimum_api else 'reports/android-input-host-tests.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: report[key] for key in ('status', 'check_count', 'observations', 'storage_retention_check_count', 'owner_retirement_check_count', 'original_file_count', 'ordinary_pid_after_host')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
