"""Adversarial raw/parsed FrameTimeline checks; synthetic traces are not performance evidence."""
import io
import json
from pathlib import Path
import tempfile

from analyze_android_m1_trace import MAX_SIGNED_TIME, audit, check_capture_window, sha, verify_capture


def packet(kind, timestamp, cookie=1, pid=123, sequence=2, clock=6):
    content = f'      cookie: {cookie}\n'
    if kind != 'frame_end':
        content += f'      token: {cookie + 100}\n      pid: {pid}\n'
    return (f'packet {{\n  timestamp_clock_id: {clock}\n  timestamp: {timestamp}\n'
            '  [com.android.internal.FrameworksNativeTracePacket.frame_timeline_event] {\n'
            f'    {kind} {{\n{content}    }}\n  }}\n'
            f'  trusted_packet_sequence_id: {sequence}\n  trusted_pid: 7\n}}\n')


def checks():
    checked=[]
    start=packet('actual_surface_frame_start',100)
    end=packet('frame_end',120)
    row={'surface_frame_token':'101','ts':'100','dur':'20'}

    def run(name,text,rows,stats=(),rejected=None):
        result=audit(io.StringIO(text),123,rows,stats)
        if rejected:
            assert result['status']=='window-rejected' and rejected in result['window_rejection_reasons'],result
        else:
            assert result['status']=='frame-window-consistent',result
        assert not result['performance_thresholds_verified'] and not result['m1_accepted']
        checked.append(name)
        return result

    run('complete-pair-matches-independent-parsed-row',start+end,[row])
    result=run('uint64-overflow-remains-exact-and-in-denominator',
               start+packet('frame_end',MAX_SIGNED_TIME+542603152304660),
               [dict(row,dur='-1')],rejected='incomplete-target-app-frames')
    assert result['invalid_app_frames'][0]['end']==MAX_SIGNED_TIME+542603152304660
    assert result['raw_parsed_projection_matches'] and result['app_frames']==1
    run('missing-end-refused',start,[dict(row,dur='-1')],rejected='incomplete-target-app-frames')
    run('end-before-start-refused',start+packet('frame_end',90),[dict(row,dur='-1')],rejected='incomplete-target-app-frames')
    run('parsed-row-cannot-hide-invalid-frame',start+packet('frame_end',MAX_SIGNED_TIME+1),[],rejected='raw-parsed-frame-mismatch')
    run('parsed-duration-change-refused',start+end,[dict(row,dur='21')],rejected='raw-parsed-frame-mismatch')
    run('cookie-is-scoped-to-producer-and-sequence',start+end+packet('actual_surface_frame_start',200,sequence=3)+packet('frame_end',230,sequence=3),
        [row,dict(row,ts='200',dur='30')])
    run('unsupported-clock-is-not-assumed-boottime',packet('actual_surface_frame_start',100,clock=3)+packet('frame_end',120,clock=3),
        [row],rejected='unsupported-clock')
    run('empty-target-population-refused',packet('actual_surface_frame_start',100,pid=456)+end,[],rejected='no-target-app-frames')
    unknown='packet {\n  ftrace_stats {\n    unknown_ftrace_events: "vendor/missing"\n  }\n}\n'
    result=run('missing-vendor-events-remain-visible',start+end+unknown,[row],rejected='ftrace-configuration-incomplete')
    assert result['unknown_ftrace_events']==['vendor/missing']
    run('discarded-chunks-block-admission-even-with-info-severity',start+end,[row],
        [{'name':'traced_chunks_discarded','value':'1','severity':'info'}],rejected='trace-health-blockers')
    run('global-timestamp-errors-not-silently-filtered',start+end,[row],
        [{'name':'trace_sorter_negative_timestamp_dropped','value':'1','severity':'error'}],rejected='trace-health-blockers')
    def stats_packet(value):
        return 'packet {\n  trace_stats {\n    chunks_discarded: '+str(value)+'\n    patches_discarded: 0\n    invalid_packets: 0\n    flushes_failed: 0\n  }\n}\n'
    result=run('cumulative-service-counters-retain-order-without-window-attribution',
               start+stats_packet(6)+end+stats_packet(7),[row],
               [{'name':'traced_chunks_discarded','value':'7','severity':'info'}],rejected='trace-health-blockers')
    assert [s['chunks_discarded'] for s in result['service_stats_snapshots']]==[6,7]
    for name,text in [('duplicate-end',start+end+end),('truncated',start+end[:-3]),('empty',''),
                      ('inexact-timestamp',start.replace('timestamp: 100','timestamp: 100.0')+end)]:
        try:audit(io.StringIO(text),123,[row],[])
        except ValueError:checked.append(name+'-refused')
        else:raise AssertionError(name)
    values=['2026-11-28T08:00:00+00:00','2026-11-28T08:00:01+00:00',
            '2026-11-28T08:00:06+00:00','2026-11-28T08:00:07+00:00']
    def window(v):
        check_capture_window({'started_utc':v[1],'finished_utc':v[2]},
                             {'finished_utc':v[0]},{'started_utc':v[3]})
    window(values)
    for invalid in ([values[1],values[0],values[2],values[3]], [values[0],values[1],values[3],values[2]],
                    [v.replace('+00:00','') for v in values]):
        try:window(invalid)
        except ValueError:pass
        else:raise AssertionError(invalid)
    checked.append('trace-must-be-inside-snapshot-window-with-timezones')
    with tempfile.TemporaryDirectory() as temporary:
        root=Path(temporary)
        (root/'interaction.pftrace').write_bytes(b'raw-trace\x00\xff')
        (root/'config.pbtxt').write_text('duration_ms: 5000\n',encoding='utf-8')
        digest=sha(root/'interaction.pftrace')
        receipt={'schema':1,'status':'trace-file-collected','trace_sha256':digest,
                 'trace_bytes':11,'config_sha256':sha(root/'config.pbtxt'),
                 'fingerprint':'device-build','remote_path':'/remote/unique.pftrace','artifacts':{}}
        for label in ('state','fingerprint-before','record','remote-sha256-before',
                      'pull','remote-sha256-after','fingerprint-after','cleanup'):
            value=('device-build' if label.startswith('fingerprint') else
                   digest+'  /remote/unique.pftrace' if label.startswith('remote-sha256') else '')
            for suffix in ('.stdout.txt','.stderr.txt'):
                (root/(label+suffix)).write_text(value if suffix=='.stdout.txt' else '',encoding='utf-8')
            receipt['artifacts'][label]={'returncode':0,'sha256':sha(root/(label+'.stdout.txt')),
                                        'stderr_sha256':sha(root/(label+'.stderr.txt'))}
        # Cleanup failure does not invalidate verified local retention.
        receipt['artifacts']['cleanup']['returncode']=1
        (root/'trace.json').write_text(json.dumps(receipt),encoding='utf-8')
        verify_capture(root)
        checked.append('capture-binding-allows-recorded-cleanup-failure')
        for filename in ('config.pbtxt','interaction.pftrace','record.stderr.txt','remote-sha256-after.stdout.txt'):
            path=root/filename
            original=path.read_bytes();path.write_bytes(original+b'changed')
            try:verify_capture(root)
            except ValueError:checked.append(filename+'-tamper-refused')
            else:raise AssertionError(filename)
            finally:path.write_bytes(original)
        del receipt['artifacts']['record']
        (root/'trace.json').write_text(json.dumps(receipt),encoding='utf-8')
        try:verify_capture(root)
        except ValueError:checked.append('missing-capture-command-refused')
        else:raise AssertionError('missing record')
    return {'status':'passed','checks':checked,'scope':'Synthetic admission checks only','m1_accepted':False}


if __name__=='__main__':
    print(json.dumps(checks(),indent=2))
