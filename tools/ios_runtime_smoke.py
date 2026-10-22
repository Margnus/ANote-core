#!/usr/bin/env python3
"""Dedicated Simulator install/launch evidence; no claim of drawing, storage UI or physical-device validation."""
import argparse
import hashlib
import json
import plistlib
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT/'ios-app/build/Build/Products/Debug-iphonesimulator/NoteCorePreview.app'
BUNDLE = 'dev.notecore.preview'

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    def sim(*command):
        return subprocess.run(['xcrun', 'simctl', *command], check=True, capture_output=True, text=True, timeout=120).stdout
    devices = json.loads(sim('list', 'devices', '-j'))['devices']
    matches = [(runtime, device) for runtime, group in devices.items() for device in group if device['udid'] == args.device]
    assert len(matches) == 1
    runtime_id, device = matches[0]
    assert device['name'] == 'NoteCorePreview' and device['isAvailable'] and device['state'] == 'Booted', 'Requires the dedicated booted NoteCorePreview device'
    runtime = next(r for r in json.loads(sim('list', 'runtimes', '-j'))['runtimes'] if r['identifier'] == runtime_id)
    assert runtime['isAvailable'] and 'arm64' in runtime['supportedArchitectures']
    build = json.loads((ROOT/'reports/ios-preview-build.json').read_text(encoding='utf-8'))
    assert build['build_verified'] and build['bundle'] == BUNDLE
    assert digest(ROOT/build['artifact']) == build['sha256'], 'Archive changed after successful packaging'
    for path, sha in build['source_and_build_input_sha256'].items():
        assert digest(ROOT/path) == sha, ('Stale iOS build input', path)
    info = plistlib.loads((APP/'Info.plist').read_bytes()); assert info['CFBundleIdentifier'] == BUNDLE
    sim('install', args.device, str(APP))
    installed = Path(sim('get_app_container', args.device, BUNDLE, 'app').strip())
    executable = info['CFBundleExecutable']
    assert digest(installed/executable) == digest(APP/executable)
    # Recent Xcode debug builds place app logic in .debug.dylib; the launcher stub alone is insufficient.
    payload = {str(path.relative_to(APP)): digest(path) for path in APP.rglob('*') if path.is_file()}
    assert all((installed/path).is_file() and digest(installed/path) == sha for path, sha in payload.items()), 'Installed app payload differs from packaged build'
    launched = sim('launch', args.device, BUNDLE).strip()
    assert launched.startswith(BUNDLE + ': ')
    pid = int(launched.rsplit(': ', 1)[1])
    time.sleep(2)
    screenshot = ROOT/'reports/ios-preview.png'
    sim('io', args.device, 'screenshot', str(screenshot))
    process = next((line for line in sim('spawn', args.device, 'launchctl', 'list').splitlines()
                    if line.split()[:1] == [str(pid)] and 'UIKitApplication:' + BUNDLE + '[' in line), None)
    assert process, 'App exited after launch'
    assert screenshot.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
    report = {
        'status': 'passed', 'observed_at': datetime.now(timezone.utc).isoformat(),
        'checks': ['dedicated_device_and_runtime', 'install', 'installed_executable_matches_build', 'installed_payload_matches_build', 'launch', 'process_alive_after_screenshot', 'screenshot_capture'],
        'bundle': BUNDLE, 'artifact_sha256': build['sha256'], 'executable_sha256': digest(APP/executable),
        'installed_payload_sha256': payload,
        'device': {k: device[k] for k in ('name', 'udid', 'state')},
        'runtime': {k: runtime[k] for k in ('identifier', 'version', 'buildversion', 'supportedArchitectures')},
        'pid': pid, 'launch_output': launched, 'process': process,
        'screenshot': screenshot.relative_to(ROOT).as_posix(), 'screenshot_sha256': digest(screenshot),
        'input_sha256': {'reports/ios-preview-build.json': digest(ROOT/'reports/ios-preview-build.json'),
                         Path(__file__).relative_to(ROOT).as_posix(): digest(Path(__file__))},
        'ui_interaction_verified': False, 'drawing_verified': False, 'save_reopen_verified': False,
        'device_performance_verified': False, 'production_c1_gate_complete': False,
        'scope': 'Dedicated iOS Simulator install/launch, process liveness and screenshot only; inspect the image separately; no UI actions or storage recovery verification',
    }
    (ROOT/'reports/ios-runtime-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))

if __name__ == '__main__': main()
