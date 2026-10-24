#!/usr/bin/env python3
"""Bind actual shared/native/host recovery tests and unchanged original files; no UI/durability inference."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
TEST = 'testPendingRecoveryOriginalModelFailureRetryNewOwnerAndCanonicalReopen()'
ANDROID = 'pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    assert args.serial == 'emulator-5580' and args.device == DEVICE
    def run(*command):
        return subprocess.check_output(command, cwd=ROOT, timeout=120)
    def adb(*command):
        return run(args.adb, '-s', args.serial, *command)
    reports = {}
    bindings = {str(Path(__file__).relative_to(ROOT)): digest(Path(__file__))}
    for file, field in [('verification.json', 'verification_input_sha256'),
                        ('shared-native-contracts.json', 'source_and_artifact_sha256'),
                        ('android-input-host-tests.json', 'source_and_artifact_sha256'),
                        ('ios-host-tests.json', 'source_and_framework_sha256')]:
        path = ROOT/'reports'/file
        value = json.loads(path.read_text(encoding='utf-8'))
        assert value['status'] == 'passed', file
        for relative, sha in value[field].items():
            assert digest(ROOT/relative) == sha, (file, relative)
        bindings['reports/'+file] = digest(path)
        reports[file] = value
    xml = ROOT/'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.PreviewRecoveryTest.xml'
    suite = ET.parse(xml).getroot()
    assert all(int(suite.attrib[key]) == 0 for key in ('failures', 'errors', 'skipped'))
    checks = [node.attrib['name'].removesuffix('[jvm]').removesuffix('()') for node in suite.findall('testcase')]
    assert len(checks) == 6
    native = reports['shared-native-contracts.json']
    assert all('dev.notecore.document.PreviewRecoveryTest.'+name in native['checks'] for name in checks)
    android = reports['android-input-host-tests.json']
    assert ANDROID in android['storage_retention_checks']['checks']
    assert TEST in reports['ios-host-tests.json']['checks']
    before = ROOT/'build/pending-recovery'
    original_android = json.loads((before/'android-before.json').read_text(encoding='utf-8'))
    names = adb('shell', 'run-as', 'dev.notecore.preview', 'ls', 'files/preview-notes').decode().splitlines()
    actual_android = {name: hashlib.sha256(adb('exec-out', 'run-as', 'dev.notecore.preview', 'cat', 'files/preview-notes/'+name)).hexdigest()
                      for name in names if name.endswith('.ncp')}
    container = Path(run('xcrun', 'simctl', 'get_app_container', DEVICE, 'dev.notecore.preview', 'data').decode().strip())
    original_ios = json.loads((before/'ios-before.json').read_text(encoding='utf-8'))
    actual_ios = {p.name: digest(p) for p in (container/'Documents/PreviewNotes').glob('*.ncp')}
    assert actual_android == original_android and actual_ios == original_ios, 'Original files changed; preserve and investigate'
    report = dict(schema='pending-recovery-1', status='passed', shared_recovery_checks=checks,
        native_recovery_checks=checks, android_original_store_check=ANDROID, swift_original_model_check=TEST,
        original_android_files=actual_android, original_ios_files=actual_ios,
        original_files_unchanged=True, source_and_evidence_sha256=bindings,
        decision='Adopt internal recovery envelope in original atomic file and original ledger/worker/owner; ordinary v1/v2 remain readable. Only exact IO receipt permits pending responsibility handoff. No second authority, queue or sidecar.',
        scope='Common/Native strict framing, full samples and identities, same-revision extension, separate flight/latest charges, old completion, failure/retry, original owner retry once and nonce reuse. Android original Store/worker and Swift original Model/worker perform actual failed writes, repair, checkpoint handoff, new-owner reopen, canonical replacement and truncated-file refusal in isolated synthetic directories.',
        ordinary_ui_verified=False, actual_process_kill_verified=False, power_loss_durability_verified=False,
        physical_heap_or_stylus_verified=False, c1_gate_complete=False, production_sqlite_authorized=False)
    (ROOT/'reports/pending-recovery.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps(dict(status='passed', shared_recovery_checks=len(checks), original_android_files=len(actual_android),
                          original_ios_files=len(actual_ios)), ensure_ascii=False))


if __name__ == '__main__':
    main()
