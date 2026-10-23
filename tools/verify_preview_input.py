#!/usr/bin/env python3
"""Bind raw-batch/capacity JVM/Native tests to source-bound ART/UIKit adapter evidence."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from verify_ios_host import EXPECTED as EXPECTED_IOS_HOST_CHECKS
from verify_android_input_host import CHECKS as EXPECTED_ANDROID_INPUT_CHECKS

ROOT = Path(__file__).resolve().parents[1]
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--device', required=True); args = parser.parse_args()
    assert args.device == DEVICE
    folder = ROOT/'build/preview-input'; folder.mkdir(parents=True, exist_ok=True)
    def command(*args):
        return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True, encoding='utf-8', timeout=120).stdout
    devices = json.loads(command('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))['devices']
    device = next(d for group in devices.values() for d in group if d['udid'] == args.device)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted'
    bindings = {}
    for name, fields in (
        ('android-input-host-tests', ('source_and_artifact_sha256', 'sdk_contract_sha256')),
        ('ios-host-tests', ('source_and_framework_sha256', 'test_build_sha256', 'input_sdk_contract_sha256')),
    ):
        path = ROOT/'reports'/(name+'.json'); report = json.loads(path.read_text(encoding='utf-8'))
        assert report['status'] == 'passed'
        for field in fields:
            assert report[field]
            for relative, digest in report[field].items():
                assert sha(ROOT/relative) == digest, ('Stale adapter evidence', name, relative)
                bindings[relative] = digest
        bindings[str(path.relative_to(ROOT))] = sha(path)
        if name == 'android-input-host-tests':
            assert report['check_count'] == len(report['checks']) == len(EXPECTED_ANDROID_INPUT_CHECKS)
            assert set(report['checks']) == EXPECTED_ANDROID_INPUT_CHECKS
            assert report['observations']['down_history_included'] is True
            assert report['observations']['callback_real_batch_limit'] == 1024
            assert 'callback_batch_limit_explicit_cancel_and_recover' in report['checks']
        else:
            assert report['test_count'] == len(report['checks']) == len(EXPECTED_IOS_HOST_CHECKS)
            assert set(report['checks']) == EXPECTED_IOS_HOST_CHECKS
            assert report['native_scalar_input_batch_bridge_verified'] is True
            assert 'testNativeScalarBatchBridgeValidatesLengthsAndCopiesAcceptedData()' in report['checks']
    sources = sorted([*ROOT.glob('shared-core/src/commonMain/**/*.kt'), *ROOT.glob('shared-core/src/commonTest/**/*.kt'),
        *ROOT.glob('shared-core/src/iosMain/**/*.kt'), *ROOT.glob('canvas-core/src/commonMain/**/*.kt'),
        *ROOT.glob('canvas-core/src/iosMain/**/*.kt'), ROOT/'shared-core/build.gradle.kts', ROOT/'canvas-core/build.gradle.kts',
        ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'gradle.properties',
        ROOT/'tools/verify_ios_host.py', ROOT/'tools/verify_android_input_host.py', Path(__file__)])
    inputs = {str(path.relative_to(ROOT)): sha(path) for path in sources}
    build = [str(ROOT/'gradlew'), '--offline', '--no-daemon', '--console=plain', '-PenableAppleTargets=true']
    if os.environ.get('KOTLIN_NATIVE_HOME'): build.append('-Pkotlin.native.home='+os.environ['KOTLIN_NATIVE_HOME'])
    build.append(':shared-core:linkDebugTestIosSimulatorArm64')
    build_log = folder/'native-build.log'
    with build_log.open('w', encoding='utf-8') as stream:
        subprocess.run(build, cwd=ROOT, check=True, stdout=stream, stderr=subprocess.STDOUT, timeout=300)
    binary = ROOT/'shared-core/build/bin/iosSimulatorArm64/debugTest/test.kexe'
    suites = ('PreviewInputTest', 'PreviewInputCapacityTest', 'PreviewInputBudgetTest', 'PreviewCommitCapacityTest')
    test_filter = ':'.join(f'dev.notecore.document.{name}.*' for name in suites)
    output = command('xcrun', 'simctl', 'spawn', args.device, str(binary),
        '--ktest_filter='+test_filter, '--ktest_logger=GTEST')
    log = folder/'native.log'; log.write_text(output, encoding='utf-8')
    expected = set()
    artifacts = []
    for suite_name in suites:
        source = ROOT/f'shared-core/src/commonTest/kotlin/dev/notecore/document/{suite_name}.kt'
        names = re.findall(r'@Test\s+fun\s+(\w+)\(', source.read_text(encoding='utf-8'))
        assert names and '@Ignore' not in source.read_text(encoding='utf-8')
        expected.update(f'dev.notecore.document.{suite_name}.'+name for name in names)
        xml = ROOT/f'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.{suite_name}.xml'
        suite = ET.parse(xml).getroot()
        assert int(suite.attrib['tests']) == len(names) and all(int(suite.attrib[k]) == 0 for k in ('failures', 'errors', 'skipped'))
        assert {case.attrib['name'].removesuffix('[jvm]') for case in suite.findall('testcase')} == set(names)
        artifacts.extend((source, xml))
    passed = re.findall(r'^\[\s+OK\s+\]\s+(\S+)', output, re.MULTILINE)
    assert len(expected) == len(passed) == 25 and set(passed) == expected, output
    assert inputs == {str(path.relative_to(ROOT)): sha(path) for path in sources}
    bindings.update(inputs)
    bindings.update({str(p.relative_to(ROOT)): sha(p) for p in (binary, log, build_log, *artifacts)})
    report = {
        'schema': 'preview-input-contract-1', 'status': 'passed', 'source_and_artifact_sha256': bindings,
        'jvm_tests': len(expected), 'native_tests': len(passed), 'android_original_adapter_checks': len(EXPECTED_ANDROID_INPUT_CHECKS), 'swift_host_tests': len(EXPECTED_IOS_HOST_CHECKS),
        'live_byte_capacity_jvm_native_regressions': 3,
        'input_commit_capacity_jvm_native_regressions': 7,
        'android_live_byte_capacity_callback_verified': 'live_byte_refusal_and_undo_recovery' in EXPECTED_ANDROID_INPUT_CHECKS,
        'swift_specific_live_byte_callback_case_verified': False,
        'native_checks': sorted(passed), 'max_real_samples_per_batch': 1024,
        'decision': 'Adopt atomic bounded raw real-batch admission on the original notebook and accumulator. Distinguish rejected-zero, accepted-active, committed and accepted-pending; no duplicate terminal resend. Native adapters copy a whole callback before admission, explicitly cancel/report unrecoverable native input refusal, never truncate. Apple NSData shim performs bounded native scalar copies without per-sample Kotlin callbacks.',
        'scope': 'Actual JVM/Kotlin Native raw-batch tests plus source-bound Android original View synthetic MotionEvents and iOS original UIKit callbacks/NSData bridge app-host tests. No ordinary package UI, real OS stylus delivery or physical latency claim. Source byte/count bounds are not measured process heap or a production arena.',
        'production_point_arena_or_overall_backpressure_verified': False, 'zero_copy_native_bridge': False,
        'predicted_points_admitted': False, 'durable_receipt_verified_by_batch_result': False,
        'ordinary_app_ui_verified': False, 'physical_device_verified': False, 'c1_gate_complete': False,
    }
    (ROOT/'reports/preview-input-contract.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('status', 'jvm_tests', 'native_tests', 'android_original_adapter_checks', 'swift_host_tests')}))


if __name__ == '__main__': main()
