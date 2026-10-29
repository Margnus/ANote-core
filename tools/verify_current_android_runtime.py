#!/usr/bin/env python3
"""Source-bound checks on the separately authorized NoteCoreCurrent35 AVD."""
import argparse
import hashlib
import io
import json
import re
import subprocess
import tarfile
import time
from pathlib import Path

from verify_android_input_host import CHECKS, STORAGE_CHECKS, OWNER_CHECKS

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'dev.notecore.preview'
TITLE_CHECKS = {
    'invalid_title_keeps_root_receipts_and_file',
    'same_operation_valid_retry_and_utf8_file_reopen',
    'invalid_title_keeps_redo_branch', 'invalid_initial_title_refused',
    'invalid_identity_keeps_root_status_and_file',
    'same_identity_operation_corrected_unicode_page_file_reopen',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    args = parser.parse_args()
    folder = ROOT / ('build/current-api35-' + time.strftime('%Y%m%d-%H%M%S'))
    folder.mkdir(parents=True)
    report = {'schema': 1, 'date': time.strftime('%Y-%m-%d'),
              'core_base': subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT,
                                          check=True, capture_output=True, text=True).stdout.strip(),
              'status': 'running', 'avd': 'NoteCoreCurrent35', 'serial': 'emulator-5590',
              'scope': 'New isolated API35 emulator, current APK; synthetic instrumentation only.',
              'physical_device_verified': False, 'apple_verified': False,
              'ordinary_ui_verified': False, 'cases': {}}

    def adb(*command):
        return subprocess.run([args.adb, '-s', report['serial'], *command], cwd=ROOT,
                              capture_output=True, check=True, timeout=180).stdout

    def files():
        exists = adb('shell', 'run-as', BUNDLE, 'sh', '-c',
                     "'if [ -d files/preview-notes ]; then echo present; fi'").strip()
        if exists != b'present':
            assert not exists, exists
            return {}
        raw = adb('exec-out', 'run-as', BUNDLE, 'tar', 'cf', '-', 'files/preview-notes')
        with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
            return {entry.name: sha(archive.extractfile(entry).read())
                    for entry in archive.getmembers() if entry.isfile()}

    try:
        assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name').decode().strip() == report['avd']
        assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == b'35'
        report['device_properties'] = adb('shell', 'getprop').decode()
        stamp = dict(line.split('  ', 1)[::-1] for line in
                     (ROOT / 'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
        assert 'android-app/src/androidTest/java/dev/notecore/app/CanonicalTitleHostChecks.kt' in stamp
        for path, expected in stamp.items():
            assert sha((ROOT / path).read_bytes()) == expected, ('stale build', path)
        report['source_and_build_input_sha256'] = stamp
        report['runner_sha256'] = sha(Path(__file__).read_bytes())
        installed = adb('shell', 'pm', 'list', 'packages', BUNDLE).decode().splitlines()
        before = files() if 'package:' + BUNDLE in installed else {}
        report['files_before'] = before
        for relative in ('android-app/build/outputs/apk/debug/android-app-debug.apk',
                         'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'):
            assert relative in stamp
            adb('install', '-r', str(ROOT / relative))
        remote = adb('shell', 'pm', 'path', BUNDLE).decode().strip()
        assert remote.startswith('package:') and '\n' not in remote, remote
        actual = adb('exec-out', 'cat', remote[len('package:'):])
        assert sha(actual) == stamp['android-app/build/outputs/apk/debug/android-app-debug.apk']
        report['installed_apk_sha256'] = sha(actual)
        for case, prefix, expected in (
                ('title_contract', 'title', TITLE_CHECKS),
                ('input_batches', 'input', CHECKS),
                ('storage_retention', 'storage', STORAGE_CHECKS),
                ('owner_retirement', 'owner', OWNER_CHECKS)):
            raw = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', case,
                      BUNDLE + '.test/dev.notecore.app.ViewportInstrumentation').decode('utf-8')
            (folder / (case + '.log')).write_text(raw, encoding='utf-8')
            assert prefix + '_status=passed' in raw and 'INSTRUMENTATION_CODE: -1' in raw, raw
            match = re.search(prefix + r'_observations=(\{.*\})', raw)
            assert match, raw
            observed = json.loads(match.group(1))
            assert len(observed['checks']) == len(expected) and set(observed['checks']) == expected, observed
            if case == 'input_batches':
                assert observed['callback_real_batch_limit'] == 1024 and observed['down_history_included']
                assert observed['canonical_max_error'] <= 1e-9 and observed['old_float_witness_error'] > 1e-5
                assert observed['batch_sample_count'] == 7 and observed['all_test_notes_in_memory']
                assert observed['active_input_reservation_bytes'] == 1_329_280
                assert observed['input_reservation_released_after_completion']
            if case in ('storage_retention', 'owner_retirement'):
                assert observed['original_note_files_used'] is False
            if case == 'storage_retention':
                assert observed['system_file_picker_verified'] is False
            if case == 'owner_retirement':
                assert observed['gc_timing_verified'] is False
                assert observed['abrupt_owner_or_thread_loss_verified'] is False
            report['cases'][case] = observed
            assert files() == before, ('ordinary files changed', case)
            print(case, 'passed', len(expected), flush=True)
        report['files_after'] = files()
        report['status'] = 'passed'
    except BaseException as error:
        report['status'] = 'failed'
        report['error'] = repr(error)
        raise
    finally:
        report['raw_evidence_sha256'] = {p.relative_to(ROOT).as_posix(): sha(p.read_bytes())
                                        for p in folder.glob('*.log')}
        report['raw_evidence_directory'] = folder.relative_to(ROOT).as_posix()
        (ROOT / 'reports/current-api35-runtime.json').write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
