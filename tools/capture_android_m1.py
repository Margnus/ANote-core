"""Read-only M-1 inventory/frame/memory snapshots; never a physical acceptance report."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import time
import uuid


def process_identity(stat, pid):
    # comm is parenthesized and may itself contain spaces or ')' characters.
    head, separator, fields = stat.rpartition(') ')
    tail = fields.split()
    if (not separator or not head.startswith(pid + ' (') or len(tail) < 20
            or not tail[19].isascii() or not tail[19].isdigit() or int(tail[19]) <= 0):
        raise ValueError('invalid process stat identity')
    return {'pid': int(pid), 'starttime_ticks': int(tail[19])}


def capture(adb, serial, output, emulator_preflight=False, apk=None,
            sample_count=1, interval_seconds=1.0):
    if (type(sample_count) is not int or not 1 <= sample_count <= 3600
            or not math.isfinite(interval_seconds) or not 0.1 <= interval_seconds <= 60
            or (sample_count - 1) * interval_seconds > 3600):
        raise ValueError('sample count/interval must be bounded (1..3600, 0.1..60s, span <=3600s)')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    artifacts = {}
    now = lambda: datetime.now(timezone.utc).isoformat()

    def read(name, *args):
        started = now()
        try:
            result = subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=30)
        except subprocess.TimeoutExpired as error:
            data, stderr = error.stdout or b'', error.stderr or b''
            (output / (name + '.txt')).write_bytes(data)
            (output / (name + '.stderr.txt')).write_bytes(stderr)
            artifacts[name] = {'sha256': hashlib.sha256(data).hexdigest(),
                               'stderr_sha256': hashlib.sha256(stderr).hexdigest(),
                               'returncode': None, 'timed_out': True,
                               'started_utc': started, 'finished_utc': now()}
            raise
        data = result.stdout
        (output / (name + '.txt')).write_bytes(data)
        (output / (name + '.stderr.txt')).write_bytes(result.stderr)
        artifacts[name] = {'sha256': hashlib.sha256(data).hexdigest(),
                           'stderr_sha256': hashlib.sha256(result.stderr).hexdigest(),
                           'returncode': result.returncode,
                           'started_utc': started, 'finished_utc': now()}
        if result.returncode:
            raise RuntimeError(f'{name} failed; raw output retained')
        return data.decode('utf-8', errors='replace').strip()

    report = {'schema': 2, 'status': 'incomplete', 'physical_verified': False,
              'm1_accepted': False, 'artifacts': artifacts,
              'serial': serial, 'requested_samples': sample_count,
              'interval_seconds': interval_seconds, 'samples': [],
              'started_utc': now(),
              'scope': 'Read-only point snapshots; no input trace, peak memory or pen latency'}
    try:
        candidate = Path(apk) if apk is not None else Path(__file__).resolve().parents[1] / 'dist/NoteCore-0.1.0-preview.apk'
        report['candidate_apk_sha256'] = hashlib.sha256(candidate.read_bytes()).hexdigest()
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
        paths = read('apk-path', 'shell', 'pm', 'path', package).splitlines()
        if len(paths) != 1 or not paths[0].startswith('package:'):
            raise ValueError('expected exactly one installed APK; missing/split packages require separate review')
        remote = paths[0][len('package:'):]
        # adb shell joins arguments: reject shell syntax before using the observed path.
        if not remote.startswith('/data/app/') or not re.fullmatch(r'/[A-Za-z0-9_./+=@~-]+\.apk', remote):
            raise ValueError('unsupported installed APK path')
        digest = read('installed-apk-sha256', 'shell', 'sha256sum', remote).split()
        if len(digest) != 2 or digest[1] != remote or not re.fullmatch(r'[0-9a-f]{64}', digest[0]):
            raise ValueError('invalid installed APK digest output')
        report['installed_apk_sha256'] = digest[0]
        report['candidate_apk_matches_installed'] = digest[0] == report['candidate_apk_sha256']
        if not report['candidate_apk_matches_installed']:
            raise ValueError('installed APK does not match candidate; snapshot refused')
        identity = None
        epoch = time.monotonic()
        previous_start = None
        for index in range(sample_count):
            if previous_start is not None:
                time.sleep(max(0, previous_start + interval_seconds - time.monotonic()))
            started = time.monotonic()
            previous_start = started
            prefix = '' if sample_count == 1 else f'sample-{index:04d}-'
            sample = {'index': index, 'status': 'incomplete', 'started_utc': now(),
                      'elapsed_start_seconds': started - epoch, 'artifact_prefix': prefix}
            report['samples'].append(sample)
            pid_before = read(prefix + 'pid-before', 'shell', 'pidof', package)
            if not re.fullmatch(r'[1-9][0-9]{0,9}', pid_before):
                raise ValueError('process missing or changed: expected exactly one PID')
            if identity is not None and int(pid_before) != identity['pid']:
                raise ValueError('process missing or changed between samples')
            before = process_identity(read(prefix + 'stat-before', 'shell', 'cat',
                                           f'/proc/{pid_before}/stat'), pid_before)
            if identity is not None and before != identity:
                raise ValueError('process missing or changed between samples')
            identity = before
            sample['process_identity'] = before
            # Select the bound PID, rather than aggregating a package's multiple processes.
            frames = read(prefix + 'frames', 'shell', 'dumpsys', 'gfxinfo', pid_before, 'framestats')
            memory = read(prefix + 'memory', 'shell', 'dumpsys', 'meminfo', '-s', pid_before)
            for label, data, heading in [('frames', frames, 'Graphics info for pid'),
                                          ('memory', memory, 'MEMINFO in pid')]:
                if not re.search(r'\b' + heading + r'\s+' + pid_before + r'\s+\[' + re.escape(package) + r'\]', data):
                    raise ValueError(f'{label} output does not identify the bound preview process')
            pid_after = read(prefix + 'pid-after', 'shell', 'pidof', package)
            if pid_before != pid_after:
                raise ValueError('process missing or changed during snapshot')
            after = process_identity(read(prefix + 'stat-after', 'shell', 'cat',
                                          f'/proc/{pid_before}/stat'), pid_before)
            if before != after:
                raise ValueError('process missing or changed: PID reused during snapshot')
            sample.update(status='snapshot-collected', finished_utc=now(),
                          elapsed_end_seconds=time.monotonic() - epoch)
        # An upgrade during a series invalidates the original package binding.
        final_paths = read('apk-path-after', 'shell', 'pm', 'path', package).splitlines()
        final_digest = read('installed-apk-sha256-after', 'shell', 'sha256sum', remote).split()
        if final_paths != paths or final_digest != digest:
            raise ValueError('installed APK changed during capture')
        report['status'] = 'snapshot-collected' if sample_count == 1 else 'series-collected'
        report['origin'] = 'emulator-preflight' if is_emulator else 'device-candidate'
        report['stylus_observed'] = None
        return report
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report['error'] = str(error)
        raise
    except KeyboardInterrupt:
        report['error'] = 'capture interrupted'
        raise
    finally:
        report['finished_utc'] = now()
        with (output / 'snapshot.json').open('w', encoding='utf-8', newline='\n') as stream:
            stream.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', default='build/android-m1-' + str(uuid.uuid4()))
    parser.add_argument('--emulator-preflight', action='store_true')
    parser.add_argument('--apk', help='Expected candidate APK; defaults to packaged preview in dist')
    parser.add_argument('--sample-count', type=int, default=1, help='1..3600 point samples; not a continuous peak measurement')
    parser.add_argument('--interval-seconds', type=float, default=1.0, help='Minimum start-to-start spacing; total requested span <=3600s')
    args = parser.parse_args()
    capture(args.adb, args.serial, args.output, args.emulator_preflight, args.apk,
            args.sample_count, args.interval_seconds)
    print(args.output)
