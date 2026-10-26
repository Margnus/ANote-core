#!/usr/bin/env python3
"""Bind explicit editing retirement evidence; never infer heap, native receipt or durability."""
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
    xml = ROOT / 'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.RetiredEditingStateTest.xml'
    suite = ET.parse(xml).getroot()
    assert int(suite.attrib['tests']) == 4 and not suite.findall('.//failure') and not suite.findall('.//skipped')
    sample = ROOT / 'build/retired-editing-state/sample'
    before = json.loads((sample / 'notecore-before.json').read_text(encoding='utf-8'))
    assert before == json.loads((sample / 'notecore-after.json').read_text(encoding='utf-8'))
    original = json.loads((ROOT / 'build/retired-editing-state/before-files.json').read_text(encoding='utf-8'))
    assert before['files'] == original and android['original_file_count'] == len(original)
    log = (sample / 'instrumentation.log').read_text(encoding='utf-8')
    assert 'INSTRUMENTATION_RESULT: status=passed' in log and 'INSTRUMENTATION_CODE: -1' in log
    for line in (ROOT / 'build/sketch-host/build-inputs.sha256').read_text(encoding='utf-8').splitlines():
        digest, relative = line.split('  ', 1)
        assert sha(ROOT / relative) == digest, relative
        bindings[relative] = digest
    for relative in ['tools/verify_retired_editing_state.py', 'tools/run_sketch_host.py',
                     'build/retired-editing-state/build.log', 'build/retired-editing-state/before-files.json',
                     'build/retired-editing-state/sample/notecore-before.json',
                     'build/retired-editing-state/sample/notecore-after.json',
                     'build/retired-editing-state/sample/instrumentation.log',
                     'build/retired-editing-state/sample/device.json', xml.relative_to(ROOT).as_posix()]:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'retired-editing-state-1', 'status': 'passed',
        'scope': 'Explicit original notebook retirement only: current JVM and Android original owner/file-worker checks and sample instrumentation. No sudden owner loss, Apple current execution or physical heap claim.',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'editing_retirement_checks': [case.attrib['name'] for case in suite.findall('testcase')],
        'android_view_checks': android['check_count'], 'android_storage_checks': android['storage_retention_check_count'],
        'android_owner_checks': android['owner_retirement_check_count'], 'android_api': android['api'],
        'original_file_count': android['original_file_count'], 'independent_sample_instrumentation_passed': True,
        'decision': 'Retire session-only journal/history/identity/epochs in the original authority after successful notebook retirement guards; keep exact immutable root and pending recovery responsibility. Closed authority refuses all commits before journal replay. Original save ledger/worker/native pool retain their own obligations.',
        'limits': ['Caller-held canonical snapshots and pending commands remain retained and charged; no GC or physical heap-release proof',
                   'Current Apple Native/framework/Swift host, ordinary UI, physical M-1, complete public and owner/thread transfer remain pending',
                   'Whole-chain peak admission, unconfirmed IO interruption and power loss remain open'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT / 'reports/retired-editing-state.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
