#!/usr/bin/env python3
"""Bind pending input admission to current shared and original platform host evidence."""
import hashlib
import json
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    bindings = {}
    reports = {}
    fields = {
        'verification': ('source_sha256', 'verification_input_sha256'),
        'shared-native-contracts': ('source_and_artifact_sha256',),
        'android-input-host-tests': ('source_and_artifact_sha256',),
        'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256'),
        'ios-preview-restored': ('input_sha256',),
        'preview-build': ('source_and_build_input_sha256',),
        'ios-preview-build': ('source_and_build_input_sha256',),
    }
    for name, maps in fields.items():
        path = ROOT / 'reports' / (name + '.json')
        report = read(path)
        assert report['status'] == 'passed', name
        for field in maps:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        reports[name] = report
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    native = reports['shared-native-contracts']
    required = ['recoveryCannotAdoptPendingAboveItsInputBudgetAndRefusalKeepsCheckpoint',
                'maximumIdentityMetadataIsChargedAndRestoredAtExactBoundary']
    assert all('dev.notecore.document.PreviewRecoveryTest.' + name in native['checks'] for name in required)
    android = reports['android-input-host-tests']
    assert android['all_original_files_unchanged'] and android['ordinary_process_restored']
    assert android['observations']['active_input_reservation_bytes'] == 1_133_696
    assert 'pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen' in android['storage_retention_checks']['checks']
    ios = reports['ios-host-tests']
    assert 'testPendingRecoveryOriginalModelFailureRetryNewOwnerAndCanonicalReopen()' in ios['checks']
    restored = reports['ios-preview-restored']
    assert restored['ordinary_package_restored_after_host_tests'] and restored['no_xctest_plugin'] and not restored['data_deleted']
    folder = ROOT / 'build/pending-input-budget'
    original_counts = {}
    for platform in ('android', 'ios'):
        before = read(folder / (platform + '-before.json'))
        assert before and before == read(folder / (platform + '-after.json'))
        for name, digest in before.items():
            assert sha(folder / (platform + '-originals') / name) == digest
        original_counts[platform] = len(before)
        for suffix in ('before', 'after'):
            path = folder / (platform + '-' + suffix + '.json')
            bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    for relative in [Path(__file__).relative_to(ROOT).as_posix(), 'tools/verify_android_input_host.py',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PagedPreviewNotebook.kt',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewRecoveryCodec.kt',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewNotebookOpen.kt',
                     'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewRecoveryTest.kt',
                     'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewInputBudgetTest.kt',
                     'android-app/src/androidTest/java/dev/notecore/app/PendingRecoveryHostChecks.kt',
                     'android-app/src/androidTest/java/dev/notecore/app/AndroidInputHostChecks.kt',
                     'ios-app/NoteCorePreviewTests/PagedHostTests.swift']:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'pending-input-budget-1', 'status': 'passed',
        'scope': 'Shared JVM/Native and original Android/Swift consumer host input and pending recovery admission; no ordinary UI or physical heap claim',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'native_tests': native['test_count'], 'swift_host_tests': ios['test_count'],
        'android_input_groups': android['check_count'],
        'android_storage_groups': android['storage_retention_check_count'],
        'android_owner_groups': android['owner_retirement_check_count'],
        'checks': required + ['original Android and Swift consumers reject zero/one-byte-short input capacity and accept exact pending charge',
                              'checkpoint and original owner pending unchanged on refusal; retry commits once',
                              'DOWN reserves arena plus maximum retained command metadata before accepting real samples',
                              'original worker IO failure/retry/new owner/canonical reopen regression'],
        'decision': 'Adopt original pendingCharge for retained input; reserve 3072 logical metadata bytes at DOWN and refuse recovery over inputBytes before authority creation. Existing format, authority, ledger and worker retained.',
        'original_files_unchanged': original_counts,
        'limits': ['Logical reservation is not measured heap or complete producer/render/save peak admission',
                   'Input capacity refusal takes precedence over other open validation when pending exceeds budget',
                   'No ordinary GUI, physical stylus/M-1, full owner transfer, unconfirmed IO interruption or power loss verification'],
        'ordinary_ui_verified': False, 'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/pending-input-budget.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
