#!/usr/bin/env python3
"""Run common geometry/index/mask tests on ART in an isolated library-test APK, without UI."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    args = parser.parse_args()
    assert args.serial.startswith('emulator-'), 'Use a dedicated emulator'
    sources = sorted([*ROOT.glob('canvas-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/commonTest/**/*.kt'),
        *ROOT.glob('canvas-core/src/jvmAndAndroidMain/**/*.kt'), *ROOT.glob('canvas-core/src/jvmAndAndroidTest/**/*.kt'), *ROOT.glob('canvas-core/src/androidMain/**/*.kt'), *ROOT.glob('canvas-core/src/androidInstrumentedTest/**/*.kt'),
        ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'canvas-core/build.gradle.kts', ROOT/'gradle.properties', Path(__file__)])
    before = {p.relative_to(ROOT).as_posix(): digest(p) for p in sources}
    build = ROOT/'canvas-core/build/android-brush-tests'
    build.mkdir(parents=True, exist_ok=True)
    with (build/'build.log').open('w', encoding='utf-8') as log:
        subprocess.run([str(ROOT/'gradlew'), '--no-daemon', '--console=plain', '-PenableAndroidTargets=true',
            ':canvas-core:assembleDebugAndroidTest'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
    metadata = ROOT/'canvas-core/build/outputs/apk/androidTest/debug/output-metadata.json'
    info = json.loads(metadata.read_text(encoding='utf-8'))
    assert info['applicationId'] == 'dev.notecore.canvas.poc.test' and len(info['elements']) == 1
    apk = metadata.parent/info['elements'][0]['outputFile']
    apk_sha = digest(apk)
    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], cwd=ROOT, capture_output=True,
            text=True, check=True, timeout=120).stdout
    adb('install', '-r', str(apk))
    output = adb('shell', 'am', 'instrument', '-w', '-r',
        'dev.notecore.canvas.poc.test/dev.notecore.canvas.prototype.CoreBrushInstrumentation')
    (build/'run.log').write_text(output, encoding='utf-8')
    assert 'common_brush_status=passed' in output and 'common_brush_tests=91' in output and 'common_brush_ignored=0' in output, output
    assert before == {p.relative_to(ROOT).as_posix(): digest(p) for p in sources} and digest(apk) == apk_sha
    observations = json.loads(re.search(r'native_image_observations=(\[.*\])', output).group(1))
    assert len(observations) == 3
    trace = json.loads(re.search(r'render_viewport_trace=(\{.*\})', output).group(1))
    assert trace['trace_id'] == 'dev034-fixed-viewport-v1' and trace['closed_drained']
    report = {'render_viewport_trace': trace, 'native_image_observations': observations, 'status': 'passed', 'platform': 'Android emulator ART standalone canvas-core test APK',
        'serial': args.serial, 'api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
        'test_count': 91, 'viewport_trace_test_count': 1, 'android_image_holder_test_count': 7, 'common_test_count': 72, 'real_render_executor_test_count': 6, 'android_bitmap_test_count': 5, 'test_groups': ['BrushEnvelopePocTest', 'BrushPathPocTest', 'BrushReductionPocTest', 'ChunkedBrushPocTest', 'BrushTileCachePocTest', 'ResourceLeaseContractTest', 'WeightedLruContractTest', 'BrushTilePixelsPocTest', 'BrushTileBitmapTest', 'BrushRenderOwnerPocTest', 'BrushRenderExecutorPocTest', 'AndroidBrushImagePocTest', 'RenderViewportTraceTest'],
        'source_and_build_sha256': before, 'test_apk': apk.relative_to(ROOT).as_posix(), 'test_apk_sha256': apk_sha,
        'test_output_sha256': digest(build/'run.log'), 'desktop_unlock_required': False,
        'native_image_lease_handoff_verified': True, 'android_image_holder_verified': True, 'real_bounded_executor_render_handoff_verified': True, 'shared_geometry_and_chunk_mask_on_art_verified': True, 'native_canvas_mask_image_consumption_verified': True, 'native_brush_path_rasterizer_verified': False,
        'physical_device_measured': False, 'app_brush_integration_verified': False,
        'native_cost_scope': 'Bitmap allocationByteCount and one exploratory API timing. Includes temporary RGBA ByteBuffer copy; no process peak heap, GPU, GC or delayed reclamation measurement. Release time is recycle API return.',
        'scope': 'Real ART execution of common geometry/reduction/chunk index/subpixel mask tests in an isolated test-only library APK. Includes actual Android Bitmap/Canvas premultiplication, color/alpha and nearest and filtered tile seam checks with true neighbor gutter and disjoint device-pixel core clips. Includes Bitmap allocationByteCount observations; no whole-process heap, GPU, arbitrary transform, app UI, touch or device performance claim.'}
    (ROOT/'reports/android-brush-common-tests.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_build_sha256'}))


if __name__ == '__main__':
    main()
