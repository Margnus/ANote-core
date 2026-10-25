#!/usr/bin/env python3
"""Bind shared accepted-input identity and actual original dual-platform View evidence."""
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
    bindings = {}; reports = {}
    for name, fields in {
        'verification': ('source_sha256', 'verification_input_sha256'),
        'shared-native-contracts': ('source_and_artifact_sha256',),
        'android-input-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
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
        bindings[path.relative_to(ROOT).as_posix()] = sha(path); reports[name] = report
    native = reports['shared-native-contracts']; ios = reports['ios-host-tests']; android = reports['android-input-host-tests']
    assert any('.acceptedInputIdentityTracksOnlyOriginalDownAndNeverAliasesLaterStroke' in test for test in native['checks'])
    assert 'testSiblingSurfaceCannotCancelOrAppendAnotherAcceptedInput()' in ios['checks']
    assert 'sibling_surface_preserves_original_input_and_rejects_stale_events' in android['checks']
    assert android['all_original_files_unchanged'] and android['ordinary_process_restored']
    restored = reports['ios-preview-restored']
    assert restored['no_xctest_plugin'] and not restored['data_deleted']
    folder = ROOT / 'build/surface-input'
    before = read(folder / 'before-summary.json')
    assert before['totalTestCount'] == before['failedTests'] == 1
    assert any('idle sibling cancelled original accepted input' in failure['failureText'] for failure in before['testFailures'])
    originals = read(folder / 'ios-after.json')
    assert len(originals) == 14 and originals == read(ROOT / 'build/owner-reference/ios-after.json')
    for name, digest in originals.items():
        assert sha(ROOT / 'build/pending-input-budget/ios-originals' / name) == digest
    paths = [Path(__file__), folder / 'before-summary.json', folder / 'before.log',
             folder / 'before-native.swift', folder / 'before-native.kt', folder / 'ios-after.json',
             ROOT / 'docs/owner交接与失败责任矩阵.md',
             ROOT / 'shared-core/src/commonMain/kotlin/dev/notecore/document/PagedPreviewNotebook.kt',
             ROOT / 'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewInputTest.kt',
             ROOT / 'android-app/src/main/java/dev/notecore/app/InkCanvasView.kt',
             ROOT / 'android-app/src/androidTest/java/dev/notecore/app/AndroidInputHostChecks.kt',
             ROOT / 'ios-app/NoteCorePreview/NativeInkCanvas.swift',
             ROOT / 'ios-app/NoteCorePreviewTests/PagedHostTests.swift']
    for path in paths:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    result = {
        'schema': 'surface-input-1', 'status': 'passed',
        'scope': 'Original Android/iOS Views, synthetic native callbacks sharing exactly one Notebook; no OS multiwindow or physical input acceptance',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'], 'native_tests': native['test_count'],
        'swift_host_tests': ios['test_count'],
        'decision': 'A surface retains the original accepted DOWN operation identity and matches it before continuation/cancel/active-path drawing. Global App cancellation stays explicit; competing DOWN refuses before changing shared pen. No new identity, authority or content copy.',
        'counterexample': 'Original Apple idle sibling initial layout/cancel cancelled another surface accepted stroke; retained original source and actual failed XCTest.',
        'checks': ['idle sibling layout/cancel preserves prefix', 'competing DOWN cannot replace input or shared tool',
                   'global cancel and later DOWN leave old surface stale', 'stale MOVE/UP/cancel/dismantle cannot append/commit/cancel later input',
                   'canonical accepted two-point strokes commit once, original first object preserved',
                   'identity null after cancel/complete/retire, later DOWN does not alias, owner check retained'],
        'original_ios_files_preserved': len(originals),
        'limits': ['Same-document multiwindow App navigation/rendering, duplicate services for one directory and full SDK white list remain unapproved',
                   'A host must use the identity guard on the original owner thread; this does not make Core arbitrary foreign calls safe',
                   'No mutable thread takeover, complete chain physical heap, M-1/pen or power-loss evidence'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/surface-input.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
