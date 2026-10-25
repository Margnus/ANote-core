#!/usr/bin/env python3
"""Bind actual original Apple owner-reference failure and retirement evidence."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    bindings = {}
    reports = {}
    for name, fields in {
        'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256'),
        'ios-preview-build': ('source_and_build_input_sha256',),
        'ios-preview-restored': ('input_sha256',),
    }.items():
        path = ROOT / 'reports' / (name + '.json')
        report = read(path); assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                assert sha(ROOT / relative) == digest, (name, relative)
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
        reports[name] = report
    tests = {
        'testOwnerReferenceDropKeepsFailedWriteUntilOriginalRetirement()',
        'testOwnerReferenceDropKeepsProviderUntilOriginalRetirement()',
    }
    host = reports['ios-host-tests']
    assert tests <= set(host['checks']) and host['test_count'] == 54
    restored = reports['ios-preview-restored']
    assert restored['no_xctest_plugin'] and not restored['data_deleted']
    folder = ROOT / 'build/owner-reference'
    summary = read(folder / 'before-summary.json')
    assert summary['failedTests'] == summary['totalTestCount'] == 2
    assert {f['testName'] for f in summary['testFailures']} == tests
    assert any('XCTUnwrap failed' in f['failureText'] for f in summary['testFailures'])
    baseline = (folder / 'before-model.swift').read_text(encoding='utf-8')
    assert 'ownerRetiring = true; retirementKeepAlive = self' in baseline
    originals = read(folder / 'ios-before.json')
    assert len(originals) == 14 and originals == read(folder / 'ios-after.json')
    assert originals == read(ROOT / 'build/catalog-budget/ios-after.json')
    for name, digest in originals.items():
        assert sha(ROOT / 'build/pending-input-budget/ios-originals' / name) == digest
    paths = [Path(__file__), folder / 'before-summary.json', folder / 'before-device.log',
             folder / 'before-model.swift', folder / 'ios-before.json', folder / 'ios-after.json',
             ROOT / 'ios-app/NoteCorePreview/NoteCorePreviewApp.swift',
             ROOT / 'ios-app/NoteCorePreviewTests/PagedHostTests.swift',
             ROOT / 'ios-app/NoteCorePreviewTests/BrushRenderHostTests.swift',
             ROOT / 'docs/owner交接与失败责任矩阵.md']
    for path in paths:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    report = {
        'schema': 'owner-reference-1', 'status': 'passed',
        'scope': 'Original Apple App main-owner explicit service lifetime; Simulator host tests, no ordinary GUI or physical-device claim',
        'swift_host_tests': host['test_count'], 'required_tests': sorted(tests),
        'counterexample': 'Both pre-retirement reference-drop tests failed on original Model: failed accepted write owner vanished; held CG provider outlived its Model.',
        'decision': 'Reuse original self-retention field from init until explicit retirement confirms original ledger/native holders/worker drained. Recover via a retained route to the same owner, never a replacement authority.',
        'original_note_files_preserved': len(originals),
        'limits': ['All external handles lost leaves an inaccessible retained service; caller must retain a route and explicitly retire',
                   'No mutable thread transfer, dead-owner-thread takeover or process durability',
                   'No new Android/shared Native run, total physical heap, M-1 or pen acceptance'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/owner-reference.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
