#!/usr/bin/env python3
"""Collect real Gradle XML results; never invent native/device/durability verification."""
import json
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

TEXT_EXTENSIONS = {'.kt', '.kts', '.py', '.json', '.md', '.sh', '.properties', '.xml'}

def digest(path):
    """Match verifyPrototype: normalize CRLF in text inputs so a Windows
    checkout reproduces the same stamp; binary fixtures stay byte-exact."""
    data = path.read_bytes()
    if path.suffix.lower() in TEXT_EXTENSIONS:
        data = data.replace(b'\r\n', b'\n')
    return hashlib.sha256(data).hexdigest()


def main():
    architecture = subprocess.run([sys.executable, str(ROOT/'tools/check_architecture.py')],
                                  check=True, capture_output=True, text=True)
    catalog = subprocess.run([sys.executable, str(ROOT/'tools/validate_fixture_catalog.py')],
                             check=True, capture_output=True, text=True)
    suites = [p for module in ('canvas-core', 'shared-core') for p in (ROOT/module/'build/test-results/jvmTest').glob('TEST-*.xml')]
    if not suites:
        raise SystemExit('No JVM test XML. Run verifyPrototype first; a source file count is not a test result.')
    inputs = sorted((ROOT/'canvas-core/src').rglob('*.kt')) + sorted((ROOT/'shared-core/src').rglob('*.kt')) + sorted((ROOT/'fixtures').rglob('*.json')) + sorted((ROOT/'tools').glob('*.py')) + [
        ROOT/'settings.gradle.kts', ROOT/'build.gradle.kts',
        ROOT/'canvas-core/build.gradle.kts', ROOT/'shared-core/build.gradle.kts', ROOT/'gradle.properties', ROOT/'ci/verify.sh',
    ] + sorted((ROOT/'.generated-fixtures/scale').glob('*.bin')) + sorted((ROOT/'.generated-fixtures/scale').glob('verified-manifest.json'))
    stamp_path = ROOT/'reports/gradle-verification.sha256'
    if not stamp_path.exists():
        raise SystemExit('No Gradle verification stamp. Run the root verifyPrototype task first.')
    stamp = {}
    for line in stamp_path.read_text(encoding='utf-8').splitlines():
        recorded_digest, relative = line.split('  ', 1)
        stamp[relative] = recorded_digest
    actual = {p.relative_to(ROOT).as_posix(): digest(p) for p in inputs + suites}
    if actual != stamp:
        raise SystemExit('Source/configuration or test XML differs from the successful Gradle verification stamp. Rerun verifyPrototype.')
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    results = []
    for path in sorted(suites):
        suite = ET.parse(path).getroot()
        for key in totals: totals[key] += int(suite.attrib.get(key, 0))
        results.append({'suite': suite.attrib['name'], 'tests': int(suite.attrib['tests']),
                        'time_seconds': float(suite.attrib.get('time', 0))})
    sources = [p for module in ('canvas-core', 'shared-core') for p in sorted((ROOT/module/'src/commonMain').rglob('*.kt'))]
    report = {
        'phase': 'C0/C1 reference prototype',
        'status': 'passed' if totals['failures'] + totals['errors'] + totals['skipped'] == 0 else 'failed',
        'jvm_contract_tests': totals, 'suites': results,
        'module_jvm_tests': {module: sum(int(ET.parse(p).getroot().attrib['tests']) for p in suites if module in p.parts) for module in ('canvas-core', 'shared-core')},
        'architecture': json.loads(architecture.stdout),
        'fixture_catalog': json.loads(catalog.stdout),
        'source_sha256': {p.relative_to(ROOT).as_posix(): digest(p) for p in sources},
        'verification_input_sha256': {p.relative_to(ROOT).as_posix(): digest(p) for p in inputs},
        'android_build_verified': False, 'apple_build_verified': False,
        'disk_durability_verified': False, 'gpu_lifecycle_verified': False,
        'device_performance_verified': False, 'c1_architecture_gate_complete': False,
        'jvm_document_owner_thread_verified': any(s['suite'].endswith('.DocumentOwnerThreadTest') and s['tests'] >= 1 for s in results) and totals['failures'] + totals['errors'] + totals['skipped'] == 0,
        'jvm_owner_worker_threads_verified': any(s['suite'].endswith('.BoundedOwnerExecutorTest') and s['tests'] >= 15 for s in results) and totals['failures'] + totals['errors'] + totals['skipped'] == 0,
    }
    (ROOT/'reports').mkdir(exist_ok=True)
    (ROOT/'reports/verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['status'] != 'passed': raise SystemExit(1)

if __name__ == '__main__': main()
