"""Bind raw FrameTimeline packets to parsed rows; refuse incomplete performance windows."""
import argparse
import csv
from datetime import datetime
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess

from verify_android_m1_session import verify

MAX_SIGNED_TIME = (1 << 63) - 1


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def packets(stream):
    """Read Trace Processor's text conversion, retaining exact integer timestamps.

    Root packet braces are at column zero; nested fields and escaped string
    contents cannot terminate a packet. Do not parse protobuf uint64 via floats.
    """
    current = None
    size = count = 0
    for line in stream:
        if line.rstrip('\r\n') == 'packet {':
            if current is not None:
                raise ValueError('nested root packet')
            current, size = [], 0
        elif current is not None:
            if line.rstrip('\r\n') == '}':
                count += 1
                if count > 2_000_000:
                    raise ValueError('packet count limit exceeded')
                yield ''.join(current)
                current = None
            else:
                size += len(line)
                if size > 16 * 1024 * 1024:
                    raise ValueError('packet text limit exceeded')
                current.append(line)
        elif line.strip():
            raise ValueError('unexpected text outside packet')
    if current is not None or count == 0:
        raise ValueError('truncated or empty packet conversion')


def integer(packet, field, *, root=False):
    pattern = (r'^  ' if root else r'^\s+') + re.escape(field) + r': (-?[0-9]+)\s*$'
    values = re.findall(pattern, packet, re.MULTILINE)
    if len(values) != 1:
        raise ValueError('missing or duplicate field: ' + field)
    return int(values[0])


def check_capture_window(receipt, pre, post):
    points = [datetime.fromisoformat(value) for value in
              (pre['finished_utc'], receipt['started_utc'], receipt['finished_utc'], post['started_utc'])]
    if any(value.tzinfo is None for value in points) or points != sorted(points):
        raise ValueError('trace capture is not bounded by the supplied snapshots')


def verify_capture(capture):
    receipt = json.loads((capture/'trace.json').read_text(encoding='utf-8'))
    trace = capture/'interaction.pftrace'
    digest = sha(trace)
    if (receipt['schema'] != 1 or receipt['status'] != 'trace-file-collected'
            or receipt['trace_sha256'] != digest or receipt['trace_bytes'] != trace.stat().st_size
            or trace.stat().st_size == 0 or receipt['config_sha256'] != sha(capture/'config.pbtxt')):
        raise ValueError('trace or configuration does not match capture receipt')
    required = {'state', 'fingerprint-before', 'record', 'remote-sha256-before',
                'pull', 'remote-sha256-after', 'fingerprint-after'}
    if not required.issubset(receipt['artifacts']):
        raise ValueError('missing trace capture artifacts')
    for label, entry in receipt['artifacts'].items():
        if not re.fullmatch(r'[a-z0-9-]+', label) or (label in required and entry['returncode'] != 0):
            raise ValueError('invalid trace capture command')
        for suffix, key in (('.stdout.txt', 'sha256'), ('.stderr.txt', 'stderr_sha256')):
            if sha(capture/(label+suffix)) != entry[key]:
                raise ValueError('trace command digest differs: '+label)
    for phase in ('before', 'after'):
        if (capture/f'fingerprint-{phase}.stdout.txt').read_text(encoding='utf-8').strip() != receipt['fingerprint']:
            raise ValueError('raw device identity differs')
        if (capture/f'remote-sha256-{phase}.stdout.txt').read_text(encoding='utf-8').split() != [digest, receipt['remote_path']]:
            raise ValueError('raw device trace digest differs')
    return receipt


def audit(stream, pid, frame_rows, stats_rows):
    starts, ends = {}, {}
    unknown_events = set()
    failed_events = set()
    atrace_errors = []
    invalid_packets = []
    clock_failures = []
    service_stats = []
    frame_event_count = 0
    for packet in packets(stream):
        if '  trace_stats {' in packet:
            fields = {}
            for name in ('chunks_discarded', 'patches_discarded', 'invalid_packets', 'flushes_failed'):
                values = re.findall(r'^    '+name+r': ([0-9]+)$', packet, re.MULTILINE)
                if len(values) != 1:
                    raise ValueError('missing or duplicate service statistic: '+name)
                fields[name] = int(values[0])
            service_stats.append(fields)
        if '  ftrace_stats {' in packet:
            for field, target in [('unknown_ftrace_events', unknown_events), ('failed_ftrace_events', failed_events)]:
                target.update(re.findall(r'^\s+' + field + r': "([^"\r\n]*)"$', packet, re.MULTILINE))
            atrace_errors.extend(re.findall(r'^\s+atrace_errors: "([^"\r\n]*)"$', packet, re.MULTILINE))
        if '.frame_timeline_event] {' not in packet:
            continue
        frame_event_count += 1
        sequence = integer(packet, 'trusted_packet_sequence_id', root=True)
        producer = integer(packet, 'trusted_pid', root=True)
        cookie = integer(packet, 'cookie')
        clock = integer(packet, 'timestamp_clock_id', root=True)
        timestamp = integer(packet, 'timestamp', root=True)
        key = (producer, sequence, cookie)
        kind_match = re.findall(r'^    (expected_surface_frame_start|actual_surface_frame_start|expected_display_frame_start|actual_display_frame_start|frame_end) \{', packet, re.MULTILINE)
        if len(kind_match) != 1:
            raise ValueError('unsupported FrameTimeline event')
        kind = kind_match[0]
        event = {'producer_pid': producer, 'sequence': sequence, 'cookie': cookie,
                 'kind': kind, 'clock': clock, 'timestamp': timestamp}
        if clock != 6:
            clock_failures.append(event)
        if not 0 <= timestamp <= MAX_SIGNED_TIME:
            invalid_packets.append(event)
        target = ends if kind == 'frame_end' else starts
        if key in target:
            raise ValueError('duplicate FrameTimeline cookie in its producer/sequence scope')
        if kind != 'frame_end':
            event['pid'] = integer(packet, 'pid')
            event['token'] = integer(packet, 'token')
        target[key] = event

    app_frames = []
    for key, start in starts.items():
        if start['kind'] != 'actual_surface_frame_start' or start['pid'] != pid:
            continue
        end = ends.get(key)
        reason = None
        if start['clock'] != 6 or (end is not None and end['clock'] != 6):
            reason = 'unsupported-clock'
        elif not 0 <= start['timestamp'] <= MAX_SIGNED_TIME:
            reason = 'invalid-start-time'
        elif end is None:
            reason = 'missing-end'
        elif not 0 <= end['timestamp'] <= MAX_SIGNED_TIME:
            reason = 'end-outside-signed-int64'
        elif end['timestamp'] < start['timestamp']:
            reason = 'end-before-start'
        app_frames.append({'producer_pid': key[0], 'sequence': key[1], 'cookie': key[2],
                           'token': start['token'], 'start': start['timestamp'],
                           'end': end['timestamp'] if end else None, 'failure': reason})
    for event in invalid_packets:
        start = starts.get((event['producer_pid'], event['sequence'], event['cookie']))
        event['affected_pid'] = start.get('pid') if start else None

    parsed = [(int(row['surface_frame_token']), int(row['ts']), int(row['dur'])) for row in frame_rows]
    expected = [(f['token'], f['start'], -1 if f['failure'] else f['end'] - f['start']) for f in app_frames]
    # A missing/corrupt end must stay incomplete; it cannot disappear from the denominator.
    projection_matches = sorted(parsed) == sorted(expected)
    nonzero_stats = [dict(row, value=int(row['value'])) for row in stats_rows if int(row['value']) != 0]
    blockers = [row for row in nonzero_stats if row['severity'] in ('error', 'data_loss') or
                any(part in row['name'] for part in ('discard', 'overwritten', 'overrun', 'setup_error'))]
    reasons = []
    if not app_frames: reasons.append('no-target-app-frames')
    if any(f['failure'] for f in app_frames): reasons.append('incomplete-target-app-frames')
    if invalid_packets: reasons.append('invalid-raw-timestamps')
    if clock_failures: reasons.append('unsupported-clock')
    if not projection_matches: reasons.append('raw-parsed-frame-mismatch')
    if unknown_events or failed_events or atrace_errors: reasons.append('ftrace-configuration-incomplete')
    if blockers: reasons.append('trace-health-blockers')
    return {'status': 'window-rejected' if reasons else 'frame-window-consistent',
            'window_rejection_reasons': reasons, 'pid': pid, 'frame_timeline_events': frame_event_count,
            'app_frames': len(app_frames), 'complete_app_frames': sum(f['failure'] is None for f in app_frames),
            'raw_parsed_projection_matches': projection_matches,
            'invalid_app_frames': [f for f in app_frames if f['failure']],
            'invalid_timestamp_packets': invalid_packets, 'unsupported_clock_packets': clock_failures,
            'unknown_ftrace_events': sorted(unknown_events), 'failed_ftrace_events': sorted(failed_events),
            'atrace_errors': atrace_errors, 'nonzero_stats': nonzero_stats, 'health_blockers': blockers,
            'service_stats_snapshots': service_stats,
            'service_counter_scope': 'v46 chunks/patches discarded are service-wide cumulative counters, not session loss counts; snapshots retain order, not inferred timestamps or attribution',
            'performance_thresholds_verified': False, 'm1_accepted': False,
            'scope': 'Raw/parsed frame consistency and conservative trace-health admission only; no physical pen, workload, refresh-rate or total memory acceptance'}


def analyze(capture, pre, post, apk, processor, output):
    trace = capture / 'interaction.pftrace'
    receipt = verify_capture(capture)
    digest = sha(trace)
    session = verify(pre, post, apk, trace)
    check_capture_window(receipt, json.loads((pre/'snapshot.json').read_text(encoding='utf-8')),
                         json.loads((post/'snapshot.json').read_text(encoding='utf-8')))
    if session['serial'] != receipt['serial'] or session['fingerprint'] != receipt['fingerprint']:
        raise ValueError('trace and snapshots refer to different device identities')
    pid = session['process_identity']['pid']
    if type(pid) is not int or pid <= 0:
        raise ValueError('invalid target PID')
    report = {'status': 'analysis-incomplete', 'trace_sha256': digest, 'session': session,
              'capture_receipt_sha256': sha(capture/'trace.json'), 'config_sha256': receipt['config_sha256'],
              'm1_accepted': False, 'analyzer_sha256': sha(Path(__file__)), 'processor_sha256': sha(processor)}
    output.mkdir(parents=True, exist_ok=False)

    def run(label, args):
        (output/(label+'.args.json')).write_text(json.dumps([str(processor), *args], indent=2)+'\n', encoding='utf-8', newline='\n')
        with (output/(label+'.stdout.txt')).open('wb') as stdout, (output/(label+'.stderr.txt')).open('wb') as stderr:
            result = subprocess.run([str(processor), *args], stdout=stdout, stderr=stderr, timeout=180)
        if result.returncode:
            raise ValueError(label + ' failed; original processor output retained')
        return (output/(label+'.stdout.txt')).read_text(encoding='utf-8')

    try:
        report['processor_version'] = run('version', ['--version']).strip()
        text = output/'trace.textproto'
        run('convert', ['convert', 'text', str(trace), str(text)])
        frame_sql = f"SELECT surface_frame_token,ts,dur FROM actual_frame_timeline_slice WHERE upid IN (SELECT upid FROM process WHERE pid={pid} AND name='dev.notecore.preview');"
        stats_sql = 'SELECT name,idx,value,severity,source FROM stats;'
        rows = []
        for label, query in [('frames',frame_sql), ('stats',stats_sql)]:
            (output/(label+'.sql')).write_text(query+'\n',encoding='utf-8',newline='\n')
            data=run(label,['query','--no-progress','--format','csv',str(trace),query])
            reader = csv.DictReader(io.StringIO(data))
            columns = {'surface_frame_token','ts','dur'} if label == 'frames' else {'name','idx','value','severity','source'}
            if set(reader.fieldnames or []) != columns:
                raise ValueError('unexpected processor columns: '+label)
            rows.append(list(reader))
        if not rows[1]:
            raise ValueError('missing processor health statistics')
        with text.open(encoding='utf-8') as stream:
            report.update(audit(stream,pid,*rows))
        report['text_conversion_sha256'] = sha(text)
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        report['error'] = str(error)
        raise
    finally:
        report['evidence_sha256'] = {p.name:sha(p) for p in output.iterdir() if p.is_file() and p.name!='verification.json'}
        (output/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    return report


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('capture','pre','post','apk','processor','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    result=analyze(args.capture,args.pre,args.post,args.apk,args.processor,args.output)
    print(json.dumps({k:result[k] for k in ('status','window_rejection_reasons','app_frames','complete_app_frames')},indent=2))
    raise SystemExit(0 if result['status']=='frame-window-consistent' else 2)
