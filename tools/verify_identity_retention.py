#!/usr/bin/env python3
"""Bind shared allocation/identity retention evidence; no native App or heap claim."""
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
                         ('shared-native-contracts', ('source_and_artifact_sha256',))]:
        path = ROOT / 'reports' / (name + '.json')
        report = json.loads(path.read_text(encoding='utf-8'))
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        reports[name] = report
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    native = reports['shared-native-contracts']
    required = ['DocumentAuthorityTest.allocationProbeUsesOriginalTombstonesAndJournalWithoutMutation',
                'PagedPreviewNotebookTest.reusedNonceSkipsInitialIdentitiesAcrossKindsWithoutDuplicatingIndex',
                'PreviewRecoveryTest.exactPendingRoundtripAndOriginalOwnerExplicitRetryOnce']
    assert all('dev.notecore.document.' + name in native['checks'] for name in required)
    for relative in ['tools/verify_identity_retention.py',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/MemoryDocumentAuthority.kt',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PagedPreviewNotebook.kt',
                     'shared-core/src/commonTest/kotlin/dev/notecore/document/DocumentAuthorityTest.kt',
                     'shared-core/src/commonTest/kotlin/dev/notecore/document/PagedPreviewNotebookTest.kt',
                     'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewRecoveryTest.kt']:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'identity-retention-1', 'status': 'passed',
        'scope': 'Current shared JVM and Kotlin/Native common tests; original authority identity allocation only',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'native_tests': native['test_count'], 'checks': required,
        'decision': 'Remove notebook initialIds index; probe original budgeted authority tombstones/journal and original pending command/object identities. No new index, authority or persistence format.',
        'limits': ['No physical heap measurement or complete chain peak budget',
                   'No rebuilt Android/Swift App, ordinary UI or current platform host claim',
                   'No power loss, unconfirmed IO interruption or complete owner transfer validation'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT / 'reports/identity-retention.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
