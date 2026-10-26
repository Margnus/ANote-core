#!/usr/bin/env python3
"""Bind the existing index's canonical point-query reference, not an App or production index claim."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    bindings = {}
    reports = {}
    for name, fields in [('verification', ('source_sha256', 'verification_input_sha256')),
                         ('android-brush-common-tests', ('source_and_build_sha256',))]:
        path = ROOT / 'reports' / (name + '.json')
        report = json.loads(path.read_text(encoding='utf-8'))
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        reports[name] = report
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    folder = ROOT / 'build/canonical-point-query'
    xml = ROOT / 'canvas-core/build/test-results/jvmTest/TEST-dev.notecore.canvas.prototype.IncrementalSpatialIndexPocTest.xml'
    suite = ET.parse(xml).getroot()
    assert int(suite.attrib['tests']) == 13 and int(suite.attrib['failures']) == 0 and int(suite.attrib['errors']) == 0
    original = json.loads((folder / 'original-before.json').read_text(encoding='utf-8'))
    assert original == json.loads((folder / 'original-after.json').read_text(encoding='utf-8'))
    art = reports['android-brush-common-tests']
    assert art['test_count'] == 139 and 'IncrementalSpatialIndexPocTest' in art['test_groups']
    assert sha(ROOT / art['test_apk']) == art['test_apk_sha256']
    assert sha(ROOT / 'canvas-core/build/android-brush-tests/run.log') == art['test_output_sha256']
    for relative in ['tools/verify_canonical_point_query.py', 'tools/verify_apple_brush_common.py',
                     'build/canonical-point-query/build.log', 'build/canonical-point-query/original-before.json',
                     'build/canonical-point-query/original-after.json', art['test_apk'],
                     'canvas-core/build/android-brush-tests/run.log', xml.relative_to(ROOT).as_posix()]:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'canonical-point-query-1', 'status': 'passed',
        'scope': 'Existing internal STR/overlay, original SceneReducer patches and original coverage oracle on current JVM and ART. No ordinary App, editing tool or production-index adoption.',
        'index_jvm_tests': 13, 'new_canonical_reference_tests': 4,
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'], 'art_tests': art['test_count'],
        'android_api': art['api'], 'original_notes': len(original['files']), 'ordinary_app_apk_and_files_unchanged': True,
        'decision': 'Adopt validated canonical binding and complete-or-refused geometric point filtering in the original index reference. Generic bbox updates revoke canonical approval; real patches, stable handles and content/session stamps govern publication. No duplicate samples, geometry rule, index authority or worker retained.',
        'limits': ['Stable handle assignment and final current-stamp publication remain owner responsibilities',
                   'Canonical update checks unchanged object references linearly, bounded by explicit metadata quota; no production incremental timing/heap or grid/STR selection claim',
                   'Point filtering uses the floating version-1 oracle, not AA pixels, z order, lasso, eraser UI or arbitrary near-boundary exact predicates',
                   'Current Apple Native, ordinary UI, physical M-1, full budgets/public/owner/IO and C1 remain pending'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT / 'reports/canonical-point-query.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
