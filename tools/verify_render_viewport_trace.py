#!/usr/bin/env python3
"""Compare actual platform traces after verifying their source/binary bindings; no benchmark claim."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    names = {'android': 'android-brush-common-tests.json', 'apple': 'ios-host-tests.json'}
    traces = {}
    provenance = {}
    for platform, name in names.items():
        path = ROOT/'reports'/name
        report = json.loads(path.read_text(encoding='utf-8'))
        assert report['status'] == 'passed' and report['viewport_trace_test_count'] == 1
        fields = ('source_and_build_sha256',) if platform == 'android' else ('source_and_framework_sha256', 'test_build_sha256')
        for field in fields:
            for source, sha in report[field].items():
                assert digest(ROOT/source) == sha, ('Stale trace input', platform, source)
        if platform == 'android':
            assert digest(ROOT/report['test_apk']) == report['test_apk_sha256']
        trace = report['render_viewport_trace']
        assert trace['trace_id'] == 'dev034-fixed-viewport-v1' and trace['closed_drained']
        assert [trace[k] for k in ('input_points', 'max_jobs', 'resident_budget', 'total_budget')] == [4, 2, 32768, 210000]
        assert trace['viewports'] == [[0.25, 8, 0.5], [4.25, 8, 0.5], [4.25, 8, 0.25]]
        assert len(trace['queue_wait_ns']) == 4 and len(trace['copies']) == 2
        for sample in trace['queue_wait_ns']:
            assert 0 <= sample['queue_handoff_wait_lower_ns'] <= sample['queue_handoff_wait_upper_ns'] == sample['request_to_worker_ns']
            assert sample['submit_elapsed_ns'] > 0
        for copy in trace['copies']:
            assert copy['elapsed_ns'] > 0 and copy['row_storage_upper_bytes'] <= 16384
        assert [event['elapsed_ns'] for event in trace['events']] == sorted(event['elapsed_ns'] for event in trace['events'])
        traces[platform] = trace
        provenance['reports/'+name] = digest(path)
    # Derive the logical peak/resident expectations from the fixed four-point, three-segment input.
    resident = 64 * 32 * 4 + 16384
    peak = 4 * 28 + 3 * 32 + 65536 + resident
    expected = [
        ('first-held', resident, 0, 0), ('scroll-old-retired', resident, resident, 0),
        ('two-jobs-rejected-third', resident + peak * 2, resident, peak * 2),
        ('queued-cancelled', resident + peak * 2, resident, peak * 2),
        ('ready-before-ack', resident + peak * 2, resident, peak * 2),
        ('late-and-cancelled-ack', resident, resident, 0),
        ('zoom-image-held', resident * 2, resident, 0), ('closed-two-readers', resident * 2, resident * 2, 0),
        ('new-reader-released', resident, resident, 0), ('old-reader-released', 0, 0, 0),
    ]
    timings = {}
    for platform, trace in traces.items():
        assert [(e['event'], e['charged'], e['retired'], e['executor']) for e in trace['events']] == expected
        assert all(e['charged'] <= trace['total_budget'] for e in trace['events'])
        time = {event['event']: event['elapsed_ns'] for event in trace['events']}
        timings[platform] = {
            'queue_wait_bounds_ns': trace['queue_wait_ns'], 'copies': trace['copies'],
            'first_native_resident_hold_ns': time['scroll-old-retired'] - time['first-held'],
            'first_native_retired_hold_ns': time['old-reader-released'] - time['scroll-old-retired'],
            'closed_to_drained_ns': time['old-reader-released'] - time['closed-two-readers'],
        }
    provenance[Path(__file__).relative_to(ROOT).as_posix()] = digest(Path(__file__))
    result = {
        'status': 'passed', 'trace_id': 'dev034-fixed-viewport-v1', 'source_report_sha256': provenance,
        'matching_platform_events': [dict(zip(('event', 'charged', 'retired', 'executor'), event)) for event in expected],
        'platform_timings': timings,
        'scope': 'Same fixed synthetic scroll/zoom responsibility trace on actual ART Bitmap and iOS app-host Swift worker/CGImage; no ordinary app integration. Timings are single exploratory runs with deliberate worker gate/owner polling. Wait bounds bracket admission within submit and end at beforeCompute; include handoff overhead, not isolated scheduler latency. Row storage/charge is not peak heap or GPU residency.',
        'physical_device_measured': False, 'heap_or_gpu_measured': False, 'production_c1_gate_complete': False,
    }
    (ROOT/'reports/render-viewport-trace.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'matching_events': len(expected), 'peak_charged_bytes': resident + peak * 2,
                      'closed_reader_bytes': resident * 2, 'final_bytes': 0}))


if __name__ == '__main__':
    main()
