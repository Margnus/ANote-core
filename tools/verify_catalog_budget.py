#!/usr/bin/env python3
"""Bind complete catalog/refusal/owner retention evidence on the original two consumers."""
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
    bindings = {}; reports = {}
    for name, fields in {
        'verification': ('source_sha256', 'verification_input_sha256'),
        'shared-native-contracts': ('source_and_artifact_sha256',),
        'android-input-host-tests': ('source_and_artifact_sha256',),
        'preview-build': ('source_and_build_input_sha256',),
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
        reports[name] = report
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    native = reports['shared-native-contracts']
    tests = [name for name in native['checks'] if '.PreviewCatalogBudgetTest.' in name]
    assert len(tests) == 3
    android = reports['android-input-host-tests']; ios = reports['ios-host-tests']
    assert 'catalog_capacity_failure_preserves_owner_snapshot' in android['storage_retention_checks']['checks']
    assert 'testCatalogCapacityPreservesCompleteOldRowsAndChargesRetainedOverlay()' in ios['checks']
    assert android['all_original_files_unchanged'] and android['ordinary_process_restored']
    restored = reports['ios-preview-restored']
    assert restored['no_xctest_plugin'] and restored['ordinary_package_restored_after_host_tests'] and not restored['data_deleted']
    folder = ROOT / 'build/catalog-budget'
    assert 'Catalog IO failure discarded original disk rows: []' in (folder / 'before-host.log').read_text(encoding='utf-8')
    counts = {}
    for platform in ('android', 'ios'):
        before = read(folder / (platform + '-before.json'))
        assert before == read(folder / (platform + '-after.json')) and before
        for name, digest in before.items():
            assert sha(ROOT / 'build/pending-input-budget' / (platform + '-originals') / name) == digest
        counts[platform] = len(before)
        for suffix in ('before', 'after'):
            path = folder / (platform + '-' + suffix + '.json'); bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    for path in [Path(__file__), folder / 'before-host.log', folder / 'before-store.kt',
                 ROOT / 'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewCatalogBudget.kt',
                 ROOT / 'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewCatalogBudgetTest.kt',
                 ROOT / 'android-app/src/main/java/dev/notecore/app/NoteStore.kt',
                 ROOT / 'android-app/src/main/java/dev/notecore/app/PreviewFileIO.kt',
                 ROOT / 'android-app/src/main/java/dev/notecore/app/NoteListAdapter.kt',
                 ROOT / 'android-app/src/androidTest/java/dev/notecore/app/CatalogRetentionHostChecks.kt',
                 ROOT / 'ios-app/NoteCorePreview/NoteCorePreviewApp.swift',
                 ROOT / 'ios-app/NoteCorePreview/PreviewFileIO.swift',
                 ROOT / 'ios-app/NoteCorePreviewTests/PagedHostTests.swift']:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    report = {
        'schema': 'catalog-budget-1', 'status': 'passed',
        'scope': 'Shared declared catalog sizing and original Android Store/Swift Model worker-owner complete metadata adoption; no production paged-library or physical heap claim',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'], 'native_tests': native['test_count'],
        'swift_host_tests': ios['test_count'], 'android_storage_groups': android['storage_retention_check_count'],
        'shared_catalog_tests': tests,
        'counterexample': 'Actual original Android Store lost both prior disk rows on real directory IO refusal; retained log/source show [] before owner snapshot adoption.',
        'decision': 'Use existing owner metadata snapshot; shared work reservation includes path/URL staging, header workspace and old/new result overlap. Check complete merged metadata against default 2MiB before merge/sort. Known disk-row capacity refusal is distinct from IO/unknown; failure preserves old rows and original ledger overlay. Clear owner snapshot at final retirement.',
        'checks': ['Unicode zero/one-byte-short/exact 1588-byte two-row metadata boundary',
                   'complete 2000 disk rows including 1998 maximum IDs/Unicode titles',
                   '2001 explicitly refused without partial publication or file mutation; repaired catalog refreshes',
                   'real directory IO refusal keeps complete old disk rows',
                   'eight failed saves overlay all 2000 disk rows; original ledger retry writes them after repair',
                   'owner retirement clears retained metadata charge'],
        'original_files_unchanged': counts,
        'limits': ['512 declared bytes per row and platform staging allowances are policies, not measured heap',
                   'Foundation/OS enumerator, UI/runtime capacities and reclamation lag remain unmeasured',
                   'No current ordinary GUI, physical device/stylus, full aggregate quota or power-loss verification'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/catalog-budget.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
