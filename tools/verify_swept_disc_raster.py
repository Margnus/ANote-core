#!/usr/bin/env python3
"""Isolated C1 geometry consumer probe; never enables the candidate in the App or SDK."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / 'tools/probes/swept-disc-raster'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    parser.add_argument('--check-comparator', action='store_true')
    parser.add_argument('--image', type=Path, default=ROOT / 'build/api26-probe/image/arm64-v8a')
    args = parser.parse_args()
    if args.check_comparator:
        check_comparator()
        return
    if args.output is None: parser.error('--output is required for a native run')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    raw = out / 'raw'; raw.mkdir()
    generated = out / 'generated'; generated.mkdir()
    env = dict(os.environ, JAVA_HOME=subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip(),
               GRADLE_USER_HOME=str(ROOT / '.gradle-user-home'), KONAN_DATA_DIR=str(ROOT / '.konan'))
    def run(label, command, timeout=120, allowed=(0,), environment=None):
        result = subprocess.run(list(map(str, command)), cwd=ROOT, env=environment or env, capture_output=True, timeout=timeout)
        (raw / (label + '.stdout')).write_bytes(result.stdout)
        (raw / (label + '.stderr')).write_bytes(result.stderr)
        if result.returncode not in allowed:
            raise RuntimeError((label, result.returncode, result.stdout[-2500:], result.stderr[-2500:]))
        return result.stdout
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    android_source = ROOT / 'android-app/src/main/java/dev/notecore/app/NativeBrushRenderer.kt'
    shutil.copyfile(android_source, generated / 'NativeBrushRenderer.kt')
    swift_source = ROOT / 'ios-app/NoteCorePreview/NativeInkCanvas.swift'
    original = swift_source.read_text(encoding='utf-8')
    fragment = original[original.index('final class NativeBrushRenderer {'):original.index('\nfinal class InkUIView:')]
    (generated / 'NativeBrushRenderer.swift').write_text('import UIKit\nimport NoteCanvasPrototype\n\n' + fragment, encoding='utf-8')
    shutil.copyfile(PROBE / 'AppleRasterProbe.swift', generated / 'main.swift')
    init = generated / 'isolate.gradle'
    init.write_text('''gradle.beforeProject { p ->
    def segment = p.path == ':' ? 'root' : p.path.substring(1).replace(':', '/')
    p.layout.buildDirectory.set(new File(%s, 'artifacts/' + segment))
    if (p.path == ':canvas-core') p.afterEvaluate {
        def sets = p.extensions.getByName('kotlin').sourceSets
        sets.getByName('commonMain').kotlin.srcDir(new File(p.rootDir, 'tools/probes/swept-disc-raster/common'))
        if (p.findProperty('enableAndroidTargets') == 'true') {
            sets.getByName('androidInstrumentedTest').kotlin.srcDir(new File(p.rootDir, 'tools/probes/swept-disc-raster/android'))
            sets.getByName('androidInstrumentedTest').kotlin.srcDir(new File(%s))
            p.extensions.getByName('android').defaultConfig.testInstrumentationRunner = 'dev.notecore.canvas.prototype.SweptDiscRasterInstrumentation'
        }
    }
}
''' % (json.dumps(str(out)), json.dumps(str(generated))), encoding='utf-8')
    gradle = [ROOT / 'gradlew', '--offline', '--no-daemon', '--init-script', init]
    run('apple-build', [*gradle, '-PenableAppleTargets=true', '-Pkotlin.native.home=' + str(ROOT / '.konan/kotlin-native-prebuilt-macos-aarch64-2.2.21'),
                        ':canvas-core:linkDebugFrameworkIosSimulatorArm64'], timeout=300)
    framework = out / 'artifacts/canvas-core/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework'
    binary = out / 'apple-probe'
    run('swift-build', ['xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios17.0-simulator',
                       '-F', framework.parent, '-module-cache-path', out / 'modules', generated / 'NativeBrushRenderer.swift',
                       generated / 'main.swift', '-o', binary], timeout=180)
    before = json.loads(run('devices-before', ['xcrun', 'simctl', 'list', 'devices', '-j']))
    device = run('create', ['xcrun', 'simctl', 'create', 'NoteCore-Raster-' + uuid.uuid4().hex,
                           'com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro', 'com.apple.CoreSimulator.SimRuntime.iOS-27-0']).decode().strip()
    apple = out / 'apple'; android = out / 'android'; android.mkdir()
    try:
        run('boot', ['xcrun', 'simctl', 'boot', device])
        run('bootstatus', ['xcrun', 'simctl', 'bootstatus', device, '-b'], timeout=180)
        run('apple-probe', ['xcrun', 'simctl', 'spawn', device, binary, apple])
    finally:
        try: run('shutdown', ['xcrun', 'simctl', 'shutdown', device], allowed=(0, 1, 149))
        finally: run('delete', ['xcrun', 'simctl', 'delete', device])
    after = json.loads(run('devices-after', ['xcrun', 'simctl', 'list', 'devices', '-j']))
    assert before == after
    run('android-build', [*gradle, '-PenableAndroidTargets=true', ':canvas-core:assembleDebugAndroidTest'], timeout=300)
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    adb = sdk / 'platform-tools/adb'; serial = 'emulator-5582'
    assert serial not in run('adb-before', [adb, 'devices']).decode()
    avd_home = out / 'avd'; avd_home.mkdir(); name = 'NoteCoreRaster26-' + uuid.uuid4().hex
    avd = avd_home / (name + '.avd'); avd.mkdir()
    (avd_home / (name + '.ini')).write_text('avd.ini.encoding=UTF-8\npath=' + str(avd) + '\ntarget=android-26\n', encoding='utf-8')
    config = (ROOT / '.android-avd/NoteCorePreview.avd/config.ini').read_text(encoding='utf-8').splitlines()
    values = {'AvdId': name, 'avd.ini.displayname': name, 'image.sysdir.1': str(args.image.resolve()) + '/',
              'tag.id': 'default', 'tag.display': 'Default Android System Image', 'disk.dataPartition.size': '2147483648'}
    (avd / 'config.ini').write_text('\n'.join(key.strip() + ' = ' + values.get(key.strip(), value.strip()) for key, value in
                                             (line.split('=', 1) for line in config if '=' in line)) + '\n', encoding='utf-8')
    android_env = dict(env, ANDROID_AVD_HOME=str(avd_home), ANDROID_EMULATOR_HOME=str(out / 'emulator'), ANDROID_USER_HOME=str(out / 'emulator'))
    emulator_log = (raw / 'emulator.log').open('wb')
    emulator = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', name, '-port', '5582', '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect'], env=android_env, stdout=emulator_log, stderr=subprocess.STDOUT)
    try:
        for _ in range(100):
            assert emulator.poll() is None
            if run('boot-poll', [adb, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], allowed=(0, 1), environment=android_env).strip() == b'1': break
            time.sleep(2)
        else: raise RuntimeError('API26 boot timeout')
        assert run('avd-name', [adb, '-s', serial, 'shell', 'getprop', 'ro.kernel.qemu.avd_name'], environment=android_env).decode().strip() == name
        assert run('api', [adb, '-s', serial, 'shell', 'getprop', 'ro.build.version.sdk'], environment=android_env).strip() == b'26'
        run('fingerprint', [adb, '-s', serial, 'shell', 'getprop', 'ro.build.fingerprint'], environment=android_env)
        apk = out / 'artifacts/canvas-core/outputs/apk/androidTest/debug/canvas-core-debug-androidTest.apk'
        run('install', [adb, '-s', serial, 'install', apk], environment=android_env)
        result = run('instrument', [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r', 'dev.notecore.canvas.poc.test/dev.notecore.canvas.prototype.SweptDiscRasterInstrumentation'], environment=android_env)
        assert b'swept_raster_status=passed' in result and b'swept_raster_cases=6' in result, result
        names = ['retrace-marker', 'pressure-taper', 'reflected-marker', 'wide-dot', 'large-origin-dot', 'stationary-pressure']
        for filename in ['costs.json', *[name + suffix for name in names for suffix in ('.json', '-1.rgba', '-2.rgba')]]:
            payload = run('pull-' + filename, [adb, '-s', serial, 'exec-out', 'run-as', 'dev.notecore.canvas.poc.test', 'cat', 'cache/swept-disc-raster/' + filename], environment=android_env)
            (android / filename).write_bytes(payload)
    finally:
        emulator.terminate()
        try: emulator.wait(timeout=30)
        except subprocess.TimeoutExpired: emulator.kill(); emulator.wait(timeout=10)
        emulator_log.close(); shutil.rmtree(avd); (avd_home / (name + '.ini')).unlink()
    report = compare(apple, android)
    report.update({'apple_costs': json.loads((apple / 'costs.json').read_text(encoding='utf-8')), 'android_costs': json.loads((android / 'costs.json').read_text(encoding='utf-8')),
                   'original_android_renderer_sha256': sha(android_source), 'original_swift_source_sha256': sha(swift_source),
                   'swift_class_fragment_sha256': hashlib.sha256(fragment.encode()).hexdigest(), 'android_copy_matches_original': sha(generated / 'NativeBrushRenderer.kt') == sha(android_source),
                   'framework_sha256': sha(framework / 'NoteCanvasPrototype'), 'apple_binary_sha256': sha(binary), 'android_apk_sha256': sha(apk),
                   'temporary_devices_removed': not avd.exists() and before == after, 'scope': 'Synthetic offscreen software Bitmap/CoreGraphics through original renderer sources. Explicit pixel buffers and exploratory emulator timings; no GPU, App integration, physical heap, ordinary input or C1 completion.'})
    (out / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


def compare(apple, android):
    rows = []
    for path in sorted(apple.glob('*.json')):
        if path.name == 'costs.json': continue
        a = json.loads(path.read_text(encoding='utf-8')); b = json.loads((android / path.name).read_text(encoding='utf-8'))
        assert a == b, ('Plan coordinates differ', path.name)
        width = a['width']; height = a['height']; vertices = a['vertices']; offsets = a['offsets']
        contours = [vertices[start:end] for start, end in zip(offsets, offsets[1:])]
        def region(x, y):
            inside = False; distance = float('inf')
            for contour in contours:
                signs = []
                for p, q in zip(contour, contour[1:] + contour[:1]):
                    dx = q[0] - p[0]; dy = q[1] - p[1]
                    signs.append(dx * (y - p[1]) - dy * (x - p[0]))
                    t = min(1.0, max(0.0, ((x - p[0]) * dx + (y - p[1]) * dy) / (dx * dx + dy * dy)))
                    distance = min(distance, math.hypot(x - p[0] - t * dx, y - p[1] - t * dy))
                inside |= all(s >= 0 for s in signs) or all(s <= 0 for s in signs)
            return inside, distance
        alpha = (a['argb'] >> 24) & 255
        colors = [(a['argb'] >> s) & 255 for s in (16, 8, 0)]
        for times in (1, 2):
            first = (apple / (a['name'] + f'-{times}.rgba')).read_bytes()
            second = (android / (a['name'] + f'-{times}.rgba')).read_bytes()
            assert len(first) == len(second) == width * height * 4
            # Boundary rasterization is measured separately, never asserted to be byte-identical.
            differences = [max(abs(first[i + c] - second[i + c]) for c in range(4)) for i in range(0, len(first), 4)]
            max_difference = max(differences); large = [i for i, value in enumerate(differences) if value > 2]
            checked_inside = checked_outside = 0
            large_set = set(large); large_far = 0; large_max_distance = 0.0
            expected_alpha = round(255 * (1 - (1 - alpha / 255) ** times))
            expected = [round(c * expected_alpha / 255) for c in colors] + [expected_alpha]
            probes = set(range(0, width * height, 17)) | set(large)
            for i in probes:
                inside, distance = region(i % width + .5, i // width + .5)
                if i in large_set:
                    large_max_distance = max(large_max_distance, distance)
                    if distance > 1.5: large_far += 1
                if distance <= 1.5: continue
                wanted = expected if inside else [0, 0, 0, 0]
                for data in (first, second):
                    assert max(abs(data[i * 4 + c] - wanted[c]) for c in range(4)) <= 2, (a['name'], times, i, list(data[i*4:i*4+4]), wanted)
                if inside: checked_inside += 1
                else: checked_outside += 1
            assert checked_inside > 0 and checked_outside > 0
            rows.append({'name': a['name'], 'times': times, 'max_channel_difference': max_difference, 'pixels_differing_over_2': len(large),
                         'clear_interior_probes': checked_inside, 'clear_exterior_probes': checked_outside,
                         'large_diff_probes_beyond_contour_band': large_far, 'large_diff_max_distance_to_any_contour': large_max_distance,
                         'expected_interior_alpha': expected_alpha, 'plan_vertices': len(vertices), 'plan_contours': len(contours)})
    assert len(rows) == 12
    return {'status': 'passed', 'cases': rows, 'plans_identical': True, 'clear_region_tolerance': 2, 'edge_band_target_pixels': 1.5, 'edge_band_scope': 'Distance to any emitted contour edge, including internal overlap edges; not the exact visible union boundary', 'probe_scope': 'Every 17th pixel and every pixel whose cross-platform channel difference exceeds 2', 'c1_complete': False}


def check_comparator():
    with tempfile.TemporaryDirectory(prefix='notecore-raster-comparator-') as temporary:
        root = Path(temporary); apple = root / 'apple'; android = root / 'android'
        apple.mkdir(); android.mkdir()
        originals = {}
        for index in range(6):
            name = 'case' + str(index)
            plan = {'name': name, 'width': 16, 'height': 16, 'argb': 0x7f336699,
                    'vertices': [[3, 2], [11, 2], [11, 8], [3, 8]], 'offsets': [0, 4]}
            for directory in (apple, android):
                (directory / (name + '.json')).write_text(json.dumps(plan), encoding='utf-8')
                for times in (1, 2):
                    alpha = round(255 * (1 - (1 - 127 / 255) ** times))
                    color = bytes([round(c * alpha / 255) for c in (51, 102, 153)] + [alpha])
                    data = bytes().join(color if 3 <= i % 16 < 11 and 2 <= i // 16 < 8 else bytes(4) for i in range(256))
                    path = directory / (name + f'-{times}.rgba'); path.write_bytes(data); originals[path] = data
        assert compare(apple, android)['status'] == 'passed'
        checks = ['matching_clear_regions_accepted']
        for label, pixel, color in [('same_wrong_double_blend_rejected', 85, bytes([38, 76, 115, 191])),
                                    ('same_wrong_exterior_paint_rejected', 0, bytes([25, 51, 76, 127]))]:
            for directory in (apple, android):
                path = directory / 'case0-1.rgba'; data = bytearray(originals[path]); data[pixel*4:pixel*4+4] = color; path.write_bytes(data)
            try: compare(apple, android)
            except AssertionError: checks.append(label)
            else: raise AssertionError(label + ' was accepted')
            for path, data in originals.items(): path.write_bytes(data)
        path = apple / 'case0-1.rgba'; data = originals[path]
        path.write_bytes(b''.join(data[y*64:(y+1)*64] for y in range(15, -1, -1)))
        try: compare(apple, android)
        except AssertionError: checks.append('vertical_orientation_error_rejected')
        else: raise AssertionError('vertical orientation error was accepted')
        path.write_bytes(data)
        path = android / 'case0.json'; plan = json.loads(path.read_text(encoding='utf-8')); plan['vertices'][0][0] += .5
        path.write_text(json.dumps(plan), encoding='utf-8')
        try: compare(apple, android)
        except AssertionError: checks.append('plan_drift_rejected')
        else: raise AssertionError('plan drift was accepted')
        print(json.dumps({'status': 'passed', 'checks': checks, 'scope': 'Synthetic comparator positive/negative controls, no native execution'}))


if __name__ == '__main__': main()
