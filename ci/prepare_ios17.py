"""Bounded hosted-runner check for an actual iOS17 runtime; never App acceptance."""
import json
import os
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/ios17-preflight'


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    report = {'status': 'running', 'app_verified': False, 'physical_device_verified': False,
              'runtime_identifier': 'com.apple.CoreSimulator.SimRuntime.iOS-17-5'}
    device = None

    def run(label, *args, timeout=120):
        with (OUTPUT / (label + '.log')).open('w', encoding='utf-8', newline='\n') as log:
            subprocess.run(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=timeout, check=True)
        return (OUTPUT / (label + '.log')).read_text(encoding='utf-8')

    try:
        report['commit'] = run('commit', 'git', 'rev-parse', 'HEAD').strip()
        run('disk-before', 'df', '-h')
        run('xcode', 'xcodebuild', '-version')
        run('runtimes-before', 'xcrun', 'simctl', 'list', 'runtimes', '--json')
        run('download', 'xcodebuild', '-downloadPlatform', 'iOS', '-buildVersion', '17.5', timeout=900)
        inventory = json.loads(run('runtimes-after', 'xcrun', 'simctl', 'list', 'runtimes', '--json'))
        matches = [r for r in inventory['runtimes'] if r['identifier'] == report['runtime_identifier'] and r.get('isAvailable')]
        assert len(matches) == 1 and matches[0]['version'].split('.')[0] == '17', 'Requested iOS17 runtime unavailable'
        report['runtime'] = matches[0]
        device = run('create', 'xcrun', 'simctl', 'create', 'NoteCore-iOS17-' + uuid.uuid4().hex,
                     'com.apple.CoreSimulator.SimDeviceType.iPhone-15', report['runtime_identifier']).strip()
        uuid.UUID(device)
        run('boot', 'xcrun', 'simctl', 'boot', device)
        try:
            run('bootstatus', 'xcrun', 'simctl', 'bootstatus', device, '-b', timeout=240)
        except subprocess.TimeoutExpired:
            # The observer timed out, not necessarily the booted simulator. Inspect
            # the same UUID before continuing observation; never recreate/reboot it.
            report['initial_boot_observer_timed_out'] = True
            inventory = json.loads(run('devices-after-observer-timeout', 'xcrun', 'simctl', 'list', 'devices', '--json'))
            matches = [d for group in inventory['devices'].values() for d in group if d['udid'] == device]
            report['device_after_observer_timeout'] = matches
            if len(matches) != 1 or matches[0].get('state') != 'Booted':
                raise
            # Booted is necessary, not sufficient. Only an actual bootstatus success
            # can pass this preflight; a second timeout remains a real failure.
            run('bootstatus-continued', 'xcrun', 'simctl', 'bootstatus', device, '-b', timeout=240)
        report.update(status='runtime-boot-verified', device=device)
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        if device:
            try:
                run('shutdown', 'xcrun', 'simctl', 'shutdown', device)
            except subprocess.SubprocessError as error:
                report['shutdown_error'] = repr(error)
            try:
                run('delete', 'xcrun', 'simctl', 'delete', device)
                report['temporary_device_deleted'] = True
            except subprocess.SubprocessError as error:
                report.update(status='failed', cleanup_error=repr(error))
        (OUTPUT / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    assert report['status'] == 'runtime-boot-verified', report


if __name__ == '__main__':
    main()
