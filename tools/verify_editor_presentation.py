#!/usr/bin/env python3
"""Bind original Android multi-editor read-only feedback to actual worker/Activity evidence."""
import hashlib
import json
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    reports = {}; bindings = {}
    for name, fields in {
        'verification': ('source_sha256', 'verification_input_sha256'),
        'preview-build': ('source_and_build_input_sha256',),
        'android-input-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    }.items():
        path = ROOT / 'reports' / (name + '.json'); report = read(path)
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        reports[name] = report; bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    android = reports['android-input-host-tests']
    required = {'editor_original_ledger_old_ack_failure_and_retry', 'editor_stopped_backend_feedback', 'actual_activity_shared_presentation_update'}
    assert required <= set(android['owner_retirement_checks']['checks'])
    assert android['owner_retirement_check_count'] == 12 and android['all_original_files_unchanged'] and android['ordinary_process_restored']
    originals = read(ROOT / 'build/android-input-host/original-files.json')
    previous = read(ROOT / 'build/catalog-budget/android-after.json')
    assert len(originals) == len(previous) == 66
    assert {Path(k).name: v for k, v in originals.items()} == {Path(k).name: v for k, v in previous.items()}
    for name, digest in originals.items():
        assert sha(ROOT / 'build/pending-input-budget/android-originals' / Path(name).name) == digest
    paths = [Path(__file__), ROOT / 'build/android-input-host/original-files.json',
             ROOT / 'android-app/src/main/java/dev/notecore/app/NoteStore.kt',
             ROOT / 'android-app/src/main/java/dev/notecore/app/MainActivity.kt',
             ROOT / 'android-app/src/main/java/dev/notecore/app/InkCanvasView.kt',
             ROOT / 'android-app/src/androidTest/java/dev/notecore/app/EditorPresentationHostChecks.kt',
             ROOT / 'android-app/src/androidTest/java/dev/notecore/app/EditorAttachmentHostChecks.kt',
             ROOT / 'docs/owner交接与失败责任矩阵.md']
    for path in paths:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    result = {
        'schema': 'editor-presentation-1', 'status': 'passed',
        'scope': 'Original Android Store/ledger/file worker; synchronous weak read-only observers and actual MainActivity callbacks with isolated test-injected Store. No ordinary OS multiwindow input or physical heap claim.',
        'decision': 'Reuse the original eight editor registrations as weak observers. Derive phase from original ledger/current root+pending identities and real file ACK. Notify at most eight synchronously; no document payload queue or second state authority. Activity caches only its last scalar presentation and preserves same-page active input.',
        'checks': sorted(required), 'input_checks': android['check_count'],
        'storage_checks': android['storage_retention_check_count'], 'owner_window_checks': android['owner_retirement_check_count'],
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'], 'original_files_preserved': len(originals),
        'verified_details': ['old root ACK keeps latest current root SAVING; unoffered current root remains UNSAVED',
                             'both observers see latest file ACK, IO failure/retry and repaired write from original ledger',
                             'throwing/detached observer does not consume content or prevent another observer',
                             'stopped backend remains unavailable with original ledger content and old file intact',
                             'confirmed pending recovery is RECOVERY_SAVED, not committed ordinary ink',
                             'real sibling Activity updates title/save/undo/page from the shared owner',
                             'real pre-draw after save feedback keeps canvas bounds and original accepted input operation',
                             'page-only notification preserves content revision and file bytes'],
        'limits': ['Callbacks are advisory/read-only; finite synchronous traversal coalesces recursive notification, with no asynchronous retry queue',
                   'Stopped-backend test proves refusal/retention only; no backend restart or dead-owner takeover',
                   'Weak references are not GC timing, resource release receipts or physical memory measurement',
                   'No new shared Native/Apple run, complete OS window/toolbar/picker acceptance, production DURABLE or C1/M-1 approval'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/editor-presentation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
