#!/usr/bin/env python3
"""Bind producer admission evidence without inheriting old Apple or ordinary UI results."""
import hashlib
import json
from pathlib import Path
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
    assert android['observations']['active_input_reservation_bytes'] == 1_329_280
    sample = ROOT / 'build/producer-input-budget/sample'
    before = json.loads((sample / 'notecore-before.json').read_text(encoding='utf-8'))
    assert before == json.loads((sample / 'notecore-after.json').read_text(encoding='utf-8'))
    log = (sample / 'instrumentation.log').read_text(encoding='utf-8')
    assert 'INSTRUMENTATION_RESULT: status=passed' in log and 'INSTRUMENTATION_CODE: -1' in log
    for line in (ROOT / 'build/sketch-host/build-inputs.sha256').read_text(encoding='utf-8').splitlines():
        digest, relative = line.split('  ', 1)
        assert sha(ROOT / relative) == digest, relative
        bindings[relative] = digest
    for relative in ['tools/verify_producer_input_budget.py', 'tools/run_sketch_host.py',
                     'shared-core/src/iosMain/kotlin/dev/notecore/document/ApplePreviewInput.kt',
                     'ios-app/NoteCorePreview/NativeInkCanvas.swift',
                     'build/producer-input-budget/build.log', 'build/producer-input-budget/before-files.json',
                     'build/producer-input-budget/sample/notecore-before.json',
                     'build/producer-input-budget/sample/notecore-after.json',
                     'build/producer-input-budget/sample/instrumentation.log',
                     'build/producer-input-budget/sample/device.json']:
        bindings[relative] = sha(ROOT / relative)
    report = {
        'schema': 'producer-input-budget-1', 'status': 'passed',
        'scope': 'Current JVM and Android original View/worker and independent sample instrumentation. Apple source changes are not current native execution evidence.',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'],
        'producer_declared_bytes': 198656, 'default_20k_input_reservation_bytes': 1329280,
        'android_view_checks': android['check_count'], 'android_storage_checks': android['storage_retention_check_count'],
        'android_owner_checks': android['owner_retirement_check_count'], 'android_api': android['api'],
        'original_file_count': android['original_file_count'], 'independent_sample_instrumentation_passed': True,
        'decision': 'Reserve bounded producer overlap in original notebook input quota; check before platform scalar copying and recheck at original offer. Keep explicit whole-batch refusal and exact pending responsibility, no new pool or authority.',
        'limits': ['Declared logical buffers/container allowance only; no runtime/OS heap cap',
                   'Apple Native/framework/Swift host and ordinary UI are pending current validation',
                   'Projection containers, full-chain total budget, physical M-1, owner transfer and unconfirmed IO/power loss remain open'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT / 'reports/producer-input-budget.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
