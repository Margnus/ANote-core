#!/usr/bin/env python3
"""Bind actual JVM, ART/file-port and iOS Kotlin/Native authority-title tests."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
BUNDLE = 'dev.notecore.preview'
ANDROID_CHECKS = {'invalid_title_keeps_root_receipts_and_file', 'same_operation_valid_retry_and_utf8_file_reopen',
                  'invalid_title_keeps_redo_branch', 'invalid_initial_title_refused'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    assert args.serial == 'emulator-5580' and args.device == DEVICE
    folder = ROOT/'build/canonical-title'; folder.mkdir(parents=True, exist_ok=True)

    def command(*args, timeout=120):
        return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True, encoding='utf-8', timeout=timeout).stdout

    def adb(*args):
        return command(args_adb, '-s', 'emulator-5580', *args)

    args_adb = args.adb
    def files():
        output = adb('shell', "run-as " + BUNDLE + " sh -c 'sha256sum files/preview-notes/*.ncp'")
        result = {}
        for line in output.splitlines():
            digest, name = line.split(None, 1)
            assert re.fullmatch('[0-9a-f]{64}', digest) and re.fullmatch(r'files/preview-notes/[a-zA-Z0-9-]+\.ncp', name)
            result[name] = digest
        return result

    before = files()
    stamp = dict(line.split('  ', 1)[::-1] for line in (ROOT/'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    for path, digest in stamp.items(): assert sha(ROOT/path) == digest, ('stale Android title test build', path)
    build = json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))
    apk = ROOT/build['android']['artifact']; test = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    assert build['status'] == 'passed' and sha(apk) == build['android']['sha256']
    adb('install', '-r', str(apk)); adb('install', '-r', str(test))
    for package, artifact in ((BUNDLE, apk), (BUNDLE+'.test', test)):
        remote = adb('shell', 'pm', 'path', package).strip().removeprefix('package:')
        assert re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk', remote)
        raw = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'cat', remote], check=True, capture_output=True, timeout=60).stdout
        assert hashlib.sha256(raw).hexdigest() == sha(artifact)
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_case', 'title_contract',
                 BUNDLE+'.test/dev.notecore.app.ViewportInstrumentation')
    android_log = folder/'android.log'; android_log.write_text(output, encoding='utf-8')
    assert 'title_status=passed' in output and 'INSTRUMENTATION_CODE: -1' in output, output
    android = json.loads(re.search(r'title_observations=(\{.*\})', output).group(1))
    assert len(android['checks']) == 4 and set(android['checks']) == ANDROID_CHECKS and android['unicode_utf16_length'] == 120
    assert files() == before
    baseline = folder/'original-files.json'; baseline.write_text(json.dumps(before, indent=2)+'\n', encoding='utf-8')
    devices = json.loads(command('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))['devices']
    device = next(d for group in devices.values() for d in group if d['udid'] == args.device)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted'
    sources = sorted([*ROOT.glob('shared-core/src/commonMain/**/*.kt'), *ROOT.glob('shared-core/src/commonTest/**/*.kt'),
        *ROOT.glob('canvas-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/iosMain/**/*.kt'),
        *ROOT.glob('shared-core/src/iosMain/**/*.kt'), ROOT/'shared-core/build.gradle.kts', ROOT/'canvas-core/build.gradle.kts',
        ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'gradle.properties', Path(__file__)])
    native_inputs = {str(path.relative_to(ROOT)): sha(path) for path in sources}
    native_command = [str(ROOT/'gradlew'), '--offline', '--no-daemon', '--console=plain', '-PenableAppleTargets=true']
    if os.environ.get('KOTLIN_NATIVE_HOME'): native_command.append('-Pkotlin.native.home='+os.environ['KOTLIN_NATIVE_HOME'])
    native_command.append(':shared-core:linkDebugTestIosSimulatorArm64')
    native_build = folder/'native-build.log'
    with native_build.open('w', encoding='utf-8') as stream:
        subprocess.run(native_command, cwd=ROOT, check=True, stdout=stream, stderr=subprocess.STDOUT, timeout=300)
    binary = ROOT/'shared-core/build/bin/iosSimulatorArm64/debugTest/test.kexe'
    native_output = command('xcrun', 'simctl', 'spawn', args.device, str(binary),
        '--ktest_filter=dev.notecore.document.CanonicalTitleTest.*', '--ktest_logger=GTEST')
    native_log = folder/'native.log'; native_log.write_text(native_output, encoding='utf-8')
    source = ROOT/'shared-core/src/commonTest/kotlin/dev/notecore/document/CanonicalTitleTest.kt'
    names = re.findall(r'@Test\s+fun\s+(\w+)\(', source.read_text(encoding='utf-8'))
    expected = {'dev.notecore.document.CanonicalTitleTest.'+name for name in names}
    passed = re.findall(r'^\[\s+OK\s+\]\s+(\S+)', native_output, re.MULTILINE)
    assert len(expected) == len(passed) == 4 and set(passed) == expected, native_output
    assert native_inputs == {str(path.relative_to(ROOT)): sha(path) for path in sources}
    xml = ROOT/'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.CanonicalTitleTest.xml'
    suite = ET.parse(xml).getroot()
    assert int(suite.attrib['tests']) == 4 and all(int(suite.attrib[key]) == 0 for key in ('errors', 'failures', 'skipped'))
    artifacts = [apk, test, source, xml, android_log, baseline, binary, native_log, native_build,
        ROOT/'reports/preview-build.json', ROOT/'reports/android-brush-build-inputs.sha256']
    bindings = {**stamp, **native_inputs, **{str(path.relative_to(ROOT)): sha(path) for path in artifacts}}
    report = {'schema': 'canonical-title-contract-1', 'status': 'passed', 'android_checks': android,
        'native_checks': sorted(passed), 'jvm_test_count': 4, 'native_test_count': 4,
        'original_android_files': len(before), 'all_original_android_files_unchanged': True,
        'source_and_artifact_sha256': bindings,
        'decision': 'Reject malformed initial or directly renamed authority titles before publication; preserve root, operation identity, receipts and redo. Reuse existing codec and preview Unicode scalar validation, without normalization or format changes.',
        'scope': 'Direct document authority commands on JVM/ART/Kotlin Native, plus Android isolated real file-port UTF-8 write/reopen and old-file preservation. Native test uses malformed UTF-16 literals before Objective-C bridging. No new schema, persistence backend, production storage or full App UI acceptance.',
        'apple_filesystem_invalid_text_tested': False, 'ordinary_app_ui_verified': False,
        'physical_device_or_power_loss_verified': False, 'c1_gate_complete': False}
    (ROOT/'reports/canonical-title-contract.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'android_checks': 4, 'native_tests': 4, 'jvm_tests': 4, 'original_android_files': len(before)}))


if __name__ == '__main__': main()
