#!/usr/bin/env python3
"""Audit retained evidence bindings, not execution, device measurements or C1 approval."""
import argparse
from collections import Counter
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import subprocess
from verification_hash import TEXT_SUFFIXES, verification_digest

ROOT = Path(__file__).resolve().parents[1]
HISTORICAL = ('spatial-index-poc', 'brush-path-costs', 'brush-reduction-costs',
              'chunked-brush-costs', 'brush-tile-cache-costs')
CURRENT = {
    'dev041-android-source-handoff-20261122': ('source_sha256', 'raw_sha256'),
    'dev041-apple-source-handoff-20261122': ('source_sha256', 'raw_sha256'),
    'remote-integration-20261029': ('source_and_artifact_sha256',),
    'android-local-file-identity': ('source_and_artifact_sha256',),
    'apple-local-file-identity': ('source_and_artifact_sha256',),
    'second-host-android-current': ('source_and_artifact_sha256',),
    'consumer-current-contracts': ('source_and_artifact_sha256',),
    'android-budget-overlap': ('source_and_artifact_sha256',),
    'viewport-native-contracts': ('source_and_artifact_sha256',),
    'owner-budget-overlap': ('source_and_artifact_sha256',),
    'apple-atomic-cut': ('source_and_artifact_sha256',),
    'dev041-original-atomic-revalidation-20261118': ('source_sha256',),
    'android-atomic-cut': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    'read-traversal': ('source_and_artifact_sha256',),
    'budget-creation': ('source_and_artifact_sha256',),
    'editor-presentation': ('source_and_artifact_sha256',),
    'apple-window-attachments': ('source_and_artifact_sha256',),
    'editor-attachments': ('source_and_artifact_sha256',),
    'surface-input': ('source_and_artifact_sha256',),
    'owner-reference': ('source_and_artifact_sha256',),
    'android-authorized-adb-ui': ('source_and_artifact_sha256',),
    'legacy-container-framing': ('source_and_artifact_sha256',),
    'resource-commit-rules': ('source_and_artifact_sha256',),
    'resource-gc-rules': ('source_and_artifact_sha256',),
    'dev041-resource-retirement-20261118': ('source_sha256',),
    'dev041-android-dir-sync-20261118': ('source_sha256',),
    'dev041-ios-dir-sync-20261118': ('source_sha256',),
    'c1-apple-failure-channels-20261118': ('source_sha256',),
    'c1-android-failure-channels-20261118': ('source_sha256',),
    'dev041-pending-recovery-20261118': ('source_sha256',),
    'dev041-highlighter-migration-20261119': ('source_sha256',),
    'dev041-android-highlighter-migration-20261119': ('inputs_sha256',),
    'dev041-apple-highlighter-migration-20261119': ('input_sha256',),
    'dev041-android-pending-recovery-20261118': ('inputs_sha256',),
    'dev041-apple-pending-recovery-20261118': ('input_sha256',),
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
    'second-host-ios': ('source_and_artifact_sha256',),
    'independent-consumers': ('source_and_artifact_sha256',),
    'recovery-framing': ('source_and_artifact_sha256',),
    'validated-creation': ('source_and_artifact_sha256',),
    'typed-recovery-read': ('source_and_artifact_sha256',),
    'read-owner-outcomes': ('source_and_artifact_sha256',),
    'identity-retention': ('source_and_artifact_sha256',),
    'pending-input-budget': ('source_and_artifact_sha256',),
    'producer-scratch': ('source_and_artifact_sha256',),
    'export-result-budget': ('source_and_artifact_sha256',),
    'catalog-budget': ('source_and_artifact_sha256',),
    'producer-input-budget': ('source_and_artifact_sha256',),
    'live-projection-budget': ('source_and_artifact_sha256',),
    'retired-editing-state': ('source_and_artifact_sha256',),
    'brush-oracle-numeric': ('source_and_artifact_sha256',),
    'preview-float-capability': ('source_and_artifact_sha256',),
    'canonical-point-query': ('source_and_artifact_sha256',),
    'input-capture-contract': ('source_and_fixture_sha256',),
    'apple-current-revalidation': ('source_and_artifact_sha256',),
    'shared-native-contracts': ('source_and_artifact_sha256',),
    'release-handoff': ('source_and_artifact_sha256',),
    'android-min-api-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    'android-path-api32-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    'android-input-host-tests': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    'android-render-integration': ('source_evidence_sha256', 'sdk_contract_source_sha256'),
    'sqlite-platform-capabilities': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
    'dev041-sqlite-capabilities-20261118': ('source_sha256',),
    'incremental-spatial-index': ('source_and_artifact_sha256',),
    'pdf-platform-capabilities': ('source_and_artifact_sha256', 'sdk_contract_sha256'),
}
REPORT_PATHS = {
    'dev041-android-source-handoff-20261122': 'reports/dev041-android-source-handoff-20261122/verification.json',
    'dev041-apple-source-handoff-20261122': 'reports/dev041-apple-source-handoff-20261122/verification.json',
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


class GitBindings:
    def __init__(self, root, revision):
        self.root = root
        self.commit = self.git('rev-parse', '--verify', '--end-of-options', revision + '^{commit}').decode().strip()
        self.blobs = self.tree(self.commit)
        self.current = self.tree('HEAD')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args])

    def tree(self, revision):
        files = {}
        for record in self.git('ls-tree', '-rz', revision).split(b'\0'):
            if record:
                header, relative = record.split(b'\t', 1)
                mode, kind, oid = header.decode().split()
                files[relative.decode()] = oid if kind == 'blob' and mode.startswith('100') else None
        return files

    def read(self, relative):
        oid = self.blobs.get(relative)
        return None if oid is None else self.git('cat-file', 'blob', oid)

    @lru_cache(maxsize=None)
    def digest(self, oid, normalize_lf):
        data = self.git('cat-file', 'blob', oid)
        if normalize_lf:
            data = data.replace(b'\r\n', b'\n')
        return hashlib.sha256(data).hexdigest()

    def binding(self, relative, expected, normalized=False):
        if relative not in self.blobs and relative not in self.current:
            return {**binding(self.root, relative, expected, normalized), 'origin': 'local_artifact'}
        oid = self.blobs.get(relative)
        if oid is None:
            return {'path': relative, 'state': 'missing', 'expected': expected, 'origin': 'git_revision'}
        actual = self.digest(oid, normalized and Path(relative).suffix.lower() in TEXT_SUFFIXES)
        return {'path': relative, 'state': 'matched' if actual == expected else 'changed',
                'expected': expected, 'actual': actual, 'origin': 'git_revision'}


def audit(root, revision=None, *, report_revision=None, names=None):
    git_bindings = GitBindings(root, revision) if revision else None
    report_bindings = GitBindings(root, report_revision) if report_revision else None
    selected = tuple(dict.fromkeys(names)) if names else (*HISTORICAL, *CURRENT)

    def inspect(relative, sha, normalized=False):
        if git_bindings:
            return git_bindings.binding(relative, sha, normalized)
        return binding(root, relative, sha, normalized)

    reports = {}
    errors = []
    for name in selected:
        relative = REPORT_PATHS.get(name, f'reports/{name}.json')
        report_folder = Path(relative).parent
        path = root / relative
        if report_bindings:
            raw_report = report_bindings.read(relative)
        else:
            raw_report = path.read_bytes() if path.is_file() else None
        if raw_report is None:
            errors.append('Missing report: ' + name)
            continue
        data = json.loads(raw_report)
        historical = name in HISTORICAL
        fields = ('source_sha256',) if historical else CURRENT[name]
        checks = []
        for field in fields:
            recorded = data.get(field, {})
            if not recorded:
                errors.append(name + ': missing binding map ' + field)
            for relative, sha in recorded.items():
                if field == 'raw_sha256':
                    relative = (report_folder / relative).as_posix()
                checks.append(inspect(relative, sha, normalized=name == 'verification'))
        for field, relative in ARTIFACTS.get(name, ()):
            checks.append(inspect(relative or data['test_apk'], data[field]))
        for field, relative in (('fixture_sha256', 'S2-ink-dense-page.bin'),
                                ('dense_fixture_sha256', 'S2-ink-dense-page.bin'),
                                ('long_fixture_sha256', 'S9-ink-long-stroke.bin')):
            if field in data:
                checks.append(inspect('.generated-fixtures/scale/' + relative, data[field]))
        mismatches = [item for item in checks if item['state'] != 'matched']
        mismatch_causes = Counter(item.get('origin', 'worktree') + ':' + item['state'] for item in mismatches)
        evidence_status = data.get('evidence_status') if name == 'android-render-integration' else data.get('status')
        if evidence_status != 'passed':
            errors.append(name + ': report did not pass')
        if not historical and mismatches:
            errors.append(name + ': current evidence binding failed')
        reports[name] = {
            'report_sha256': hashlib.sha256(raw_report).hexdigest(),
            'role': 'historical_experiment' if historical else 'current_regression',
            'binding_state': 'historical_drift' if historical and mismatches else 'failed' if mismatches else 'matched',
            'matched_bindings': len(checks) - len(mismatches),
            'mismatch_causes': dict(sorted(mismatch_causes.items())), 'mismatches': mismatches,
            **({'matched_git_bindings': sum(item['state'] == 'matched' and item['origin'] == 'git_revision' for item in checks),
                'matched_local_bindings': sum(item['state'] == 'matched' and item['origin'] == 'local_artifact' for item in checks)}
               if git_bindings else {}),
            'scope': data.get('scope', data.get('phase')),
        }
    return {
        'schema': 'c1-evidence-audit-1', 'status': 'failed' if errors else 'passed',
        'reports': reports, 'errors': errors,
        'selected_reports': list(selected), 'full_inventory_requested': set(selected) == {*HISTORICAL, *CURRENT},
        'report_revision': report_bindings.commit if report_bindings else None,
        **({'revision': git_bindings.commit, 'untracked_bindings_use_local_artifacts': True} if git_bindings else {}),
        'scope': 'Read-only rehash of selected reports and their recorded inputs; no rerun or completeness proof. Historical drift is explicit. Current binding failure blocks this audit. Decisions remain in ADR-0020 and C1未决事项.md.',
        'ordinary_app_backend_integration_verified_by_this_audit': False, 'c1_gate_complete': False,
        'physical_device_or_heap_measured': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'reports/c1-evidence-audit.json')
    parser.add_argument('--revision', help='Read tracked inputs from this Git commit and untracked artifacts locally')
    parser.add_argument('--report-revision', help='Read the report JSON itself from this Git commit')
    parser.add_argument('--report', action='append', choices=(*HISTORICAL, *CURRENT),
                        help='Audit only this report; repeat to audit multiple reports')
    args = parser.parse_args()
    report = audit(ROOT, args.revision, report_revision=args.report_revision, names=args.report)
    report['audit_tool_sha256'] = digest(Path(__file__))
    with args.output.open('w', encoding='utf-8', newline='\n') as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'reports': {
        name: value['binding_state'] for name, value in report['reports'].items()}, 'errors': report['errors']}))
    if report['errors']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
