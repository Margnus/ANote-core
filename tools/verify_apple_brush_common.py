#!/usr/bin/env python3
"""Execute common geometry tests compiled by Kotlin/Native on the existing iOS Simulator."""
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
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    devices = json.loads(subprocess.run(['xcrun', 'simctl', 'list', 'devices', '-j'],
        capture_output=True, text=True, check=True).stdout)['devices']
    device = next(d for group in devices.values() for d in group if d['udid'] == args.device)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted' and device['isAvailable']
    sources = sorted([*ROOT.glob('canvas-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/commonTest/**/*.kt'),
        ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'canvas-core/build.gradle.kts',
        ROOT/'gradle.properties', Path(__file__)])
    before = {p.relative_to(ROOT).as_posix(): digest(p) for p in sources}
    build = ROOT/'canvas-core/build/apple-brush-tests'
    build.mkdir(parents=True, exist_ok=True)
    command = [str(ROOT/'gradlew'), '--no-daemon', '--console=plain', '-PenableAppleTargets=true']
    if os.environ.get('KOTLIN_NATIVE_HOME'):
        command.append('-Pkotlin.native.home='+os.environ['KOTLIN_NATIVE_HOME'])
    command.append(':canvas-core:linkDebugTestIosSimulatorArm64')
    with (build/'build.log').open('w', encoding='utf-8') as log:
        subprocess.run(command, cwd=ROOT, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    binary = ROOT/'canvas-core/build/bin/iosSimulatorArm64/debugTest/test.kexe'
    binary_sha = digest(binary)
    run = subprocess.run(['xcrun', 'simctl', 'spawn', args.device, str(binary),
        '--ktest_filter=dev.notecore.canvas.prototype.BrushEnvelopePocTest.*:dev.notecore.canvas.prototype.BrushPathPocTest.*:dev.notecore.canvas.prototype.BrushReductionPocTest.*:dev.notecore.canvas.prototype.ChunkedBrushPocTest.*:dev.notecore.canvas.prototype.BrushTileCachePocTest.*:dev.notecore.canvas.prototype.ResourceLeaseContractTest.*:dev.notecore.canvas.prototype.WeightedLruContractTest.*',
        '--ktest_logger=GTEST'], cwd=ROOT, check=False, capture_output=True, text=True, timeout=120)
    (build/'run.log').write_text(run.stdout+run.stderr, encoding='utf-8')
    run.check_returncode()
    passed = re.findall(r'^\[\s+OK\s+\]\s+(\S+)', run.stdout, re.MULTILINE)
    expected = set()
    for name in ('BrushEnvelopePocTest', 'BrushPathPocTest', 'BrushReductionPocTest', 'ChunkedBrushPocTest', 'BrushTileCachePocTest', 'ResourceLeaseContractTest', 'WeightedLruContractTest'):
        source = ROOT/('canvas-core/src/commonTest/kotlin/dev/notecore/canvas/prototype/'+name+'.kt')
        expected.update('dev.notecore.canvas.prototype.'+name+'.'+method for method in
            re.findall(r'@Test\s+fun\s+(\w+)\(', source.read_text(encoding='utf-8')))
    assert len(expected) == 55 and set(passed) == expected and len(passed) == 55, run.stdout
    assert before == {p.relative_to(ROOT).as_posix(): digest(p) for p in sources}
    assert digest(binary) == binary_sha
    report = {'status': 'passed', 'platform': 'Kotlin/Native ARM64 tests executed via iOS Simulator spawn',
        'device': {'name': device['name'], 'udid': args.device}, 'test_count': len(passed), 'checks': sorted(passed),
        'source_and_build_sha256': before, 'test_binary_sha256': binary_sha,
        'test_output_sha256': digest(build/'run.log'), 'desktop_unlock_required': False,
        'shared_geometry_generation_on_kotlin_native_verified': True, 'native_rasterizer_verified': False,
        'physical_device_measured': False, 'app_brush_integration_verified': False,
        'scope': 'Actual common envelope/path generation, bounded admission, cancellation and coverage tests compiled/run by Kotlin/Native. No UIKit rasterizer, touch or physical performance claim.'}
    (ROOT/'reports/apple-brush-common-tests.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_build_sha256'}))


if __name__ == '__main__':
    main()
