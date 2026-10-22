#!/usr/bin/env python3
"""Exercise the draft capture-file boundary with synthetic records and independent bad mutations."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
from input_capture_contract import CaptureError, MAX_INT64, read_capture, time_us, validate

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'fixtures/input/capture-0.2-synthetic.json'


def checks():
    original, summary = read_capture(FIXTURE)
    require_counts = dict(real_samples=8, predicted_samples=1, committed_samples=6,
                          completed_strokes=2, cancelled_strokes=1, navigation_samples=1)
    assert summary['counts'] == require_counts
    baseline = copy.deepcopy(original)
    results = ['synthetic_history_prediction_navigation_lifecycle_and_snapshot_record']
    for unit, factor in [('millisecond', 1), ('microsecond', 1000), ('nanosecond', 1000000)]:
        clock = {'unit': unit, 'origin_raw': 17}
        assert time_us(17 + factor, clock) == 1000
    assert time_us(1016, {'unit': 'nanosecond', 'origin_raw': 17}) == 0
    assert time_us(MAX_INT64, {'unit': 'microsecond', 'origin_raw': 0}) == MAX_INT64
    results.append('integer_clock_conversion_origin_quantization_and_int64_boundary')
    pressure = copy.deepcopy(original)
    pressure['capabilities'].update(pressure=True, pressure_range=[-1, 3], pressure_fallback=None)
    for event in pressure['events']:
        for point in event.get('real_samples', []) + event.get('predicted_samples', []):
            point['pressure_raw'] = 1
    assert validate(pressure)['counts'] == require_counts
    results.append('explicit_raw_pressure_range_and_absent_sensor_fallback_are_distinct')
    truncated = copy.deepcopy(original)
    truncated['events'].pop()
    truncated['truncated'] = True
    truncated['counts'].update(real_samples=7, committed_samples=4, completed_strokes=1)
    assert validate(truncated)['truncated'] is True
    results.append('unfinished_capture_requires_explicit_truncated_flag')
    mutations = {
        'unknown_schema': lambda d: d.update(schema_version='99'),
        'synthetic_with_device': lambda d: d.update(device={'model': 'invented'}),
        'fabricated_latency': lambda d: d.update(measured_latency_us=42),
        'missing_nullable_device': lambda d: d.pop('device'),
        'missing_nullable_axis': lambda d: d['events'][0]['real_samples'][0].pop('pressure_raw'),
        'pointer_tool_changes': lambda d: d['events'][1].update(tool='finger'),
        'missing_provider': lambda d: d.pop('provider'),
        'unknown_clock_domain': lambda d: d['clock'].update(domain='wall-clock'),
        'wrong_clock_origin_kind': lambda d: d['clock'].update(domain='apple-uptime'),
        'negative_clock_origin': lambda d: d['clock'].update(origin_raw=-1),
        'boolean_time': lambda d: d['events'][0].update(time_raw=True),
        'microsecond_overflow': lambda d: (d['clock'].update(unit='millisecond'), d['events'][0].update(time_raw=MAX_INT64)),
        'event_time_order': lambda d: d['events'][1].update(time_raw=999),
        'missing_capability': lambda d: d['capabilities'].pop('pressure'),
        'missing_pressure_fallback': lambda d: d['capabilities'].update(pressure_fallback=None),
        'unsupported_pressure': lambda d: d['events'][0]['real_samples'][0].update(pressure_raw=.5),
        'unsupported_tilt': lambda d: d['events'][0]['real_samples'][0].update(tilt_radians=0),
        'singular_snapshot': lambda d: d['coordinate_snapshots'][0].update(view_to_scene=[0,0,0,0,0,0]),
        'duplicate_snapshot_version': lambda d: d['coordinate_snapshots'][1].update(version=1),
        'unknown_sample_snapshot': lambda d: d['events'][0]['real_samples'][0].update(viewport_version=99),
        'nonfinite_coordinate': lambda d: d['events'][0]['real_samples'][0].update(raw_x=float('nan')),
        'future_real_point': lambda d: d['events'][0]['real_samples'][0].update(time_raw=1001),
        'predicted_in_real': lambda d: d['events'][0]['real_samples'][0].update(source='predicted'),
        'past_prediction': lambda d: d['events'][1]['predicted_samples'][0].update(time_raw=2000),
        'prediction_capability': lambda d: d['capabilities'].update(prediction=False),
        'history_capability': lambda d: d['capabilities'].update(history=False),
        'late_generation_after_rotate': lambda d: d['events'][6].update(generation=1),
        'pointer_changes_mid_stroke': lambda d: d['events'][1].update(pointer_id=8),
        'unknown_tool': lambda d: d['events'][0].update(tool='unknown'),
        'invalid_suffix_no_partial_summary': lambda d: d['events'][-1].update(phase='down'),
        'missing_target': lambda d: d['events'][0].pop('target_at_down'),
        'bad_counts': lambda d: d['counts'].update(committed_samples=7),
        'unmarked_unfinished': lambda d: d['events'][-1].update(phase='move'),
        'boolean_generation': lambda d: d['events'][0].update(generation=True),
        'malformed_events': lambda d: d.update(events=[None]),
    }
    for name, mutate in mutations.items():
        changed = copy.deepcopy(original)
        mutate(changed)
        try:
            validate(changed)
        except CaptureError:
            results.append('rejected_' + name)
        else:
            raise AssertionError('Accepted invalid record: ' + name)
        assert original == baseline
    with tempfile.TemporaryDirectory(prefix='notecore-capture-') as folder:
        path = Path(folder) / 'capture.json'
        for name, payload in [('duplicate_field', b'{"schema_version":"0.2-draft","schema_version":"0.2-draft"}'),
                              ('nonfinite_json', b'{"x":NaN}'), ('invalid_utf8', b'\xff'),
                              ('file_budget', b' ' * (16 * 1024 * 1024 + 1))]:
            path.write_bytes(payload)
            try:
                read_capture(path)
            except CaptureError:
                results.append('rejected_' + name)
            else:
                raise AssertionError(name)
    return results, summary


def main():
    results, summary = checks()
    paths = [FIXTURE, Path(__file__), ROOT/'tools/input_capture_contract.py']
    report = {'schema': 'input-capture-contract-1', 'status': 'passed', 'checks': results,
              'check_count': len(results), 'summary': summary,
              'source_and_fixture_sha256': {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths},
              'scope': 'Synthetic draft capture-file validation, not native adapter collection, runtime replay or device acceptance.',
              'old_0_1_consumer_changed': False, 'ordinary_app_changed': False,
              'physical_device_verified': False, 'production_total_budget_verified': False, 'c1_gate_complete': False}
    (ROOT/'reports/input-capture-contract.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'checks': len(results), 'counts': summary['counts']}))


if __name__ == '__main__':
    main()
