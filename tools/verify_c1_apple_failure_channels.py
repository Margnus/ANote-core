"""Exercise the selected C1 recovery failures and compatibility throws on a fixed Apple SDK."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / 'reports/c1-pinned-sdk-20261117.json'
CONSUMER = ROOT / 'tools/probes/C1AppleFailureConsumer.swift'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(framework_dir, runtime, device_type, output, pin_path=PIN, current_candidate=False):
    output.mkdir(parents=True)
    pin = json.loads(pin_path.read_bytes())
    fixture_paths = {Path(name).name: ROOT / name for name in pin['apple']['fixture_sha256']}
    for name, digest in pin['apple']['fixture_sha256'].items():
        assert sha(ROOT / name) == digest, name

    def run(label, *command, timeout=60, allowed=(0,)):
        result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, timeout=timeout)
        (output / f'{label}.stdout.log').write_bytes(result.stdout)
        (output / f'{label}.stderr.log').write_bytes(result.stderr)
        if result.returncode not in allowed:
            raise RuntimeError((label, result.returncode, result.stderr.decode(errors='replace')))
        return result.stdout

    simctl = ('xcrun', 'simctl')
    with tempfile.TemporaryDirectory(prefix='c1-apple-failure-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        targets = {}
        for target, sdk, triple, platform in (
            ('iosSimulatorArm64', 'iphonesimulator', 'arm64-apple-ios17.0-simulator', 'IOSSIMULATOR'),
            ('iosArm64', 'iphoneos', 'arm64-apple-ios17.0', 'IOS'),
        ):
            framework = framework_dir / target / 'releaseFramework/NoteCanvasPrototype.framework'
            library = framework / 'NoteCanvasPrototype'
            header = framework / 'Headers/NoteCanvasPrototype.h'
            expected = pin['apple']['targets'][target]
            assert sha(library) == expected['framework_sha256'] and sha(header) == expected['header_sha256']
            binary = work / target
            defines = ['-D', 'CURRENT_CANDIDATE'] if current_candidate else []
            run(f'compile-{target}', 'xcrun', '--sdk', sdk, 'swiftc', *defines, '-target', triple, '-F', framework.parent,
                '-module-cache-path', work / ('modules-' + target), CONSUMER, '-o', binary)
            linked = run(f'link-{target}', 'xcrun', 'vtool', '-show-build', binary).decode()
            assert 'minos 17.0' in linked and f'platform {platform}\n' in linked
            targets[target] = {'consumer_sha256': sha(binary), 'framework_sha256': sha(library),
                               'header_sha256': sha(header), 'link_min_os': '17.0'}

        before = json.loads(run('devices-before', *simctl, 'list', 'devices', '-j'))
        runtime_info = json.loads(run('runtimes', *simctl, 'list', 'runtimes', '-j'))
        configured, = [item for item in runtime_info['runtimes'] if item['identifier'] == runtime and item['isAvailable']]
        simulator = run('create', *simctl, 'create', 'NoteCore-C1-' + uuid.uuid4().hex,
                        device_type, runtime).decode().strip()
        uuid.UUID(simulator)
        try:
            run('boot', *simctl, 'boot', simulator)
            run('bootstatus', *simctl, 'bootstatus', simulator, '-b', timeout=180)
            receipt = json.loads(run('consumer', *simctl, 'spawn', simulator, work / 'iosSimulatorArm64',
                                     fixture_paths['prepare.ncp'], fixture_paths['confirmed-final.ncp']))
            assert receipt['pid'] > 0 and receipt['retry_preserved_checkpoint_bytes']
        finally:
            try:
                run('shutdown', *simctl, 'shutdown', simulator, allowed=(0, 1, 149))
            finally:
                run('delete', *simctl, 'delete', simulator)
        after = json.loads(run('devices-after', *simctl, 'list', 'devices', '-j'))
        assert not any(device['udid'] == simulator for devices in after['devices'].values() for device in devices)
        for name, digest in pin['apple']['fixture_sha256'].items():
            assert sha(ROOT / name) == digest, name

    report = {
        'status': 'passed', 'candidate_source_commit': pin['candidate_source_commit'],
        'current_candidate_mode': current_candidate,
        'scope': 'Independent Apple Swift executable on the selected simulator runtime; iPhoneOS binary linked only',
        'runtime': {'identifier': runtime, 'version': configured['version'], 'build': configured['buildversion']},
        'selected_typed_channels_and_recovery': {key: value for key, value in receipt.items() if 'nserror' not in key},
        'compatibility_nserror_channels': {key: value for key, value in receipt.items() if 'nserror' in key},
        'targets': targets, 'simulator_deleted': True, 'existing_device_inventory_unchanged': before == after,
        'ios17_runtime_verified': configured['version'].split('.')[0] == '17',
        'source_sha256': {path.relative_to(ROOT).as_posix(): sha(path)
                          for path in (Path(__file__).resolve(), CONSUMER, pin_path, *fixture_paths.values())},
        'raw_sha256': {path.name: sha(path) for path in sorted(output.iterdir())},
    }
    (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--framework-dir', type=Path, required=True,
                        help='Directory containing iosArm64 and iosSimulatorArm64 build targets')
    parser.add_argument('--runtime', default='com.apple.CoreSimulator.SimRuntime.iOS-27-0')
    parser.add_argument('--device-type', default='com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--pin', type=Path, default=PIN, help='Exact source/artifact descriptor; historical pin remains the default')
    parser.add_argument('--current-candidate', action='store_true', help='Compile current signatures; accept HIGHLIGHTER and refuse PRESSURE')
    args = parser.parse_args()
    verify(args.framework_dir.resolve(), args.runtime, args.device_type, args.output.resolve(),
           args.pin.resolve(), args.current_candidate)
