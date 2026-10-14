#!/usr/bin/env python3
"""Run synthetic multi-pointer events through a dedicated emulator's Android window."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'): raise SystemExit('Requires a dedicated emulator')
    def adb(*command):
        result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, text=True, timeout=120)
        if result.returncode: raise RuntimeError(result.stderr)
        return result.stdout
    apk = ROOT/'dist/NoteCore-0.1.0-preview.apk'
    test = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install', '-r', str(apk)); adb('install', '-r', str(test))
    output = adb('shell', 'am', 'instrument', '-w', '-r', 'dev.notecore.preview.test/dev.notecore.app.ViewportInstrumentation')
    required = ['pinch_and_inverse_mapping', 'stylus_transform_lock', 'finger_pan_without_ink', 'executor_owner_worker', 'document_delta_history_owner', 'storage_failure_recovery', 'storage_bounded_read_and_active_owner', 'storage_atomic_rename_failure', 'catalog_visible_row_recycling', 'viewport_status']
    for key in required:
        if f'{key}=passed' not in output: raise AssertionError(output)
    inputs = sorted([*ROOT.glob('android-app/src/androidTest/**/*.kt'), *ROOT.glob('android-app/src/main/**/*.kt'),
                     *ROOT.glob('shared-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/jvmAndAndroidMain/**/*.kt'), ROOT/'android-app/build.gradle.kts', ROOT/'shared-core/build.gradle.kts', test])
    report = {'status': 'passed', 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'serial': args.serial, 'api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
              'checks': required[:-1], 'event_source': 'synthetic MotionEvent injected through UiAutomation into window',
              'executor_thread_source': 'real Android ART owner/worker threads, bounded result delivery and explicit retry',
              'document_history_source': 'real ART document authority and owner/worker guard, last-page undo/redo, monotonic object epoch and immutable sample sharing; not paged App UI verification',
              'storage_test_source': 'real AtomicFile directory and silent-rename failure / retained reopen / repaired retry / strict UTF-8 and 32MiB preflight, isolated synthetic cache',
              'catalog_test_source': '1000 synthetic rows in a real ListView window, bounded widget creation and recycled-row ID rebinding; not a latency benchmark',
              'real_stylus_verified': False, 'device_performance_verified': False,
              'source_and_test_apk_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}}
    (ROOT/'reports/android-viewport-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_test_apk_sha256'}, ensure_ascii=False))

if __name__ == '__main__': main()
