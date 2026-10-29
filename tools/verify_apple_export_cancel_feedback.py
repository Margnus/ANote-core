#!/usr/bin/env python3
"""Bind actual XCTest/build/file evidence to separately recorded manual CUA observations."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT/'reports/apple-export-cancel-feedback-20261029'
OLD_UI = ROOT/'reports/apple-system-files-cancel-recovered-20261029'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    before = read(CASE/'before/summary.json')
    assert before['result'] == 'Failed' and before['failedTests'] == before['totalTestCount'] == 1
    assert any('导出已取消，原笔记仍保留' in row['failureText'] for row in before['testFailures'])
    host = read(ROOT/'reports/ios-host-tests.json')
    assert host['status'] == 'passed' and host['test_count'] == 65
    assert 'testExportResultRoutesOnlyToRequestWindowAndCancelsOnDisappearance()' in host['checks']
    after = read(CASE/'after-summary.json')
    assert host['test_summary'] == after and after['result'] == 'Passed'
    assert after['failedTests'] == after['skippedTests'] == after['expectedFailures'] == 0
    build = read(ROOT/'reports/ios-preview-build.json')
    restored = read(ROOT/'reports/ios-preview-restored.json')
    assert build['status'] == 'passed' and sha(ROOT/build['artifact']) == build['sha256'] == restored['artifact_sha256']
    assert restored['no_xctest_plugin'] and restored['data_deleted'] is False
    payload = read(CASE/'payload-final.json')
    assert payload == restored['installed_payload_sha256'] and not any('.xctest' in p for p in payload)
    files = read(CASE/'files-final.json')
    assert len(files) == 16 and files == read(OLD_UI/'files-before.json') == read(ROOT/'reports/apple-system-files-cancel-20261029/files-after.json')
    old_ui = read(OLD_UI/'old-package-ui-observations.json')
    new_ui = read(CASE/'new-package-ui-observations.json')
    old_build = read(CASE/'before/ios-preview-build.json')
    assert old_ui['artifact_sha256'] == old_build['sha256'] == read(OLD_UI/'start.json')['artifact_sha256']
    assert new_ui['artifact_sha256'] == build['sha256'] != old_build['sha256']
    assert old_ui['export_cancel_verified'] and old_ui['post_export_cancel_reopen_verified']
    assert old_ui['import_cancel_verified'] and old_ui['post_import_cancel_reopen_verified']
    old_states = {r['label']: r for r in old_ui['states']}
    assert old_states['export_cancel']['feedback_defect_observed']
    assert '正在准备备份…' in old_states['export_cancel']['ax_fragments']
    for key in ('export_cancel_feedback_verified', 'post_cancel_reopen_verified', 'post_cancel_export_retry_verified', 'retry_cancel_verified'):
        assert new_ui[key]
    states = {r['label']: r for r in new_ui['states']}
    for key in ('new_export_cancel', 'new_retry_cancel'):
        assert '导出已取消，原笔记仍保留' in states[key]['ax_fragments']
    assert '已打开 · 1 笔' in states['new_reopen_after_cancel']['ax_fragments']
    assert old_ui['new_notes_created'] == new_ui['new_notes_created'] == 0
    assert not old_ui['file_selection_or_export_save_performed'] and not new_ui['save_in_external_picker_performed']
    assert '16867' == (CASE/'process-final.log').read_text(encoding='utf-8').split()[0]
    execution = read(CASE/'execution.json')
    assert execution['ordinary_confirmation_build_exit_code'] == 0
    bindings = {}
    for mapping in (host['source_and_framework_sha256'], host['test_build_sha256'], build['source_and_build_input_sha256']):
        for path, expected in mapping.items():
            assert sha(ROOT/path) == expected, path
            bindings[path] = expected
    paths = [*CASE.rglob('*'), *OLD_UI.iterdir(), Path(__file__).resolve(), ROOT/build['artifact'],
             *(ROOT/'reports'/p for p in ('ios-preview-build.json', 'ios-preview-restored.json', 'ios-build-inputs.sha256', 'ios-host-tests.json'))]
    bindings.update({p.relative_to(ROOT).as_posix(): sha(p) for p in paths if p.is_file()})
    report = {'schema': 'apple-export-cancel-feedback-1', 'status': 'passed', 'date': '2026-10-29',
        'problem': 'Actual ordinary Files export dismissal left the editor showing preparing. Original finishExport cleared request/result but published no cancellation feedback.',
        'decision': 'Update feedback only when finishing a current export request. Preserve original window/request identity gates, worker ACK, immutable export data and content responsibility. Matching result callbacks retain their existing success/failure feedback. Duplicate cleanup cannot overwrite later status.',
        'verification': {'baseline_targeted_xctest': {'tests': 1, 'failed': 1}, 'full_swift_host_tests': 65,
            'new_artifact_sha256': build['sha256'], 'ordinary_pid': 16867, 'original_files_unchanged': 16,
            'ordinary_complete_payload_matched_without_xctest': True,
            'old_package_ui': {'artifact_sha256': old_build['sha256'], 'import_cancel_and_reopen': True,
                               'export_cancel_and_reopen': True, 'stale_preparing_feedback_observed': True},
            'new_package_ui': {'export_cancel_feedback': True, 'post_cancel_reopen': True, 'post_cancel_export_retry_and_cancel': True}},
        'ui_evidence_method': 'Manual CUA Device Hub AX/screenshot observations plus actual file/payload hashes. This verifier checks retained facts and bindings, not an automated UI pixel/event oracle.',
        'limitations': ['No new-package import/export success roundtrip repeated; old ordinary Files report retains its original artifact scope.',
                       'No permission revocation, cloud provider, partial write/IO fault, rotation/multitouch or physical Pencil/heap/performance evidence.',
                       'Shared/Android business code unchanged; no new JVM/shared Native/Android test claim or inherited UI conclusion.',
                       'Full C1/unified peak/physical M-1 and DEV-041/production SQLite remain gated. No whole requirement accepted.'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False, 'whole_requirement_accepted': False,
        'source_and_evidence_sha256': dict(sorted(bindings.items()))}
    (ROOT/'reports/apple-export-cancel-feedback.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: report[k] for k in ('status', 'verification', 'ui_evidence_method')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
