"""Adversarial collector protocol checks with a fake ADB; no physical evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch

from capture_android_m1 import capture
from verify_android_m1_session import verify


def checks():
    results = []
    remote = '/data/app/~~fixture/pkg-123/base.apk'
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        apk = root / 'candidate.apk'
        apk.write_bytes(b'explicit synthetic candidate')
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()

        def case(name, mutation=None, expected=None, emulator=False, sample_count=1):
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
                    ('shell', 'cat', '/proc/101/stat'): '101 (preview ) worker) ' + ' '.join(['S'] + ['0'] * 18 + ['999']),
                    ('shell', 'dumpsys', 'gfxinfo', '101', 'framestats'): '** Graphics info for pid 101 [dev.notecore.preview] **\nPROFILEDATA synthetic',
                    ('shell', 'dumpsys', 'meminfo', '-s', '101'): '** MEMINFO in pid 101 [dev.notecore.preview] **\nTOTAL PSS: 12345',
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
            clock = [0.0]
            def monotonic():
                clock[0] += 0.01
                return clock[0]
            def sleep(seconds):
                clock[0] += seconds
            with patch('capture_android_m1.subprocess.run', side_effect=run), patch(
                    'capture_android_m1.time.monotonic', side_effect=monotonic), patch(
                    'capture_android_m1.time.sleep', side_effect=sleep):
                try:
                    capture('fake-adb', 'emulator-fixture' if emulator else 'physical-candidate', out,
                            apk=apk, sample_count=sample_count)
                except (ValueError, RuntimeError, subprocess.TimeoutExpired, KeyboardInterrupt) as caught:
                    error = caught
            report = json.loads((out / 'snapshot.json').read_text(encoding='utf-8'))
            assert not report['m1_accepted'] and not report['physical_verified']
            assert report['started_utc'] <= report['finished_utc']
            for key, artifact in report['artifacts'].items():
                assert hashlib.sha256((out / (key + '.txt')).read_bytes()).hexdigest() == artifact['sha256']
                assert hashlib.sha256((out / (key + '.stderr.txt')).read_bytes()).hexdigest() == artifact['stderr_sha256']
            if expected:
                assert error is not None and expected in report['error'], (name, error)
                assert report['status'] == 'incomplete'
            else:
                assert error is None and report['status'] == ('snapshot-collected' if sample_count == 1 else 'series-collected')
                assert report['candidate_apk_matches_installed']
                assert len(report['samples']) == sample_count
                for sample in report['samples']:
                    assert sample['status'] == 'snapshot-collected'
                    assert sample['process_identity'] == {'pid': 101, 'starttime_ticks': 999}
                    assert sample['elapsed_end_seconds'] > sample['elapsed_start_seconds'] >= 0
            results.append(name)
            return calls, report

        case('matching_candidate_keeps_acceptance_false')
        case('matching_post_snapshot')
        pre = root / 'matching_candidate_keeps_acceptance_false'
        post = root / 'matching_post_snapshot'
        trace = root / 'interaction.pftrace'
        trace.write_bytes(b'synthetic trace bytes')
        bound = verify(pre, post, apk, trace)
        assert bound['status'] == 'snapshot-pair-verified'
        assert bound['process_identity'] == {'pid': 101, 'starttime_ticks': 999}
        assert bound['trace_sha256'] == hashlib.sha256(trace.read_bytes()).hexdigest()
        assert not bound['trace_metrics_verified'] and not bound['m1_accepted']

        def refused(before, after, message):
            try:
                verify(before, after, apk, trace)
            except ValueError as error:
                assert message in str(error), error
            else:
                raise AssertionError(message)

        refused(post, pre, 'pre/post order')
        frames = post / 'frames.txt'
        original_frames = frames.read_bytes()
        frames.write_bytes(b'changed')
        refused(pre, post, 'digest mismatch')
        frames.write_bytes(original_frames)
        receipt = post / 'snapshot.json'
        original = receipt.read_bytes()
        replacement = json.loads(original)
        replacement['serial'] = 'another-device'
        receipt.write_text(json.dumps(replacement), encoding='utf-8')
        refused(pre, post, 'changed between snapshots')
        replacement = json.loads(original)
        replacement['samples'][0]['process_identity']['starttime_ticks'] = 1000
        for label in ('stat-before', 'stat-after'):
            path = post / (label + '.txt')
            updated = path.read_bytes().removesuffix(b'999') + b'1000'
            path.write_bytes(updated)
            replacement['artifacts'][label]['sha256'] = hashlib.sha256(updated).hexdigest()
        receipt.write_text(json.dumps(replacement), encoding='utf-8')
        refused(pre, post, 'changed between snapshots')
        for label in ('stat-before', 'stat-after'):
            path = post / (label + '.txt')
            path.write_bytes(path.read_bytes().removesuffix(b'1000') + b'999')
        receipt.write_bytes(original)
        trace.write_bytes(b'')
        refused(pre, post, 'trace file is empty')
        results.append('paired_snapshots_refuse_mismatch_tampering_and_empty_trace')

        calls, _ = case('emulator_refused_before_package_reads', expected='emulator rejected', emulator=True)
        assert not any('pm' in args for args in calls)
        calls, _ = case('wrong_candidate_refused_before_metrics',
                        lambda args, text, count: '0' * 64 + '  ' + remote if args[:2] == ('shell', 'sha256sum') else text,
                        'does not match')
        assert not any('gfxinfo' in args for args in calls)
        case('successful_command_without_metrics_refused',
             lambda args, text, count: 'No process found' if 'gfxinfo' in args else text,
             'frames output does not identify')
        case('wrong_process_memory_refused',
             lambda args, text, count: text.replace('pid 101', 'pid 102') if 'meminfo' in args else text,
             'memory output does not identify')
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

        calls, report = case('bounded_series_keeps_each_raw_sample_and_timing', sample_count=3)
        assert sum('meminfo' in args for args in calls) == 3
        assert all('101' in args for args in calls if 'gfxinfo' in args or 'meminfo' in args)
        starts = [sample['elapsed_start_seconds'] for sample in report['samples']]
        assert all(b - a >= 1 for a, b in zip(starts, starts[1:]))
        assert 'sample-0002-memory' in report['artifacts']

        calls, _ = case('multiple_pids_refused_before_metrics',
                       lambda args, text, count: '101 102' if 'pidof' in args else text,
                       'exactly one PID')
        assert not any('gfxinfo' in args for args in calls)
        case('malformed_stat_refused',
             lambda args, text, count: 'truncated stat' if 'cat' in args else text,
             'invalid process stat')
        case('pid_reuse_within_sample_refused',
             lambda args, text, count: text[:-3] + '1000' if 'cat' in args and count == 2 else text,
             'PID reused')
        _, report = case('pid_reuse_between_samples_retains_first_sample',
             lambda args, text, count: text[:-3] + '1000' if 'cat' in args and count >= 3 else text,
             'between samples', sample_count=3)
        assert report['samples'][0]['status'] == 'snapshot-collected'
        assert report['samples'][1]['status'] == 'incomplete'
        assert len(report['samples']) == 2
        case('pid_change_between_samples_refused',
             lambda args, text, count: '102' if 'pidof' in args and count >= 3 else text,
             'between samples', sample_count=3)

        package_reads = [0]
        def upgrade(args, text, count):
            if args[:3] == ('shell', 'pm', 'path'):
                package_reads[0] += 1
                if package_reads[0] == 2:
                    return 'package:/data/app/new/base.apk'
            return text
        case('installed_package_replacement_refused', upgrade, 'APK changed', sample_count=2)
        case('installed_digest_changed_refused',
             lambda args, text, count: '0' * 64 + '  ' + remote
             if 'sha256sum' in args and count >= 2 else text, 'APK changed')

        def interrupt(args, text, count):
            if 'gfxinfo' in args and count == 3:
                raise KeyboardInterrupt()
            return text
        _, report = case('interruption_retains_completed_sample', interrupt, 'capture interrupted', sample_count=3)
        assert report['samples'][0]['status'] == 'snapshot-collected'
        assert report['samples'][1]['status'] == 'incomplete'

        def later_timeout(args, text, count):
            if 'meminfo' in args and count == 3:
                raise subprocess.TimeoutExpired('fake-adb', 30, output=b'partial memory', stderr=b'late timeout')
            return text
        _, report = case('later_timeout_retains_prior_samples_and_partial_memory', later_timeout,
                         'timed out', sample_count=3)
        assert report['samples'][0]['status'] == 'snapshot-collected'
        assert report['artifacts']['sample-0001-memory']['timed_out']

        for count, interval in [(0, 1), (3601, 1), (True, 1), (2, 0), (2, float('nan')),
                                (2, float('inf')), (2, 61), (3600, 60)]:
            out = root / 'invalid-parameters'
            with patch('capture_android_m1.subprocess.run') as run:
                try:
                    capture('fake-adb', 'physical-candidate', out, apk=apk,
                            sample_count=count, interval_seconds=interval)
                except ValueError:
                    pass
                else:
                    raise AssertionError((count, interval))
                run.assert_not_called()
            assert not out.exists()
        results.append('invalid_parameters_refused_before_output_or_adb')
    return {'status': 'passed', 'origin': 'synthetic-fake-adb', 'checks': results,
            'device_performance_verified': False}


if __name__ == '__main__':
    print(json.dumps(checks(), ensure_ascii=False, indent=2))
