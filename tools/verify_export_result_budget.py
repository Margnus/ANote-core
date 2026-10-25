#!/usr/bin/env python3
"""Verify original Swift export result capacity and owner retention evidence."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    bindings = {}
    for name, fields in {
        'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256'),
        'ios-preview-build': ('source_and_build_input_sha256',),
        'ios-preview-restored': ('input_sha256',),
    }.items():
        path = ROOT / 'reports' / (name + '.json')
        report = read(path)
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                assert sha(ROOT / relative) == digest, (name, relative)
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    test = 'testExportResultCapacityRefusalKeepsSourceAndExactChargeUntilConsumerRelease()'
    host = read(ROOT / 'reports/ios-host-tests.json')
    assert test in host['checks']
    restored = read(ROOT / 'reports/ios-preview-restored.json')
    assert restored['no_xctest_plugin'] and restored['ordinary_package_restored_after_host_tests'] and not restored['data_deleted']
    folder = ROOT / 'build/export-result-budget'
    before = read(ROOT / 'build/producer-scratch/ios-after.json')
    assert len(before) == 14 and before == read(folder / 'ios-after.json')
    for name, digest in before.items():
        assert sha(ROOT / 'build/pending-input-budget/ios-originals' / name) == digest
    summary = read(folder / 'before-summary.json')
    assert any(failure['testName'] == test and 'XCTAssertNil failed' in failure['failureText']
               and 'PreviewBackup' in failure['failureText'] for failure in summary['testFailures'])
    baseline = (folder / 'before-model.swift').read_text(encoding='utf-8')
    assert 'exportResultByteLimit' in baseline and 'resultLimit - 512' not in baseline
    paths = [Path(__file__), ROOT / 'build/producer-scratch/ios-after.json', folder / 'ios-after.json',
             folder / 'before-model.swift', folder / 'before-summary.json', folder / 'before-test.log',
             ROOT / 'ios-app/NoteCorePreview/NoteCorePreviewApp.swift',
             ROOT / 'ios-app/NoteCorePreviewTests/PagedHostTests.swift']
    for path in paths:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    report = {
        'schema': 'export-result-budget-1', 'status': 'passed',
        'scope': 'Original Swift Model/worker/UI result holder; actual app-host file/capacity/retry/retirement regression, no ordinary picker or physical heap claim',
        'swift_host_tests': host['test_count'], 'required_test': test,
        'counterexample': 'With result limit configured but no admission, original export produced PreviewBackup despite zero capacity; retained xcresult summary and source bind this failure.',
        'decision': 'Original worker counts UTF8 before allocating Data; default owner result quota 32MiB includes 512 fixed logical bytes. Typed capacity refusal preserves source. One private-set immutable backup remains charged until finishExport releases the owner reference.',
        'checks': ['zero/one-byte-short/exact Unicode UTF8 boundary',
                   'source root identity and saved file bytes unchanged by refusal',
                   'same owner retries smaller result at unchanged quota',
                   'repeated prepare cannot replace accepted result',
                   'post-worker result stays charged and blocks retirement until explicit consumer release',
                   'release clears charge, permits a fresh export and normal owner retirement'],
        'original_ios_files_unchanged': len(before),
        'limits': ['Logical fixed overhead is not measured heap or Foundation/SwiftUI/system picker private copies',
                   'No new Android/shared Native run or ordinary GUI/picker acceptance',
                   'No complete chain peak quota, abrupt owner transfer, unconfirmed IO or power-loss proof'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/export-result-budget.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
