"""Record an explicitly selected physical iPad and running preview process with Instruments."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import plistlib
import re
import stat
import subprocess
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CAPTURE_FILES = frozenset({'xcode', 'device', 'installed', 'process-before', 'record', 'process-after', 'trace-toc'})
HARDWARE_UDID = re.compile(r'(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40})')


def stream_sha256(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
    return digest.hexdigest()


def sha256(path):
    with path.open('rb') as stream:
        return stream_sha256(stream)


def capture_artifacts(directory):
    artifacts = {}
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'unexpected symlink in capture: {path}')
        if path.is_file() and path != directory / 'capture.json':
            artifacts[path.relative_to(directory).as_posix()] = sha256(path)
    return artifacts


def validate_trace(directory):
    trace = directory / 'recording.trace'
    if not trace.is_dir() or trace.is_symlink() or not any(
        path.is_file() and not path.is_symlink() and path.stat().st_size > 0 for path in trace.rglob('*')
    ):
        raise ValueError('missing or empty Instruments trace')
    try:
        toc = ET.parse(directory / 'trace-toc.stdout').getroot()
    except ET.ParseError as error:
        raise ValueError('invalid Instruments trace TOC') from error
    if toc.tag != 'trace-toc' or toc.find('./run/data/table') is None:
        raise ValueError('Instruments trace TOC has no recorded tables')


def preview_process(payload, executable, pid):
    rows = json.loads(payload)['result']['runningProcesses']
    matches = [row for row in rows if row['processIdentifier'] == pid
               and row['executable'].endswith(f'/{executable}.app/{executable}')]
    if len(matches) != 1:
        raise ValueError('running process does not match preview executable and PID')
    return matches[0]['executable']


def read_candidate(path):
    with path.open('rb') as package, zipfile.ZipFile(package) as archive:
        entries = archive.infolist()
        manifests = [entry for entry in entries if re.fullmatch(r'Payload/[^/]+\.app/Info\.plist', entry.filename)]
        if len(manifests) != 1:
            raise ValueError('expected one device app in IPA')
        manifest = manifests[0]
        info = plistlib.loads(archive.read(manifest))
        executable = info['CFBundleExecutable']
        if info['CFBundleIdentifier'] != 'dev.notecore.preview' or 'iPhoneOS' not in info['CFBundleSupportedPlatforms']:
            raise ValueError('expected the NoteCore iPhoneOS preview')
        if manifest.filename.split('/')[1] != executable + '.app':
            raise ValueError('expected matching app directory and executable names')
        executable_path = manifest.filename.removesuffix('Info.plist') + executable
        binaries = [entry for entry in entries if entry.filename == executable_path]
        if (len(binaries) != 1 or binaries[0].file_size == 0
                or stat.S_IFMT(binaries[0].external_attr >> 16) not in (0, stat.S_IFREG)):
            raise ValueError('expected one nonempty preview executable in IPA')
        with archive.open(binaries[0]) as binary:
            binary_digest = stream_sha256(binary)
        package.seek(0)
        return executable, {
            'bundle': info['CFBundleIdentifier'], 'build': info['CFBundleVersion'],
            'version': info['CFBundleShortVersionString'], 'ipa_sha256': stream_sha256(package),
            'ipa_executable_sha256': binary_digest,
        }


def capture(args):
    args.output.mkdir(parents=True, exist_ok=False)
    report = {
        'schema': 2, 'status': 'incomplete', 'm1_accepted': False,
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
        return run(
            label, 'xcrun', 'devicectl', 'device', 'info', topic,
            '--device', args.device, '--timeout', '25', '--json-output', '-', *options,
        )

    def process(label):
        return preview_process(device_info(label, 'processes', '--search', executable), executable, args.pid)

    try:
        executable, candidate = read_candidate(args.ipa)
        report['candidate'] = candidate
        run('xcode', 'xcodebuild', '-version')
        json.loads(device_info('device', 'details'))['result']
        json.loads(device_info('installed', 'apps', '--search', candidate['bundle']))['result']
        before = process('process-before')
        trace = args.output / 'recording.trace'
        run('record', 'xcrun', 'xctrace', 'record', '--device', args.device, '--attach', str(args.pid),
            '--template', args.template, '--time-limit', f'{args.seconds}s', '--output', str(trace),
            timeout=args.seconds + 120)
        if process('process-after') != before:
            raise ValueError('preview executable changed during trace')
        run('trace-toc', 'xcrun', 'xctrace', 'export', '--input', str(trace), '--toc')
        if sha256(args.ipa) != candidate['ipa_sha256']:
            raise ValueError('candidate IPA changed during trace')
        validate_trace(args.output)
        report['artifact_sha256'] = capture_artifacts(args.output)
        report['status'] = 'trace-collected'
    except (OSError, ValueError, KeyError, plistlib.InvalidFileException,
            subprocess.SubprocessError, zipfile.BadZipFile) as error:
        report['error'] = str(error)
        raise
    finally:
        report['finished_utc'] = datetime.now(timezone.utc).isoformat()
        if 'artifact_sha256' not in report:
            try:
                report['artifact_sha256'] = capture_artifacts(args.output)
            except (OSError, ValueError) as artifact_error:
                report['artifact_hash_error'] = str(artifact_error)
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
    if not HARDWARE_UDID.fullmatch(args.device):
        parser.error('--device must be a hardware UDID')
    if args.pid < 1 or args.seconds < 1:
        parser.error('--pid and --seconds must be positive')
    capture(args)
    print(args.output)


if __name__ == '__main__':
    main()
