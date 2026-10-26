#!/usr/bin/env python3
"""Bind current JVM and original Android consumer evidence; Apple and heap remain unverified."""
import hashlib
import json
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    bindings = {}
    reports = {}
    for name, fields in [('verification', ('source_sha256', 'verification_input_sha256')),
                         ('android-input-host-tests', ('source_and_artifact_sha256',))]:
        path = ROOT / 'reports' / (name + '.json')
        report = json.loads(path.read_text(encoding='utf-8'))
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        reports[name] = report
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    android = reports['android-input-host-tests']
    assert android['all_original_files_unchanged']
    assert 'input_byte_budget_refusal_cancel_and_recovery' in android['checks']
    assert 'pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen' in android['storage_retention_checks']['checks']
    for relative in ['tools/verify_pending_input_budget.py', 'build/pending-input-budget/build.log',
                     'build/pending-input-budget/before-files.json',
                     'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.PreviewInputBudgetTest.xml',
                     'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.PreviewRecoveryTest.xml',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PagedPreviewNotebook.kt',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewRecoveryCodec.kt',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewNotebookOpen.kt',
                     'android-app/src/androidTest/java/dev/notecore/app/PendingRecoveryHostChecks.kt']:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'pending-input-budget-1', 'status': 'passed',
        'scope': 'Current JVM and Android original View/Store worker consumer tests; no current Apple or ordinary UI claim',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'android_view_checks': android['check_count'], 'android_storage_checks': android['storage_retention_check_count'],
        'android_owner_checks': android['owner_retirement_check_count'],
        'android_api': android['api'], 'android_original_files': android['original_file_count'],
        'decision': 'Reuse original recovery retained-command formula for pending input. DOWN reserves the greater of arena/completion overlap and exact pending charge. Recovery quota refusal precedes authority creation and preserves checkpoint for adequate-budget adoption.',
        'limits': ['Producer/platform copies, projection containers and whole-chain peak budget remain open',
                   'No current Apple Native/framework/Swift host build on this Windows environment',
                   'No physical heap/stylus, ordinary UI, unconfirmed IO interruption, power loss or complete owner transfer evidence'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT / 'reports/pending-input-budget.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
