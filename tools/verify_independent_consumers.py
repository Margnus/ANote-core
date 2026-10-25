#!/usr/bin/env python3
"""Bind both actual independent memory consumers to current core inputs; no SDK freeze."""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'build/independent-consumers/android'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    bindings = {}
    ios_path = ROOT / 'reports/second-host-ios.json'; ios = read(ios_path)
    assert ios['status'] == 'passed' and ios['test_count'] == 6 and ios['ordinary_ui_verified']
    for relative, digest in ios['source_and_artifact_sha256'].items():
        assert sha(ROOT / relative) == digest, ('Apple', relative)
    bindings[ios_path.relative_to(ROOT).as_posix()] = sha(ios_path)
    stamp = CASE / 'build-inputs.sha256'
    for line in stamp.read_text(encoding='utf-8').splitlines():
        digest, relative = line.split('  ', 1)
        assert sha(ROOT / relative) == digest, ('Android', relative)
        bindings[relative] = digest
    projects = (CASE / 'runtime-projects.txt').read_text(encoding='utf-8').splitlines()
    assert projects == [':canvas-core', ':samples:sketch-host', ':shared-core']
    output = (CASE / 'instrumentation.log').read_text(encoding='utf-8')
    assert 'INSTRUMENTATION_RESULT: status=passed' in output and 'INSTRUMENTATION_CODE: -1' in output
    for key in ('input_policy_resources', 'lifecycle_owner'):
        assert 'INSTRUMENTATION_RESULT: ' + key + '=passed:' in output
    point_line = re.search(r'actual_points=(.+)', output).group(1)
    assert point_line.count('(') == 4
    before = read(CASE / 'notecore-before.json'); after = read(CASE / 'notecore-after.json')
    assert before == after and len(before['files']) == 66
    main_build = read(ROOT / 'reports/preview-build.json')
    assert before['apk_sha256'] == main_build['android']['sha256']
    device = read(CASE / 'device.json')
    assert device['avd'] == 'NoteCorePreview' and device['api'] == '36'
    apk = ROOT / 'samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk'
    test_apk = ROOT / 'samples/sketch-host/build/outputs/apk/androidTest/debug/sketch-host-debug-androidTest.apk'
    assert sha(apk) == device['installed_sample_apk_sha256'] and sha(test_apk) == device['installed_test_apk_sha256']
    with zipfile.ZipFile(apk) as archive:
        dex = [archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
    assert dex and not any(b'Ldev/notecore/app/' in block for block in dex), 'Sample APK contains App classes'
    issues = ET.parse(ROOT / 'samples/sketch-host/build/reports/lint-results-debug.xml').getroot().findall('issue')
    assert not any(issue.attrib['severity'] in ('Error', 'Fatal') for issue in issues)
    for path in [Path(__file__), *CASE.glob('*.json'), stamp, CASE / 'runtime-projects.txt', CASE / 'instrumentation.log',
                 ROOT / 'build/independent-consumers/android-build.log', ROOT / 'tools/run_sketch_host.py',
                 ROOT / 'reports/preview-build.json', ROOT / 'docs/最小预览消费者契约.md',
                 ROOT / 'samples/sketch-host-ios/README.md', ROOT / 'samples/sketch-host/README.md']:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    report = {
        'schema': 'independent-consumers-1', 'status': 'passed',
        'scope': 'Current original core consumed by independent Android and Apple memory-sketch applications, not App-internal tests or a production SDK approval',
        'android': {'bundle': 'dev.notecore.sample.sketch', 'instrumentation_groups': ['input_policy_resources', 'lifecycle_owner'],
                    'runtime_projects': projects, 'apk_sha256': sha(apk), 'test_apk_sha256': sha(test_apk),
                    'lint_errors': 0, 'lint_warnings': len(issues), 'original_notecore_files_unchanged': 66,
                    'current_ordinary_ui_verified': False},
        'apple': {'bundle': ios['bundle'], 'tests': 6, 'artifact_sha256': ios['artifact_sha256'],
                  'ordinary_ui_verified': True, 'original_notecore_payload_unchanged': True,
                  'original_notecore_files_unchanged': ios['original_notecore_note_count_unchanged']},
        'decision': 'Adopt the direct original validated notebook as both memory-host authorities; inject host tools/background and explicit read-only/owner policy. Preserve existing core typed input results, immutable checkpoint sharing and lifecycle responsibility.',
        'limits': ['Only the current fixed-page MONOLINE memory subset; no persistence/production ABI or hidden exported-type approval',
                   'Apple ordinary CUA is separately bound; current Android evidence is instrumentation, old UI scope is not inherited',
                   'Owner refusal is not mutable session transfer or proof against arbitrary Swift precondition traps',
                   'No complete aggregate quota, physical heap/stylus, process recovery or power-loss validation'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/independent-consumers.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
