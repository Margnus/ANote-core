#!/usr/bin/env python3
"""Kill the exact original Apple test-host PID at two observable atomic API boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
BUNDLE = 'dev.notecore.preview'
HOST = ROOT / 'ios-app/build/host-tests'
TEST = 'NoteCorePreviewTests/PagedHostTests/testAtomicWriteProcessBoundary'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*values, timeout=120):
    return subprocess.run(values, cwd=ROOT, check=True, capture_output=True,
                          text=True, encoding='utf-8', timeout=timeout).stdout


def sim(*values):
    return run('xcrun', 'simctl', *values)


def originals(container):
    return {p.name: sha(p) for p in sorted((container / 'Documents/PreviewNotes').glob('*.ncp'))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--device', required=True)
    parser.add_argument('--isolated', action='store_true', help='Rebuild the host and run on a fresh NoteCoreAtomicCut simulator')
    args = parser.parse_args()
    device_id = str(uuid.UUID(args.device)).upper()
    devices = json.loads(sim('list', 'devices', '--json'))['devices']
    runtime, device = next((runtime, device) for runtime, group in devices.items()
                           for device in group if device['udid'] == device_id)
    assert device['state'] == 'Booted'
    if args.isolated:
        from verify_ios_host import inputs
        assert device['name'].startswith('NoteCoreAtomicCut-')
        assert BUNDLE not in sim('listapps', device_id), 'Expected a fresh fixture simulator without the preview app'
        folder = ROOT / ('build/apple-atomic-cut-isolated-' + uuid.uuid4().hex)
        folder.mkdir(parents=True)
        host = folder / 'host-tests'
        bindings = inputs()
        with (folder / 'host-build.log').open('w', encoding='utf-8') as log:
            subprocess.run(['xcodebuild', '-quiet', '-project', 'ios-app/NoteCorePreview.xcodeproj',
                            '-scheme', 'NoteCorePreview', '-sdk', 'iphonesimulator', '-destination',
                            'platform=iOS Simulator,id=' + device_id, '-derivedDataPath', str(host),
                            'CODE_SIGNING_ALLOWED=NO', 'build-for-testing'], cwd=ROOT,
                           stdout=log, stderr=subprocess.STDOUT, timeout=240, check=True)
        app = host / 'Build/Products/Debug-iphonesimulator/NoteCorePreview.app'
        products = [app / 'NoteCorePreview', app / 'NoteCorePreview.debug.dylib',
                    app / 'PlugIns/NoteCorePreviewTests.xctest/NoteCorePreviewTests']
        bindings.update({path.relative_to(ROOT).as_posix(): sha(path) for path in products})
        sim('install', device_id, str(app))
        container = Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())
        before = originals(container)
        assert not before
    else:
        assert device_id == DEVICE and device['name'] == 'NoteCorePreview'
        host = HOST
        container = Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())
        before = originals(container); assert len(before) == 14
        assert before == json.loads((ROOT / 'build/remote-sync-20261027/ios-after.json').read_text(encoding='utf-8'))
        built_path = ROOT / 'reports/ios-preview-build.json'
        host_path = ROOT / 'reports/ios-host-tests.json'
        built = json.loads(built_path.read_text(encoding='utf-8'))
        hosted = json.loads(host_path.read_text(encoding='utf-8'))
        assert built['status'] == hosted['status'] == 'passed' and hosted['test_count'] == len(hosted['checks'])
        assert 'testAtomicWriteProcessBoundary()' in hosted['checks']
        bindings = {**built['source_and_build_input_sha256'], **hosted['source_and_framework_sha256'],
                    **hosted['test_build_sha256']}
        bindings.update({p.relative_to(ROOT).as_posix(): sha(p) for p in (built_path, host_path)})
        folder = ROOT / 'build/apple-atomic-cut'; folder.mkdir(parents=True, exist_ok=True)
    bindings[Path(__file__).relative_to(ROOT).as_posix()] = sha(Path(__file__))
    for name, digest in bindings.items(): assert sha(ROOT / name) == digest, name
    control = container / 'Library/Caches/NoteCoreAtomicCutControl'; control.mkdir(parents=True, exist_ok=True)
    request = control / 'request.json'; assert not request.exists(), 'Existing control belongs to another run'
    evidence = []; observations = []
    process = None
    try:
        for boundary in ['beforeAtomicWrite', 'afterAtomicWrite']:
            identity = str(uuid.uuid4()); fixture = container / 'Library/Caches' / ('atomic-cut-' + identity)
            stage = folder / boundary; stage.mkdir(exist_ok=True)
            config = {'identity': identity, 'boundary': boundary, 'action': 'prepare'}
            request.write_text(json.dumps(config), encoding='utf-8')
            result_file = control / 'result.json'
            if result_file.exists(): result_file.unlink()
            prepare_result = stage / ('prepare-' + uuid.uuid4().hex + '.xcresult')
            base_command = ['xcodebuild', '-quiet', '-project', 'ios-app/NoteCorePreview.xcodeproj',
                            '-scheme', 'NoteCorePreview', '-sdk', 'iphonesimulator', '-destination',
                            'platform=iOS Simulator,id=' + device_id, '-derivedDataPath', str(host),
                            'CODE_SIGNING_ALLOWED=NO', '-parallel-testing-enabled', 'NO',
                            '-only-testing:' + TEST, '-collect-test-diagnostics', 'never']
            log = stage / 'prepare.log'
            with log.open('w', encoding='utf-8') as output:
                process = subprocess.Popen([*base_command, '-resultBundlePath', str(prepare_result), 'test-without-building'],
                                           cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 60
                marker = fixture / 'ready.json'
                while not marker.exists() and time.monotonic() < deadline:
                    assert process.poll() is None, log.read_text(encoding='utf-8')
                    time.sleep(.1)
                    # XCTest installation can relocate the data container while preserving its contents.
                    container = Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())
                    control = container / 'Library/Caches/NoteCoreAtomicCutControl'
                    request = control / 'request.json'
                    fixture = container / 'Library/Caches' / ('atomic-cut-' + identity)
                    marker = fixture / 'ready.json'
                assert marker.exists() and process.poll() is None, 'No live original worker marker observed'
                ready = json.loads(marker.read_text(encoding='utf-8'))
                victim = ready['pid']; assert isinstance(victim, int) and victim > 1
                assert ready['boundary'] == boundary and ready['has_pending'] and ready['pending_writes'] == 1
                assert ready['worker_returned'] is False and ready['status'].startswith('正在保存')
                installed = Path(sim('get_app_container', device_id, BUNDLE, 'app').strip())
                if args.isolated:
                    assert all(sha(installed / p.relative_to(app)) == sha(p) for p in products)
                command = run('ps', '-p', str(victim), '-o', 'comm=').strip()
                assert command == str(installed / 'NoteCorePreview'), command
                os.kill(victim, 0)
                old = (fixture / 'expected-old.txt').read_bytes(); pending = (fixture / 'expected-pending.txt').read_bytes()
                base = (fixture / 'pending-window.ncp').read_bytes()
                assert old.startswith(b'NCPREVIEW') and pending.startswith(b'NCRECOVERY')
                assert base == (old if boundary == 'beforeAtomicWrite' else pending)
                for name, data in [('old.ncp', old), ('pending.ncp', pending), ('base-before-kill.ncp', base)]:
                    (stage / name).write_bytes(data)
                assert process.poll() is None
                os.kill(victim, signal.SIGKILL)
                # The PID must be gone before waiting for the failed XCTest invocation to finish.
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    try: os.kill(victim, 0)
                    except ProcessLookupError: break
                    time.sleep(.05)
                else: raise AssertionError('Observed original PID did not terminate')
                assert (fixture / 'pending-window.ncp').read_bytes() == base
                process.wait(timeout=90)
                assert process.returncode != 0, 'Killed prepare invocation must not pass'
            container = Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())
            control = container / 'Library/Caches/NoteCoreAtomicCutControl'
            request = control / 'request.json'
            config['action'] = 'reopen'; request.write_text(json.dumps(config), encoding='utf-8')
            reopened_result = stage / ('reopen-' + uuid.uuid4().hex + '.xcresult')
            reopened_log = stage / 'reopen.log'
            with reopened_log.open('w', encoding='utf-8') as output:
                reopened = subprocess.run([*base_command, '-resultBundlePath', str(reopened_result), 'test-without-building'],
                                          cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, timeout=180)
            assert reopened.returncode == 0, reopened_log.read_text(encoding='utf-8')
            summary_text = run('xcrun', 'xcresulttool', 'get', 'test-results', 'summary', '--path', str(reopened_result), '--format', 'json')
            summary = json.loads(summary_text)
            assert summary['result'] == 'Passed' and summary['totalTestCount'] == 1
            assert summary['failedTests'] == summary['skippedTests'] == summary['expectedFailures'] == 0
            container = Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())
            control = container / 'Library/Caches/NoteCoreAtomicCutControl'
            request = control / 'request.json'
            result_file = control / 'result.json'
            fixture = container / 'Library/Caches' / ('atomic-cut-' + identity)
            result = json.loads(result_file.read_text(encoding='utf-8')); recovered = boundary == 'afterAtomicWrite'
            assert result['previous_pid'] == victim and result['pid'] != victim and result['boundary'] == boundary
            assert result['recovered_pending'] == recovered and result['committed_strokes'] == int(recovered)
            assert result['revision'] == int(recovered) and result['same_content_after_save'] and result['owner_retired']
            assert not fixture.exists(), 'Successful original retirement should remove only the synthetic fixture'
            observation = {'ready': ready, 'reopen': result, 'original_pid_sigkill_confirmed': True,
                           'base_before_kill_sha256': hashlib.sha256(base).hexdigest(),
                           'reopen_xcresult': reopened_result.relative_to(ROOT).as_posix()}
            observed = stage / 'observation.json'; observed.write_text(json.dumps(observation, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
            summary_file = stage / 'summary.json'; summary_file.write_text(summary_text, encoding='utf-8')
            observations.append(observation)
            evidence.extend([log, reopened_log, observed, summary_file, *stage.glob('*.ncp')])
            request.unlink()
        assert originals(container) == before
    finally:
        container = Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())
        request = container / 'Library/Caches/NoteCoreAtomicCutControl/request.json'
        if request.exists(): request.unlink()
        if process is not None and process.poll() is None:
            subprocess.run(['xcrun', 'simctl', 'terminate', device_id, BUNDLE], capture_output=True, timeout=30)
            process.wait(timeout=60)
        if not args.isolated:
            run(os.sys.executable, str(ROOT / 'tools/restore_ios_preview.py'), '--device', device_id)
    container = Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())
    assert originals(container) == before
    original_file = folder / 'original-files.json'
    original_file.write_text(json.dumps(before, indent=2)+'\n', encoding='utf-8'); evidence.append(original_file)
    if args.isolated:
        sim('launch', device_id, BUNDLE)
        assert originals(Path(sim('get_app_container', device_id, BUNDLE, 'data').strip())) == before
        evidence.append(folder / 'host-build.log')
    else:
        evidence.append(ROOT / 'reports/ios-preview-restored.json')
    for name, digest in bindings.items(): assert sha(ROOT / name) == digest, name
    bindings.update({p.relative_to(ROOT).as_posix(): sha(p) for p in evidence})
    report = {'schema': 'apple-atomic-cut-1', 'status': 'passed', 'observations': observations,
              'original_files_preserved': len(before), 'ordinary_payload_restored': not args.isolated,
              'isolated_simulator': args.isolated, 'runtime': runtime,
              'source_and_artifact_sha256': dict(sorted(bindings.items())),
              'decision': 'Retain original serial Data.write(.atomic) and original owner ACK. Before API entry the old file survives; after API return but before original owner ACK a new process recovers complete pending and commits once.',
              'scope': 'Two exact observed original test-host SIGKILLs on Apple Simulator, with fresh original Model readers. The internal temporary write/sync/rename point inside Data.write is opaque and untested. No random internal interruption, power loss, directory fsync guarantee, physical M-1/heap/stylus or production DURABLE receipt.',
              'c1_gate_complete': False, 'production_sqlite_authorized': False}
    destination = folder / 'result.json' if args.isolated else ROOT / 'reports/apple-atomic-cut.json'
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
