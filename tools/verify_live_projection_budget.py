#!/usr/bin/env python3
"""Verify current projection envelope evidence; no physical heap or old Apple result inheritance."""
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
                         ('android-input-host-tests', ('source_and_artifact_sha256',))]:
        path = ROOT / 'reports' / (name + '.json')
        report = json.loads(path.read_text(encoding='utf-8'))
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        reports[name] = report
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    android = reports['android-input-host-tests']
    assert android['all_original_files_unchanged']
    xml = ROOT / 'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.PageProjectionBudgetTest.xml'
    suite = ET.parse(xml).getroot()
    assert int(suite.attrib['tests']) == 4 and not suite.findall('.//failure') and not suite.findall('.//skipped')
    sample = ROOT / 'build/live-projection-budget/sample'
    before = json.loads((sample / 'notecore-before.json').read_text(encoding='utf-8'))
    assert before == json.loads((sample / 'notecore-after.json').read_text(encoding='utf-8'))
    original = json.loads((ROOT / 'build/live-projection-budget/before-files.json').read_text(encoding='utf-8'))
    assert before['files'] == original and android['original_file_count'] == len(original)
    log = (sample / 'instrumentation.log').read_text(encoding='utf-8')
    assert 'INSTRUMENTATION_RESULT: status=passed' in log and 'INSTRUMENTATION_CODE: -1' in log
    for line in (ROOT / 'build/sketch-host/build-inputs.sha256').read_text(encoding='utf-8').splitlines():
        digest, relative = line.split('  ', 1)
        assert sha(ROOT / relative) == digest, relative
        bindings[relative] = digest
    for relative in ['tools/verify_live_projection_budget.py', 'tools/run_sketch_host.py',
                     'build/live-projection-budget/build.log', 'build/live-projection-budget/before-files.json',
                     'build/live-projection-budget/sample/notecore-before.json',
                     'build/live-projection-budget/sample/notecore-after.json',
                     'build/live-projection-budget/sample/instrumentation.log',
                     'build/live-projection-budget/sample/device.json', xml.relative_to(ROOT).as_posix()]:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'live-projection-budget-1', 'status': 'passed',
        'scope': 'Current JVM projection/live admission semantics, Android original View/worker and separate sample instrumentation. No current Apple, ordinary UI or physical heap evidence.',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'projection_checks': [case.attrib['name'] for case in suite.findall('testcase')],
        'android_view_checks': android['check_count'], 'android_storage_checks': android['storage_retention_check_count'],
        'android_owner_checks': android['owner_retirement_check_count'], 'android_api': android['api'],
        'original_file_count': android['original_file_count'], 'independent_sample_instrumentation_passed': True,
        'decision': 'Reuse original immutable scene visitor; allocate exact page references without an all-scene list. Prepay the sole notebook projection in original live quota (zero empty, otherwise 128+8*N), including initial/DOWN/direct/copy/delta/history gates. Clear old cache before authority submission; refusal preserves canonical content and the authority identity ledger.',
        'limits': ['Logical single retained projection capacity; external caller-owned lists, command temporaries and physical GC/heap are not a whole-chain peak guarantee',
                   'Apple Native/framework/Swift host, ordinary UI, physical M-1 and complete public/owner transfer remain pending',
                   'Full-chain input/content/history/render/save peak admission and unconfirmed IO/power loss remain open'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT / 'reports/live-projection-budget.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
