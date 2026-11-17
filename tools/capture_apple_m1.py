"""Record an explicitly selected physical iPad and running preview process with Instruments."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import plistlib
import re
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def capture(args):
    args.output.mkdir(parents=True, exist_ok=False)
    report = {
        'status': 'incomplete', 'm1_accepted': False,
        'device_udid': args.device, 'pid': args.pid, 'steps': {},
        'installed_binary_sha256_verified': False,
        'started_utc': datetime.now(timezone.utc).isoformat(),
    }

    def run(label, *command, timeout=45):
        with (args.output / (label + '.stdout')).open('wb') as out, (
            args.output / (label + '.stderr')
        ).open('wb') as err:
            report['steps'][label] = {'command': command, 'status': 'started'}
            result = subprocess.run(command, stdout=out, stderr=err, timeout=timeout)
            report['steps'][label]['returncode'] = result.returncode
            result.check_returncode()
            report['steps'][label]['status'] = 'recorded'
        return (args.output / (label + '.stdout')).read_bytes()

    def device_info(label, topic, *options):
        return json.loads(run(
            label, 'xcrun', 'devicectl', 'device', 'info', topic,
            '--device', args.device, '--timeout', '25', '--json-output', '-', *options,
        ))['result']

    def process(label):
        rows = device_info(label, 'processes', '--search', executable)['runningProcesses']
        matches = [row for row in rows if row['processIdentifier'] == args.pid
                   and row['executable'].endswith(f'/{executable}.app/{executable}')]
        if len(matches) != 1:
            raise ValueError('running process does not match preview executable and PID')
        return matches[0]['executable']

    try:
        with zipfile.ZipFile(args.ipa) as archive:
            infos = [name for name in archive.namelist() if re.fullmatch(r'Payload/[^/]+\.app/Info\.plist', name)]
            if len(infos) != 1:
                raise ValueError('expected one device app in IPA')
            info = plistlib.loads(archive.read(infos[0]))
            executable = info['CFBundleExecutable']
            if info['CFBundleIdentifier'] != 'dev.notecore.preview' or 'iPhoneOS' not in info['CFBundleSupportedPlatforms']:
                raise ValueError('expected the NoteCore iPhoneOS preview')
            if infos[0].split('/')[1] != executable + '.app':
                raise ValueError('expected matching app directory and executable names')
        report['candidate'] = {
            'bundle': info['CFBundleIdentifier'], 'build': info['CFBundleVersion'],
            'version': info['CFBundleShortVersionString'], 'ipa_sha256': sha256(args.ipa),
        }
        run('xcode', 'xcodebuild', '-version')
        device_info('device', 'details')
        device_info('installed', 'apps', '--search', info['CFBundleIdentifier'])
        before = process('process-before')
        trace = args.output / 'recording.trace'
        run('record', 'xcrun', 'xctrace', 'record', '--device', args.device, '--attach', str(args.pid),
            '--template', args.template, '--time-limit', f'{args.seconds}s', '--output', str(trace),
            timeout=args.seconds + 120)
        if process('process-after') != before:
            raise ValueError('preview executable changed during trace')
        run('trace-toc', 'xcrun', 'xctrace', 'export', '--input', str(trace), '--toc')
        report['status'] = 'trace-collected'
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        report['error'] = str(error)
        raise
    finally:
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        report['artifact_sha256'] = {
            path.relative_to(args.output).as_posix(): sha256(path)
            for path in sorted(args.output.rglob('*')) if path.is_file() and not path.is_symlink()
        }
        (args.output / 'capture.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True, help='Physical iPad hardware UDID from xctrace')
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--ipa', type=Path, required=True, help='Exact signed iPhoneOS IPA installed for this run')
    parser.add_argument('--template', required=True, help='Installed Instruments template name or path')
    parser.add_argument('--seconds', type=int, default=310)
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / f'apple-m1-{uuid.uuid4()}')
    args = parser.parse_args()
    if not re.fullmatch(r'(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40})', args.device):
        parser.error('--device must be a hardware UDID')
    if args.pid < 1 or args.seconds < 1:
        parser.error('--pid and --seconds must be positive')
    capture(args)
    print(args.output)


if __name__ == '__main__':
    main()
