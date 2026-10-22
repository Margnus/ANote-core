#!/usr/bin/env python3
"""Execute the complete current shared-core common suite on the dedicated Apple simulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    assert args.device == DEVICE
    def command(*values):
        return subprocess.run(values, cwd=ROOT, capture_output=True, text=True,
                              encoding='utf-8', check=True, timeout=120).stdout
    devices = json.loads(command('xcrun', 'simctl', 'list', 'devices', '--json'))['devices']
    device = next(d for group in devices.values() for d in group if d['udid'] == DEVICE)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted' and device['isAvailable']
    tests = sorted(ROOT.glob('shared-core/src/commonTest/**/*.kt'))
    expected = set()
    for path in tests:
        text = path.read_text(encoding='utf-8')
        package = re.search(r'^package (\S+)', text, re.MULTILINE).group(1)
        classes = re.findall(r'^class (\w+Test)\b', text, re.MULTILINE)
        assert len(classes) == 1 and '@Ignore' not in text, path
        expected.update(package + '.' + classes[0] + '.' + name
                        for name in re.findall(r'@Test\s+fun\s+(\w+)\(', text))
    assert expected, 'No common tests discovered'
    paths = sorted([*ROOT.glob('shared-core/src/commonMain/**/*.kt'), *tests,
                    *ROOT.glob('shared-core/src/iosMain/**/*.kt'),
                    *ROOT.glob('canvas-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/iosMain/**/*.kt'),
                    ROOT/'shared-core/build.gradle.kts', ROOT/'canvas-core/build.gradle.kts',
                    ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'gradle.properties', Path(__file__)])
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}
    folder = ROOT/'build/shared-native'; folder.mkdir(parents=True, exist_ok=True)
    build = [str(ROOT/'gradlew'), '--offline', '--no-daemon', '--console=plain', '-PenableAppleTargets=true']
    if os.environ.get('KOTLIN_NATIVE_HOME'):
        build.append('-Pkotlin.native.home='+os.environ['KOTLIN_NATIVE_HOME'])
    build.append(':shared-core:linkDebugTestIosSimulatorArm64')
    build_log = folder/'build.log'
    with build_log.open('w', encoding='utf-8') as output:
        subprocess.run(build, cwd=ROOT, check=True, stdout=output, stderr=subprocess.STDOUT, timeout=300)
    binary = ROOT/'shared-core/build/bin/iosSimulatorArm64/debugTest/test.kexe'
    binary_sha = sha(binary)
    output = command('xcrun', 'simctl', 'spawn', DEVICE, str(binary), '--ktest_logger=GTEST')
    log = folder/'run.log'; log.write_text(output, encoding='utf-8')
    passed = re.findall(r'^\[\s+OK\s+\]\s+(\S+)', output, re.MULTILINE)
    assert len(passed) == len(expected) and set(passed) == expected, output
    assert f'[  PASSED  ] {len(expected)} tests.' in output
    assert before == {p.relative_to(ROOT).as_posix(): sha(p) for p in paths}
    assert sha(binary) == binary_sha
    bindings = dict(before)
    bindings.update({p.relative_to(ROOT).as_posix(): sha(p) for p in (binary, log, build_log)})
    report = {'schema': 'shared-native-contracts-1', 'status': 'passed', 'test_count': len(passed),
              'checks': sorted(passed), 'device': DEVICE, 'source_and_artifact_sha256': bindings,
              'scope': 'All current shared-core common tests compiled and executed on Kotlin/Native, including save dispatch, title, history, capacity, edit, input and codecs. No JVM-only worker tests, native file IO, UIKit delivery, current ordinary UI, physical performance or durability claim.',
              'jvm_only_worker_tests_included': False, 'ordinary_ui_verified': False,
              'native_file_io_verified_by_this_suite': False, 'physical_device_verified': False,
              'c1_gate_complete': False}
    (ROOT/'reports/shared-native-contracts.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'native_tests': len(passed)}))


if __name__ == '__main__':
    main()
