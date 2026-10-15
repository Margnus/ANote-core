#!/usr/bin/env python3
"""Record a successful Xcode build and package an ARM64 Simulator app, never an iPhone IPA."""
import argparse
import hashlib
import json
import plistlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT/'ios-app/build/Build/Products/Debug-iphonesimulator/NoteCorePreview.app'

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--record-success', action='store_true'); args = parser.parse_args()
    info = plistlib.loads((APP/'Info.plist').read_bytes())
    assert info['CFBundleIdentifier'] == 'dev.notecore.preview'
    assert info['CFBundleSupportedPlatforms'] == ['iPhoneSimulator']
    assert not list(APP.rglob('*.xctest')), 'Test plugins must stay in the separate host-test build, not the distributable app'
    inputs = list((ROOT/'shared-core/src/commonMain').rglob('*.kt')) + list((ROOT/'canvas-core/src/commonMain').rglob('*.kt')) + list((ROOT/'ios-app/NoteCorePreview').glob('*.swift')) + [
        ROOT/'build.gradle.kts', ROOT/'settings.gradle.kts', ROOT/'canvas-core/build.gradle.kts', ROOT/'shared-core/build.gradle.kts', ROOT/'gradle.properties',
        ROOT/'ios-app/NoteCorePreview.xcodeproj/project.pbxproj',
        ROOT/'ios-app/NoteCorePreview.xcodeproj/xcshareddata/xcschemes/NoteCorePreview.xcscheme',
        ROOT/'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework/NoteCanvasPrototype',
    ] + [p for p in APP.rglob('*') if p.is_file()]
    actual = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    stamp = ROOT/'reports/ios-build-inputs.sha256'
    if args.record_success:
        stamp.write_text(''.join(f'{digest}  {path}\n' for path, digest in sorted(actual.items())))
    else:
        expected = {line.split('  ', 1)[1]: line.split('  ', 1)[0] for line in stamp.read_text().splitlines()}
        if expected != actual: raise SystemExit('iOS source/framework/app differs from successful build stamp; rerun package-ios-preview.sh')
    output = ROOT/'dist/NoteCore-0.1.0-preview-ios-simulator-arm64.app.zip'
    output.parent.mkdir(exist_ok=True)
    subprocess.run(['/usr/bin/ditto', '-c', '-k', '--keepParent', str(APP), str(output)], check=True)
    report = {'status': 'passed', 'platform': 'iOS Simulator ARM64', 'bundle': info['CFBundleIdentifier'],
              'build_verified': True, 'launch_verified': False, 'launch_limitation': 'Packaging compiles the host but does not exercise runtime launch; verified separately',
              'artifact': str(output.relative_to(ROOT)), 'bytes': output.stat().st_size,
              'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'device_ipa': False, 'code_signing': 'disabled for Simulator build',
              'xcode': subprocess.run(['/usr/bin/xcodebuild', '-version'], check=True, capture_output=True, text=True).stdout.strip(),
              'source_and_build_input_sha256': actual, 'device_performance_verified': False, 'production_c1_gate_complete': False}
    (ROOT/'reports/ios-preview-build.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    artifacts = sorted([*output.parent.glob('*.apk'), *output.parent.glob('*.zip')])
    (output.parent/'SHA256SUMS').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in artifacts))
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_build_input_sha256'}, ensure_ascii=False))

if __name__ == '__main__': main()
