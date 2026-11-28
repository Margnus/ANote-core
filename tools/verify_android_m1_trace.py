"""Exercise binary transport and failure ownership with a fake ADB, never device metrics."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

from capture_android_m1_trace import capture


def checks():
    payload = b'\x0a\x03\x00\x0d\x0a\x80\xff\x0a'
    digest = hashlib.sha256(payload).hexdigest()
    cases = ['exact-binary', 'translated-newlines', 'empty-pull', 'digest-changed',
             'record-timeout', 'pull-failed', 'fingerprint-changed', 'cleanup-failed',
             'cleanup-unavailable', 'malformed-digest', 'interrupted']
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for case in cases:
            calls = []
            remote = [None]
            digest_calls = [0]
            fingerprints = [0]

            def run(argv, **kwargs):
                assert argv[:3] == ['fake-adb', '-s', 'selected-device']
                args = argv[3:]
                calls.append(args)
                output, code = b'', 0
                if args == ['get-state']:
                    output = b'device'
                elif args[:2] == ['shell', 'getprop']:
                    fingerprints[0] += 1
                    output = b'changed' if case == 'fingerprint-changed' and fingerprints[0] == 2 else b'fixed'
                elif args[:2] == ['shell', 'perfetto']:
                    remote[0] = args[-1]
                    assert remote[0].startswith('/data/misc/perfetto-traces/notecore-m1-')
                    assert b'duration_ms: 5000' in kwargs['input'] and kwargs['timeout'] == 35
                    if case == 'record-timeout':
                        raise subprocess.TimeoutExpired(argv, 35, output=b'partial', stderr=b'timeout')
                    if case == 'interrupted':
                        raise KeyboardInterrupt()
                elif args[:2] == ['shell', 'sha256sum']:
                    digest_calls[0] += 1
                    sha = '0' * 64 if case == 'digest-changed' and digest_calls[0] == 2 else digest
                    output = (sha + '  ' + remote[0]).encode()
                    if case == 'malformed-digest':
                        output = (sha + '  /different-file.pftrace').encode()
                elif args[0] == 'pull':
                    data = payload.replace(b'\n', b'\r\n') if case == 'translated-newlines' else payload
                    Path(args[-1]).write_bytes(b'' if case == 'empty-pull' else data)
                    code = 1 if case == 'pull-failed' else 0
                elif args[:2] == ['shell', 'rm']:
                    assert args[2] == remote[0]
                    if case == 'cleanup-unavailable':
                        raise OSError('ADB unavailable after verified pull')
                    code = 1 if case == 'cleanup-failed' else 0
                else:
                    raise AssertionError(args)
                return subprocess.CompletedProcess(argv, code, output, b'')

            error = None
            with patch('capture_android_m1_trace.subprocess.run', side_effect=run):
                try:
                    capture('fake-adb', 'selected-device', root / case, seconds=5)
                except (ValueError, RuntimeError, KeyboardInterrupt) as caught:
                    error = caught
            report = json.loads((root / case / 'trace.json').read_text(encoding='utf-8'))
            assert not report['m1_accepted'] and not report['trace_metrics_verified']
            for label, record in report['artifacts'].items():
                for suffix, key in [('.stdout.txt', 'sha256'), ('.stderr.txt', 'stderr_sha256')]:
                    assert hashlib.sha256((root / case / (label + suffix)).read_bytes()).hexdigest() == record[key]
            removed = any(args[:2] == ['shell', 'rm'] for args in calls)
            if case in ('exact-binary', 'cleanup-failed', 'cleanup-unavailable'):
                assert error is None and report['status'] == 'trace-file-collected'
                assert report['trace_sha256'] == digest and removed
                assert report['remote_removed'] == (case == 'exact-binary')
            else:
                assert error is not None and report['status'] == 'incomplete' and not removed
            if case == 'record-timeout':
                assert (root / case / 'record.stdout.txt').read_bytes() == b'partial'
        for seconds in (0, 4, 3601, True, 5.5):
            with patch('capture_android_m1_trace.subprocess.run') as mocked:
                try:
                    capture('fake-adb', 'device', root / 'invalid', seconds)
                except ValueError:
                    pass
                else:
                    raise AssertionError(seconds)
                mocked.assert_not_called()
                assert not (root / 'invalid').exists()
    return {'status': 'passed', 'scope': 'fake ADB protocol only', 'checks': cases + ['invalid-duration'],
            'm1_accepted': False}


if __name__ == '__main__':
    print(json.dumps(checks(), indent=2))
