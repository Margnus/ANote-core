#!/usr/bin/env python3
"""Actual Android offscreen path test; no window input or personal document access."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use an isolated emulator'
    fixture = ROOT/'fixtures/geometry/brush-paths.json'
    document = json.loads(fixture.read_text(encoding='utf-8'))
    for path, expected in document['source_sha256'].items():
        assert verification_digest(ROOT/path) == expected, 'Regenerate stale common fixture: '+path
    stamp = ROOT/'reports/android-brush-build-inputs.sha256'
    before = dict(line.split('  ', 1)[::-1] for line in stamp.read_text(encoding='utf-8').splitlines())
    def check_build():
        for path, sha in before.items():
            assert digest(ROOT/path) == sha, 'Stale Android test build: '+path
    check_build()
    apk = ROOT/'android-app/build/outputs/apk/debug/android-app-debug.apk'
    test = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    with zipfile.ZipFile(test) as archive:
        assert archive.read('assets/brush-paths.json') == fixture.read_bytes()
    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], cwd=ROOT,
            capture_output=True, text=True, check=True, timeout=120).stdout
    adb('install', '-r', str(apk)); adb('install', '-r', str(test))
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'brush_path',
                 'dev.notecore.preview.test/dev.notecore.app.ViewportInstrumentation')
    assert 'brush_path_status=passed' in output, output
    pixels = int(re.search(r'brush_path_pixels=(\d+)', output).group(1))
    assert pixels > 20000
    check_build()
    report = {'status': 'passed', 'platform': 'Android emulator ART / Bitmap Canvas Path',
        'serial': args.serial, 'api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
        'fixture_sha256': digest(fixture), 'cases': [c['name'] for c in document['cases']],
        'antialias_modes': [False, True], 'stable_pixel_checks': pixels, 'fill_rule': 'nonzero-once',
        'main_apk_sha256': digest(apk), 'test_apk_sha256': digest(test),
        'source_and_build_sha256': before, 'tool_sha256': digest(Path(__file__)),
        'desktop_unlock_required': False, 'real_touch_verified': False, 'physical_device_measured': False,
        'aa_boundary_equivalence_verified': False, 'app_brush_integration_verified': False,
        'scope': 'JVM-generated actual common contour output, real Android rasterizer stable coverage and alpha. Not Kotlin/ART runtime generation, AA edge equivalence, input, app integration or latency evidence.'}
    (ROOT/'reports/android-brush-paths.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_build_sha256'}))


if __name__ == '__main__':
    main()
