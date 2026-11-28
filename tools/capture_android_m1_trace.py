"""Record a bounded Perfetto file and pull it byte-exactly; no M-1 metric acceptance."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid


def capture(adb, serial, output, seconds=310):
    if type(seconds) is not int or not 5 <= seconds <= 3600:
        raise ValueError('trace duration must be 5..3600 seconds')
    template = Path(__file__).resolve().parent / 'probes/m1-android-perfetto.pbtxt'
    config, count = re.subn(rb'(?m)^duration_ms: [0-9]+\r?$',
                            f'duration_ms: {seconds * 1000}'.encode(), template.read_bytes())
    if count != 1:
        raise ValueError('expected exactly one trace duration in the reviewed configuration')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    remote = '/data/misc/perfetto-traces/notecore-m1-' + uuid.uuid4().hex + '.pftrace'
    artifacts = {}
    now = lambda: datetime.now(timezone.utc).isoformat()
    report = {'schema': 1, 'status': 'incomplete', 'serial': serial, 'seconds': seconds,
              'remote_path': remote, 'remote_removed': False, 'artifacts': artifacts,
              'started_utc': now(), 'trace_metrics_verified': False, 'm1_accepted': False,
              'scope': 'Byte-exact Perfetto transport; pair with process/APK snapshots and inspect trace signals'}
    (output / 'config.pbtxt').write_bytes(config)
    report['config_sha256'] = hashlib.sha256(config).hexdigest()

    def run(label, *args, timeout=30, data=None):
        try:
            result = subprocess.run([adb, '-s', serial, *args], input=data,
                                    capture_output=True, timeout=timeout)
            stdout, stderr, code = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr, code = error.stdout or b'', error.stderr or b'', None
        (output / (label + '.stdout.txt')).write_bytes(stdout)
        (output / (label + '.stderr.txt')).write_bytes(stderr)
        artifacts[label] = {'returncode': code, 'sha256': hashlib.sha256(stdout).hexdigest(),
                            'stderr_sha256': hashlib.sha256(stderr).hexdigest()}
        if code != 0:
            raise RuntimeError(f'{label} ' + ('timed out' if code is None else 'failed') + '; raw output retained')
        return stdout.decode('utf-8', errors='strict').strip()

    def digest(label):
        fields = run(label, 'shell', 'sha256sum', remote).split()
        if len(fields) != 2 or fields[1] != remote or not re.fullmatch('[0-9a-f]{64}', fields[0]):
            raise ValueError('invalid remote trace digest')
        return fields[0]

    try:
        if run('state', 'get-state') != 'device':
            raise ValueError('device is not ready')
        fingerprint = run('fingerprint-before', 'shell', 'getprop', 'ro.build.fingerprint')
        if not fingerprint:
            raise ValueError('missing device fingerprint')
        # Never stream protobuf through adb shell stdout: Windows can translate LF bytes.
        run('record', 'shell', 'perfetto', '--txt', '-c', '-', '-o', remote,
            timeout=seconds + 30, data=config)
        expected = digest('remote-sha256-before')
        trace = output / 'interaction.pftrace'
        run('pull', 'pull', remote, str(trace), timeout=120)
        if not trace.is_file() or trace.stat().st_size == 0:
            raise ValueError('pulled trace is empty or missing')
        actual = hashlib.sha256(trace.read_bytes()).hexdigest()
        report.update(trace_sha256=actual, trace_bytes=trace.stat().st_size)
        if actual != expected or digest('remote-sha256-after') != expected:
            raise ValueError('trace bytes changed between device and host')
        if run('fingerprint-after', 'shell', 'getprop', 'ro.build.fingerprint') != fingerprint:
            raise ValueError('device fingerprint changed during capture')
        report['fingerprint'] = fingerprint
        report['status'] = 'trace-file-collected'
        # Delete only this invocation's generated remote file after verified local retention.
        try:
            run('cleanup', 'shell', 'rm', remote)
            report['remote_removed'] = True
        except (OSError, ValueError, RuntimeError) as error:
            report['cleanup_error'] = str(error)
        return report
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report['error'] = str(error)
        raise
    except KeyboardInterrupt:
        report['error'] = 'capture interrupted; remote trace may remain at the recorded path'
        raise
    finally:
        report['finished_utc'] = now()
        (output / 'trace.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n',
                                           encoding='utf-8', newline='\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True, help='New evidence directory')
    parser.add_argument('--seconds', type=int, default=310)
    args = parser.parse_args()
    result = capture(args.adb, args.serial, args.output, args.seconds)
    print(json.dumps(result, ensure_ascii=False, indent=2))
