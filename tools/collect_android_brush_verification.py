#!/usr/bin/env python3
"""Collect source-bound Robolectric NATIVE pixel evidence; never claim emulator, ART or iOS verification."""
import hashlib
import json
import platform
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    'retracedHighlighterCompositesOnceWithinTheStrokeAndTwiceAcrossStrokes',
    'pressureGeometryHasDifferentEndpointWidthsInNativePixels',
    'monolineDoesNotTurnInputPressureIntoNativeWidth',
    'reflectedFullAffineAndLargeOriginsUseTargetCoordinatesOnlyOnce',
    'nativePrepareRefusesAFloat64PlanRatherThanSilentlyQuantizingIt',
    'paintAndBitmapStateDoNotLeakAcrossPreparedStrokes',
    'allSixSpecificationGoldensMatchIndependentNativePathsAndProduceInspectablePngs',
}


def main():
    files = []
    for directory in ('canvas-core/src/commonMain', 'canvas-core/src/jvmAndAndroidMain', 'shared-core/src/commonMain'):
        files += list((ROOT/directory).rglob('*.kt'))
    for directory in ('android-app/src/main', 'android-app/src/test'):
        files += [p for p in (ROOT/directory).rglob('*') if p.is_file()]
    files += list((ROOT/'fixtures/brush').glob('*.json'))
    suites = list((ROOT/'android-app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    images = list((ROOT/'build/android-brush-images/testDebugUnitTest').glob('*.png'))
    files += suites + images + [ROOT/p for p in ('android-app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts',
        'gradle.properties', 'canvas-core/build.gradle.kts', 'shared-core/build.gradle.kts', 'tools/collect_android_brush_verification.py',
        '.android-tools/robolectric-sdk/android-all-instrumented-15-robolectric-13954326-i7.jar')]
    stamp = ROOT/'reports/android-brush-inputs.sha256'
    if not stamp.exists() or not suites:
        raise SystemExit('Run :android-app:verifyNativeBrush before collecting native pixel evidence.')
    expected = {line.split('  ', 1)[1]: line.split('  ', 1)[0] for line in stamp.read_text(encoding='utf-8').splitlines()}
    actual = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    if actual != expected:
        raise SystemExit('Native brush source/XML/images differ from Gradle verification stamp. Rerun verifyNativeBrush.')
    cases = []
    for path in suites:
        suite = ET.parse(path).getroot()
        assert suite.attrib['name'] == 'dev.notecore.app.NativeBrushRendererTest'
        assert all(int(suite.attrib.get(key, 0)) == 0 for key in ('failures', 'errors', 'skipped'))
        cases += [case.attrib['name'].split('[', 1)[0] for case in suite.findall('testcase')]
    assert len(cases) == len(EXPECTED) and set(cases) == EXPECTED
    golden = json.loads((ROOT/'fixtures/brush/brush-golden.json').read_text(encoding='utf-8'))
    assert {p.stem for p in images} == {case['id'] for case in golden['cases']}
    report = {
        'status': 'passed', 'backend': 'Robolectric 4.17 NATIVE / native Android Skia on host JVM',
        'host': {'os': platform.system(), 'architecture': platform.machine()}, 'android_api_model': 35,
        'test_count': len(cases), 'checks': sorted(cases), 'golden_cases': len(images),
        'float32_max_error_target_pixels': 1/1024,
        'single_stroke_alpha': 96, 'two_stroke_alpha': 156,
        'native_skia_offscreen_pixels_verified': True, 'emulator_art_verified': False,
        'ios_backend_compiled': False, 'cross_platform_pixels_verified': False,
        'candidate_two_enabled_in_app': False, 'device_performance_verified': False,
        'production_c1_gate_complete': False, 'source_xml_and_image_sha256': actual,
        'scope': 'Offscreen native Skia pixels and Android adapter compilation. Not emulator/ART, hardware Canvas, native touch, iOS, color-management equivalence or device performance.',
    }
    (ROOT/'reports/android-brush-native.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_xml_and_image_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
