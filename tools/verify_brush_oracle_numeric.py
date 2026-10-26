#!/usr/bin/env python3
"""Bind reproduced oracle underflow and current JVM/ART regression evidence only."""
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
    folder = ROOT / 'build/brush-oracle-numeric'
    before = ET.parse(folder / 'before-fix.xml').getroot()
    after = ROOT / 'canvas-core/build/test-results/jvmTest/TEST-dev.notecore.canvas.prototype.BrushEnvelopePocTest.xml'
    fixed = ET.parse(after).getroot()
    assert int(before.attrib['tests']) == 10 and int(before.attrib['failures']) == 3
    assert int(fixed.attrib['tests']) == 10 and int(fixed.attrib['failures']) == 0 and int(fixed.attrib['errors']) == 0
    original = json.loads((folder / 'original-before.json').read_text(encoding='utf-8'))
    assert original == json.loads((folder / 'original-after.json').read_text(encoding='utf-8'))
    art = reports['android-brush-common-tests']
    assert art['test_count'] == 135 and 'BrushEnvelopePocTest' in art['test_groups']
    assert sha(ROOT / art['test_apk']) == art['test_apk_sha256']
    assert sha(ROOT / 'canvas-core/build/android-brush-tests/run.log') == art['test_output_sha256']
    for relative in ['tools/verify_brush_oracle_numeric.py', 'tools/verify_apple_brush_common.py',
                     'build/brush-oracle-numeric/before-fix.log', 'build/brush-oracle-numeric/before-fix.xml',
                     'build/brush-oracle-numeric/before-fix-bindings.json', 'build/brush-oracle-numeric/build.log',
                     'build/brush-oracle-numeric/original-before.json', 'build/brush-oracle-numeric/original-after.json',
                     art['test_apk'], 'canvas-core/build/android-brush-tests/run.log', after.relative_to(ROOT).as_posix()]:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'brush-oracle-numeric-1', 'status': 'passed',
        'scope': 'Original internal version-1 coverage oracle and existing geometry/path/mask consumers on current JVM and Android ART. No new ordinary brush or app rebuild.',
        'before_fix_failures': 3, 'oracle_jvm_tests': 10, 'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'art_tests': art['test_count'], 'android_api': art['api'],
        'original_notes': len(original['files']), 'ordinary_app_apk_and_files_unchanged': True,
        'decision': 'Normalize original distance and segment coefficients before squaring; reject effective zero radius before derived allocation. Preserve sampled data, swept-disc rule/version, alpha and existing goldens. Adopt as oracle numeric correction only.',
        'limits': ['Floating reference is not an arbitrary near-boundary exact predicate or production geometry/hit-test approval',
                   'No current Apple Native/offscreen, ordinary UI, physical stylus or heap/latency validation',
                   'M-1, complete C1/public/owner/budget and unconfirmed IO/power-loss gates remain open'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT / 'reports/brush-oracle-numeric.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
