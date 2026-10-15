#!/usr/bin/env python3
"""Replace XCTest host with source-bound ordinary app, verify payload without UI or data deletion."""
import argparse
import hashlib
import json
import subprocess
import shutil
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT/'ios-app/build/Build/Products/Debug-iphonesimulator/NoteCorePreview.app'
BUNDLE = 'dev.notecore.preview'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    args = parser.parse_args()

    def sim(*command):
        return subprocess.run(['xcrun', 'simctl', *command], check=True, capture_output=True,
                              text=True, timeout=120).stdout

    devices = json.loads(sim('list', 'devices', '-j'))['devices']
    device = next(d for group in devices.values() for d in group if d['udid'] == args.device)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted' and device['isAvailable']
    report_path = ROOT/'reports/ios-preview-build.json'
    built = json.loads(report_path.read_text(encoding='utf-8'))
    assert built['status'] == 'passed' and built['bundle'] == BUNDLE
    assert digest(ROOT/built['artifact']) == built['sha256']
    for path, sha in built['source_and_build_input_sha256'].items():
        assert digest(ROOT/path) == sha, ('Stale ordinary app build', path)
    assert not list(APP.rglob('*.xctest'))
    sim('install', args.device, str(APP))
    installed = Path(sim('get_app_container', args.device, BUNDLE, 'app').strip())
    # Simulator install may overlay a bundle without removing stale XCTest files. Move only
    # our exact test plugin/debug symbols to build quarantine; never touch the data container.
    quarantined = []
    for name in ('NoteCorePreviewTests.xctest', 'NoteCorePreviewTests.xctest.dSYM'):
        leftover = installed/'PlugIns'/name
        if leftover.exists():
            target = ROOT/'ios-app/build/restored-test-payload'/uuid.uuid4().hex/name
            target.parent.mkdir(parents=True, exist_ok=True)
            quarantined.extend(p.relative_to(installed).as_posix() for p in leftover.rglob('*') if p.is_file())
            shutil.move(str(leftover), str(target))
    assert not list(installed.rglob('*.xctest'))
    payload = {p.relative_to(APP).as_posix(): digest(p) for p in APP.rglob('*') if p.is_file()}
    assert {p.relative_to(installed).as_posix() for p in installed.rglob('*') if p.is_file()} == set(payload), 'Unexpected installed payload files'
    assert all((installed/p).is_file() and digest(installed/p) == sha for p, sha in payload.items())
    result = {
        'status': 'passed', 'device': args.device, 'bundle': BUNDLE,
        'ordinary_package_restored_after_host_tests': True,
        'artifact_sha256': built['sha256'], 'installed_payload_sha256': payload,
        'test_payload_moved_to_build_quarantine': sorted(quarantined),
        'no_xctest_plugin': True, 'data_deleted': False, 'ui_interaction_verified': False,
        'launch_verified': False,
        'input_sha256': {str(p.relative_to(ROOT)): digest(p) for p in (Path(__file__), report_path, ROOT/'reports/ios-host-tests.json')},
        'scope': 'Install ordinary simulator app over test host and verify exact payload file set and every hash; no uninstall, data reset, UI, launch or physical-device claim',
    }
    (ROOT/'reports/ios-preview-restored.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ('installed_payload_sha256', 'input_sha256')}))


if __name__ == '__main__':
    main()
