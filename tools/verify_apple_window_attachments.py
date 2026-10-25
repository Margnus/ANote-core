#!/usr/bin/env python3
"""Bind original Apple per-window presentation, pending, hosting and export evidence."""
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
        'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256'),
        'ios-preview-build': ('source_and_build_input_sha256',),
        'ios-preview-restored': ('input_sha256',),
    }.items():
        path = ROOT / 'reports' / (name + '.json'); report = read(path)
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        reports[name] = report; bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    required = {
        'testWindowDeparturePreservesAuthorityAndBoundsPresentationIdentities()',
        'testWindowPendingRecoveryRequiresOriginalWriteBeforeLastDeparture()',
        'testHostingWindowsKeepLocalNavigationThroughHierarchyReplacement()',
        'testExportResultRoutesOnlyToRequestWindowAndCancelsOnDisappearance()',
        'testLateWindowOpenCannotReplaceEditorOrAttachAfterDisappearance()',
    }
    host = reports['ios-host-tests']; restored = reports['ios-preview-restored']
    assert required <= set(host['checks']) and host['test_count'] == 60
    assert restored['no_xctest_plugin'] and not restored['data_deleted']
    folder = ROOT / 'build/apple-window-attachments'
    originals = read(folder / 'ios-after.json')
    assert len(originals) == 14 and originals == read(ROOT / 'build/surface-input/ios-after.json')
    for name, digest in originals.items():
        assert sha(ROOT / 'build/pending-input-budget/ios-originals' / name) == digest
    first = read(folder / 'first-summary.json')
    assert first['totalTestCount'] == 60 and first['failedTests'] == 1
    assert any('actual read starts' in failure['failureText'] for failure in first['testFailures'])
    paths = [Path(__file__), folder / 'ios-after.json', folder / 'first-summary.json', folder / 'first-host.log',
             ROOT / 'ios-app/NoteCorePreview/NoteCorePreviewApp.swift',
             ROOT / 'ios-app/NoteCorePreview/NativeInkCanvas.swift',
             ROOT / 'ios-app/NoteCorePreviewTests/PagedHostTests.swift',
             ROOT / 'docs/owner交接与失败责任矩阵.md']
    for path in paths:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    result = {
        'schema': 'apple-window-attachments-1', 'status': 'passed',
        'scope': 'Original Apple Model/worker and SwiftUI PreviewRoot in actual UIHostingController/UIWindow hierarchy; API-driven input/export, not separate OS Scenes or ordinary system picker/touch acceptance',
        'swift_host_tests': host['test_count'], 'required_tests': sorted(required),
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'decision': 'At most eight UUID presentations share the original Model/Notebook. Window state holds navigation/original accepted-input identity and a weak input view. Last departure keeps original pending responsibility until confirmed IO. Export result belongs to one window and one request; disappearance cancels derived delivery, stale request cannot clear a newer backup.',
        'checks': ['same Notebook and active prefix survive sibling departure/tool cancellation/export refusal; last command saves/reopens',
                   'explicit owner cancellation uses its original operation even after weak View is gone, with no completed content loss',
                   'duplicate/eight/ninth capacity and service retirement refusal',
                   'failed pending recovery write retains last window; repair/retry/confirmed departure/new session/single commit',
                   'real hosting hierarchy removes/replaces one root while another remains in its library',
                   'non-requesting window cannot release export; cancellation before/after result keeps canonical root; stale request cannot release newer export',
                   'late read cannot replace active owner or reattach disappeared window; bad target preserves idle original; valid target joins requester'],
        'original_ios_files_preserved': len(originals),
        'first_test_iteration': '59 passed/one observation assertion failed after consuming a semaphore signal twice; observer caches its first real signal. Failure log/summary preserved; final complete suite passed.',
        'limits': ['Single active document remains a preview limit; this does not implement production parallel multi-document windows',
                   'No ordinary system file picker, separate OS Scene activation/dismissal, all-window save status or tool-state acceptance',
                   'No new Android/shared Native run, dead-thread takeover, physical peak heap/M-1/pen or power-loss evidence'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/apple-window-attachments.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
