#!/usr/bin/env python3
"""Audit retained evidence bindings, not execution, device measurements or C1 approval."""
import hashlib
import json
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]
HISTORICAL = ('spatial-index-poc', 'brush-path-costs', 'brush-reduction-costs',
              'chunked-brush-costs', 'brush-tile-cache-costs')
CURRENT = {
    'verification': ('source_sha256', 'verification_input_sha256'),
    'android-brush-common-tests': ('source_and_build_sha256',),
    'apple-brush-common-tests': ('source_and_build_sha256',),
    'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256', 'input_sdk_contract_sha256'),
    'render-viewport-trace': ('source_report_sha256',),
    'canonical-title-contract': ('source_and_artifact_sha256',),
    'public-boundary-inventory': ('source_and_artifact_sha256',),
    'preview-edit-contract': ('source_and_artifact_sha256',),
    'preview-input-contract': ('source_and_artifact_sha256',),
    'ios-preview-ui': ('source_and_artifact_sha256',),
    'ios-ordinary-recovery-ui': ('source_and_artifact_sha256',),
    'android-ordinary-recovery-ui': ('source_and_artifact_sha256',),
    'second-host-android': ('source_and_artifact_sha256',),
    'recovery-framing': ('source_and_artifact_sha256',),
    'validated-creation': ('source_and_artifact_sha256',),
    'typed-recovery-read': ('source_and_artifact_sha256',),
    'read-owner-outcomes': ('source_and_artifact_sha256',),
    'identity-retention': ('source_and_artifact_sha256',),
    'pending-input-budget': ('source_and_artifact_sha256',),
    'producer-input-budget': ('source_and_artifact_sha256',),
    'live-projection-budget': ('source_and_artifact_sha256',),
    'input-capture-contract': ('source_and_fixture_sha256',),
    'apple-current-revalidation': ('source_and_artifact_sha256',),
    'shared-native-contracts': ('source_and_artifact_sha256',),
    'android-input-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    'android-render-integration': ('source_evidence_sha256', 'sdk_contract_source_sha256'),
    'sqlite-platform-capabilities': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    'incremental-spatial-index': ('source_and_artifact_sha256',),
    'pdf-platform-capabilities': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
}
ARTIFACTS = {
    'android-brush-common-tests': (('test_apk_sha256', None),
        ('test_output_sha256', 'canvas-core/build/android-brush-tests/run.log')),
    'apple-brush-common-tests': (('test_binary_sha256', 'canvas-core/build/bin/iosSimulatorArm64/debugTest/test.kexe'),
        ('test_output_sha256', 'canvas-core/build/apple-brush-tests/run.log')),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def binding(root, relative, expected, normalized=False):
    path = root / relative
    if not path.is_file():
        return {'path': relative, 'state': 'missing', 'expected': expected}
    actual = verification_digest(path) if normalized else digest(path)
    return {'path': relative, 'state': 'matched' if actual == expected else 'changed',
            'expected': expected, 'actual': actual}


def audit(root):
    reports = {}
    errors = []
    for name in (*HISTORICAL, *CURRENT):
        path = root / 'reports' / (name + '.json')
        if not path.is_file():
            errors.append('Missing report: ' + name)
            continue
        data = json.loads(path.read_text(encoding='utf-8'))
        historical = name in HISTORICAL
        fields = ('source_sha256',) if historical else CURRENT[name]
        checks = []
        for field in fields:
            recorded = data.get(field, {})
            if not recorded:
                errors.append(name + ': missing binding map ' + field)
            for relative, sha in recorded.items():
                checks.append(binding(root, relative, sha, normalized=name == 'verification'))
        for field, relative in ARTIFACTS.get(name, ()):
            checks.append(binding(root, relative or data['test_apk'], data[field]))
        for field, relative in (('fixture_sha256', 'S2-ink-dense-page.bin'),
                                ('dense_fixture_sha256', 'S2-ink-dense-page.bin'),
                                ('long_fixture_sha256', 'S9-ink-long-stroke.bin')):
            if field in data:
                checks.append(binding(root, '.generated-fixtures/scale/' + relative, data[field]))
        mismatches = [item for item in checks if item['state'] != 'matched']
        evidence_status = data.get('evidence_status') if name == 'android-render-integration' else data.get('status')
        if evidence_status != 'passed':
            errors.append(name + ': report did not pass')
        if not historical and mismatches:
            errors.append(name + ': current evidence binding failed')
        reports[name] = {
            'report_sha256': digest(path),
            'role': 'historical_experiment' if historical else 'current_regression',
            'binding_state': 'historical_drift' if historical and mismatches else 'failed' if mismatches else 'matched',
            'matched_bindings': len(checks) - len(mismatches), 'mismatches': mismatches,
            'scope': data.get('scope', data.get('phase')),
        }
    return {
        'schema': 'c1-evidence-audit-1', 'status': 'failed' if errors else 'passed',
        'reports': reports, 'errors': errors,
        'scope': 'Read-only rehash of recorded inputs and retained artifacts; no rerun or completeness proof. Historical drift is explicit, never promoted to current benchmark evidence. Current binding failure blocks this audit. Decisions remain in ADR-0020 and C1未决事项.md.',
        'ordinary_app_backend_integration_verified_by_this_audit': False, 'c1_gate_complete': False,
        'physical_device_or_heap_measured': False,
    }


def main():
    report = audit(ROOT)
    report['audit_tool_sha256'] = digest(Path(__file__))
    (ROOT / 'reports/c1-evidence-audit.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': report['status'], 'reports': {
        name: value['binding_state'] for name, value in report['reports'].items()}, 'errors': report['errors']}))
    if report['errors']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
