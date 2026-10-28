"""Adversarial collector protocol checks with a fake ADB; no physical evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

from capture_android_m1 import capture


def checks():
    results = []
    remote = '/data/app/~~fixture/pkg-123/base.apk'
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        apk = root / 'candidate.apk'
        apk.write_bytes(b'explicit synthetic candidate')
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()

        def case(name, mutation=None, expected=None, emulator=False):
            calls = []
            pid_reads = 0

            def run(argv, **kwargs):
                nonlocal pid_reads
                assert argv[:3] == ['fake-adb', '-s', 'emulator-fixture' if emulator else 'physical-candidate']
                args = tuple(argv[3:])
                calls.append(args)
                replies = {
                    ('get-state',): 'device',
                    ('shell', 'getprop', 'ro.kernel.qemu'): '1' if emulator else '0',
                    ('shell', 'getprop', 'ro.boot.qemu'): '1' if emulator else '0',
                    ('shell', 'pm', 'path', 'dev.notecore.preview'): 'package:' + remote,
                    ('shell', 'sha256sum', remote): digest + '  ' + remote,
                }
                text = replies.get(args, 'synthetic diagnostic')
                if args == ('shell', 'pidof', 'dev.notecore.preview'):
                    pid_reads += 1
                    text = '101'
                if mutation:
                    text = mutation(args, text, pid_reads)
                return subprocess.CompletedProcess(argv, 0, text.encode('utf-8'), b'synthetic stderr')

            out = root / name
            error = None
            with patch('capture_android_m1.subprocess.run', side_effect=run):
                try:
                    capture('fake-adb', 'emulator-fixture' if emulator else 'physical-candidate', out, apk=apk)
                except (ValueError, subprocess.TimeoutExpired) as caught:
                    error = caught
            report = json.loads((out / 'snapshot.json').read_text(encoding='utf-8'))
            assert not report['m1_accepted'] and not report['physical_verified']
            assert report['started_utc'] <= report['finished_utc']
            for key, artifact in report['artifacts'].items():
                assert hashlib.sha256((out / (key + '.txt')).read_bytes()).hexdigest() == artifact['sha256']
                assert hashlib.sha256((out / (key + '.stderr.txt')).read_bytes()).hexdigest() == artifact['stderr_sha256']
            if expected:
                assert error is not None and expected in str(error), (name, error)
                assert report['status'] == 'incomplete'
            else:
                assert error is None and report['status'] == 'snapshot-collected'
                assert report['candidate_apk_matches_installed']
            results.append(name)
            return calls, report

        case('matching_candidate_keeps_acceptance_false')
        calls, _ = case('emulator_refused_before_package_reads', expected='emulator rejected', emulator=True)
        assert not any('pm' in args for args in calls)
        calls, _ = case('wrong_candidate_refused_before_metrics',
                        lambda args, text, count: '0' * 64 + '  ' + remote if args[:2] == ('shell', 'sha256sum') else text,
                        'does not match')
        assert not any('gfxinfo' in args for args in calls)
        calls, _ = case('shell_syntax_in_observed_path_refused',
                        lambda args, text, count: 'package:/data/app/evil;touch/base.apk' if args[:3] == ('shell', 'pm', 'path') else text,
                        'unsupported installed APK path')
        assert not any('sha256sum' in args for args in calls)
        case('split_package_refused',
             lambda args, text, count: text + '\npackage:/data/app/split.apk' if args[:3] == ('shell', 'pm', 'path') else text,
             'exactly one')
        case('pid_change_refused', lambda args, text, count: '102' if 'pidof' in args and count == 2 else text,
             'process missing or changed')

        def timeout(args, text, count):
            if 'gfxinfo' in args:
                raise subprocess.TimeoutExpired('fake-adb', 30, output=b'partial frame output', stderr=b'timeout stderr')
            return text
        _, report = case('timeout_retains_partial_output_and_hashes', timeout, 'timed out')
        assert report['artifacts']['frames']['timed_out']
    return {'status': 'passed', 'origin': 'synthetic-fake-adb', 'checks': results,
            'device_performance_verified': False}


if __name__ == '__main__':
    print(json.dumps(checks(), ensure_ascii=False, indent=2))
