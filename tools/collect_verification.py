#!/usr/bin/env python3
"""Collect real Gradle XML results; never invent native/device/durability verification."""
import json
import hashlib
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    architecture = subprocess.run([sys.executable, str(ROOT/'tools/check_architecture.py')],
                                  check=True, capture_output=True, text=True)
    suites = list((ROOT/'canvas-core/build/test-results/jvmTest').glob('TEST-*.xml'))
    if not suites:
        raise SystemExit('No JVM test XML. Run verifyPrototype first; a source file count is not a test result.')
    inputs = sorted((ROOT/'canvas-core/src').rglob('*.kt')) + sorted((ROOT/'fixtures/input').glob('*.json')) + [
        ROOT/'settings.gradle.kts', ROOT/'build.gradle.kts',
        ROOT/'canvas-core/build.gradle.kts', ROOT/'gradle.properties',
    ]
    stamp_path = ROOT/'reports/gradle-verification.sha256'
    if not stamp_path.exists():
        raise SystemExit('No Gradle verification stamp. Run the root verifyPrototype task first.')
    stamp = {}
    for line in stamp_path.read_text().splitlines():
        digest, relative = line.split('  ', 1)
        stamp[relative] = digest
    actual = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs + suites}
    if actual != stamp:
        raise SystemExit('Source/configuration or test XML differs from the successful Gradle verification stamp. Rerun verifyPrototype.')
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    results = []
    for path in sorted(suites):
        suite = ET.parse(path).getroot()
        for key in totals: totals[key] += int(suite.attrib.get(key, 0))
        results.append({'suite': suite.attrib['name'], 'tests': int(suite.attrib['tests']),
                        'time_seconds': float(suite.attrib.get('time', 0))})
    sources = sorted((ROOT/'canvas-core/src/commonMain').rglob('*.kt'))
    report = {
        'phase': 'C0/C1 reference prototype',
        'status': 'passed' if totals['failures'] + totals['errors'] + totals['skipped'] == 0 else 'failed',
        'jvm_contract_tests': totals, 'suites': results,
        'architecture': json.loads(architecture.stdout),
        'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'verification_input_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
        'android_build_verified': False, 'apple_build_verified': False,
        'disk_durability_verified': False, 'gpu_lifecycle_verified': False,
        'device_performance_verified': False, 'c1_architecture_gate_complete': False,
    }
    (ROOT/'reports').mkdir(exist_ok=True)
    (ROOT/'reports/verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['status'] != 'passed': raise SystemExit(1)

if __name__ == '__main__': main()
