"""Read-only M-1 inventory/frame/memory snapshots; never a physical acceptance report."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid


def capture(adb, serial, output, emulator_preflight=False):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    artifacts = {}

    def read(name, *args):
        result = subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=30)
        data = result.stdout
        (output / (name + '.txt')).write_bytes(data)
        (output / (name + '.stderr.txt')).write_bytes(result.stderr)
        artifacts[name] = {'sha256': hashlib.sha256(data).hexdigest(),
                           'returncode': result.returncode}
        if result.returncode:
            raise RuntimeError(f'{name} failed; raw output retained')
        return data.decode('utf-8', errors='replace').strip()

    report = {'schema': 1, 'status': 'incomplete', 'physical_verified': False,
              'm1_accepted': False, 'artifacts': artifacts,
              'scope': 'Read-only point snapshots; no input trace, peak memory or pen latency'}
    try:
        if read('state', 'get-state') != 'device':
            raise ValueError('device is not ready')
        qemu = read('qemu', 'shell', 'getprop', 'ro.kernel.qemu')
        boot_qemu = read('boot-qemu', 'shell', 'getprop', 'ro.boot.qemu')
        is_emulator = serial.startswith('emulator-') or qemu == '1' or boot_qemu == '1'
        report['emulator_detected'] = is_emulator
        if is_emulator and not emulator_preflight:
            raise ValueError('emulator rejected; use --emulator-preflight only for collector checks')
        if emulator_preflight and not is_emulator:
            raise ValueError('emulator preflight requires a detected emulator')
        for name, prop in [('model', 'ro.product.model'), ('manufacturer', 'ro.product.manufacturer'),
                           ('sdk', 'ro.build.version.sdk'), ('abi', 'ro.product.cpu.abi'),
                           ('fingerprint', 'ro.build.fingerprint')]:
            read(name, 'shell', 'getprop', prop)
        read('features', 'shell', 'pm', 'list', 'features')
        read('screen-size', 'shell', 'wm', 'size')
        read('screen-density', 'shell', 'wm', 'density')
        package = 'dev.notecore.preview'
        read('package', 'shell', 'dumpsys', 'package', package)
        pid_before = read('pid-before', 'shell', 'pidof', package)
        read('frames', 'shell', 'dumpsys', 'gfxinfo', package, 'framestats')
        read('memory', 'shell', 'dumpsys', 'meminfo', '-s', package)
        pid_after = read('pid-after', 'shell', 'pidof', package)
        if not pid_before or pid_before != pid_after:
            raise ValueError('process missing or changed during snapshot')
        report['status'] = 'snapshot-collected'
        report['origin'] = 'emulator-preflight' if is_emulator else 'device-candidate'
        report['stylus_observed'] = None
        return report
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report['error'] = str(error)
        raise
    finally:
        (output / 'snapshot.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n',
                                             encoding='utf-8', newline='\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', default='build/android-m1-' + str(uuid.uuid4()))
    parser.add_argument('--emulator-preflight', action='store_true')
    args = parser.parse_args()
    capture(args.adb, args.serial, args.output, args.emulator_preflight)
    print(args.output)
