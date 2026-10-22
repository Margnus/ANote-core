#!/usr/bin/env python3
"""Package only a matching successful Gradle APK+lint build. Runtime evidence is separate."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    stamp_path = ROOT/'reports/android-build-inputs.sha256'
    expected = dict((line.split('  ', 1)[1], line.split('  ', 1)[0]) for line in stamp_path.read_text(encoding='utf-8').splitlines())
    inputs = list((ROOT/'shared-core/src/commonMain').rglob('*.kt')) + list((ROOT/'canvas-core/src/commonMain').rglob('*.kt')) + list((ROOT/'canvas-core/src/jvmAndAndroidMain').rglob('*.kt')) + list((ROOT/'canvas-core/src/androidMain').rglob('*.kt')) + [p for p in (ROOT/'android-app/src/main').rglob('*') if p.is_file()] + [
        ROOT/'android-app/build.gradle.kts', ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'gradle.properties',
        ROOT/'canvas-core/build.gradle.kts', ROOT/'shared-core/build.gradle.kts', ROOT/'android-app/build/outputs/apk/debug/android-app-debug.apk',
        ROOT/'android-app/build/reports/lint-results-debug.xml']
    actual = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    if expected != actual: raise SystemExit('APK/source/lint inputs differ from Gradle build stamp; rerun :android-app:verifyPreviewAndroid')
    issues = ET.parse(ROOT/'android-app/build/reports/lint-results-debug.xml').getroot().findall('issue')
    errors = [i for i in issues if i.attrib['severity'] in ('Error', 'Fatal')]
    if errors: raise SystemExit('Android lint contains errors')
    source = ROOT/'android-app/build/outputs/apk/debug/android-app-debug.apk'
    output = ROOT/'dist/NoteCore-0.1.0-preview.apk'; output.parent.mkdir(exist_ok=True)
    shutil.copy2(source, output)
    report = {'status': 'passed', 'phase': 'D4 offline handwriting preview, architecture unfrozen',
              'android': {'build_verified': True, 'package': 'dev.notecore.preview', 'min_api': 26, 'target_api': 36,
                          'version': '0.1.0-preview', 'artifact': output.relative_to(ROOT).as_posix(), 'bytes': output.stat().st_size,
                          'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'signing': 'development debug key',
                          'lint_errors': 0, 'lint_warnings': len(issues)},
              'source_and_build_input_sha256': actual,
              'runtime_report': 'reports/android-smoke.json (only valid if APK SHA matches)',
              'production_c1_gate_complete': False, 'device_performance_verified': False,
              'production_crash_recovery_verified': False}
    (ROOT/'reports/preview-build.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    artifacts = sorted([*output.parent.glob('*.apk'), *output.parent.glob('*.zip')])
    (output.parent/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in artifacts), encoding='utf-8', newline='\n')
    print(json.dumps({'status': 'passed', **report['android']}, ensure_ascii=False))

if __name__ == '__main__': main()
