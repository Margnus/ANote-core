#!/usr/bin/env python3
"""Bind raw budget validation to shared Native and original platform consumers, not C1 approval."""
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
    reports = {}; bindings = {}
    for name, fields in {
        'verification': ('source_sha256', 'verification_input_sha256'),
        'shared-native-contracts': ('source_and_artifact_sha256',),
        'preview-build': ('source_and_build_input_sha256',),
        'ios-preview-build': ('source_and_build_input_sha256',),
        'android-input-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
        'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256', 'input_sdk_contract_sha256'),
        'public-boundary-inventory': ('source_and_artifact_sha256',),
    }.items():
        path = ROOT / 'reports' / (name + '.json'); report = read(path)
        assert report['status'] == 'passed', name
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        bindings[path.relative_to(ROOT).as_posix()] = sha(path); reports[name] = report
    ios = reports['ios-host-tests']; android = reports['android-input-host-tests']
    assert 'testRawBudgetFailuresDoNotTrapAndPreviewStillChecksPolicy()' in ios['checks']
    assert android['ordinary_process_restored'] and android['all_original_files_unchanged']
    restore_path = ROOT / 'reports/ios-preview-restored.json'; restore = read(restore_path)
    assert restore['status'] == 'passed' and restore['no_xctest_plugin'] and restore['ordinary_package_restored_after_host_tests']
    assert restore['artifact_sha256'] == reports['ios-preview-build']['sha256']
    for relative, digest in restore['input_sha256'].items():
        assert sha(ROOT / relative) == digest, relative
    originals = read(ROOT / 'build/android-input-host/original-files.json')
    assert len(originals) == 66
    assert {Path(k).name: v for k, v in originals.items()} == read(ROOT / 'build/catalog-budget/android-after.json')
    before = read(ROOT / 'build/budget-creation/ios-before.json'); after = read(ROOT / 'build/budget-creation/ios-after.json')
    assert before == after == read(ROOT / 'build/remote-sync-20261026/ios-after.json') and len(after) == 14
    for platform, values in [('android', originals), ('ios', after)]:
        for name, digest in values.items():
            assert sha(ROOT / 'build/pending-input-budget' / (platform + '-originals') / Path(name).name) == digest
    paths = [Path(__file__), restore_path, ROOT / 'build/android-input-host/original-files.json',
             ROOT / 'build/budget-creation/ios-before.json', ROOT / 'build/budget-creation/ios-after.json',
             ROOT / 'shared-core/src/commonMain/kotlin/dev/notecore/document/DocumentCommand.kt',
             ROOT / 'shared-core/src/commonTest/kotlin/dev/notecore/document/DocumentBudgetCreationTest.kt',
             ROOT / 'docs/最小预览消费者契约.md']
    for path in paths:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    result = {
        'schema': 'budget-creation-1', 'status': 'passed',
        'decision': 'Use DocumentBudget.validated for raw numeric values before notebook creation. The original constructor shares the same range predicate and retains its trusted precondition. Original Android/Apple preview policies consume the result. No second authority, implicit owner transfer, clamping, allocator recovery or budget reservation.',
        'checks': ['nine field-specific failures without constructing a budget', 'count min/max and negative byte extremes',
                   'zero bytes, Long.MAX_VALUE and defaults preserved exactly', 'general validity remains distinct from preview support/capacity',
                   'valid budget opens the original owner; wrong-owner still rejects', 'actual Swift invalid raw values return typed failures without trapping'],
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'shared_native_tests': reports['shared-native-contracts']['test_count'], 'swift_host_tests': ios['test_count'],
        'android_host_checks': [android['check_count'], android['storage_retention_check_count'], android['owner_retirement_check_count']],
        'original_files_preserved': {'android': 66, 'apple': 14},
        'public_declaration_scope': 'The compiler header includes the new no-NSError typed factory; declaration inventory is not exhaustive exception conformance.',
        'limits': ['Raw constructor/copy, enum/null, indexes and owner callbacks still have programmer preconditions',
                   'Long.MAX_VALUE is an unchanged accepted value, not prepaid physical memory or an admission guarantee',
                   'No ordinary OS multiwindow/picker, physical M-1/heap/stylus, power-loss durability or stable ABI claim'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/budget-creation.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
