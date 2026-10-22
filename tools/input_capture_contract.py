#!/usr/bin/env python3
"""Unfrozen capture-file validation. No device collector, content authority or latency oracle."""
from decimal import Decimal
import math
import json

SCHEMA = '0.2-draft'
MAX_INT64 = (1 << 63) - 1


class CaptureError(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise CaptureError(reason)


def integer(value, reason, minimum=0):
    require(type(value) is int and minimum <= value <= MAX_INT64, reason)
    return value


def finite(value, reason):
    require(type(value) in (int, float) and math.isfinite(value), reason)
    return value


def time_us(raw, clock):
    integer(raw, 'raw-time')
    delta = raw - clock['origin_raw']
    require(delta >= 0, 'time-before-origin')
    # Integer ticks only; do not round binary seconds or lose integer precision in JSON floats.
    numerator, denominator = {'nanosecond': (1, 1000), 'microsecond': (1, 1),
                              'millisecond': (1000, 1)}[clock['unit']]
    converted = delta * numerator // denominator
    require(converted <= MAX_INT64, 'microsecond-overflow')
    return converted


def _validate(doc):
    require(isinstance(doc, dict) and doc.get('schema_version') == SCHEMA, 'schema-version')
    require(doc.get('origin') in ('synthetic', 'device'), 'origin')
    for name in ('provider', 'provider_build', 'session_id'):
        require(isinstance(doc.get(name), str) and bool(doc[name]), 'missing-' + name)
    require('device' in doc and 'measured_latency_us' in doc, 'missing-origin-metadata')
    if doc['origin'] == 'synthetic':
        require(doc.get('device') is None and doc.get('measured_latency_us') is None, 'synthetic-measurement')
    else:
        device = doc.get('device')
        require(isinstance(device, dict) and all(isinstance(device.get(k), str) and device[k]
                for k in ('model', 'os_build')), 'device-metadata')
    require(doc.get('measured_latency_us') is None, 'latency-requires-correlated-present-evidence')
    clock = doc.get('clock', {})
    require(clock.get('domain') in ('synthetic-monotonic', 'android-uptime', 'apple-uptime'), 'clock-domain')
    require((doc['origin'] == 'synthetic') == (clock['domain'] == 'synthetic-monotonic'), 'clock-origin')
    require(clock.get('unit') in ('nanosecond', 'microsecond', 'millisecond'), 'clock-unit')
    integer(clock.get('origin_raw'), 'clock-origin-raw')
    capabilities = doc.get('capabilities', {})
    for name in ('history', 'prediction', 'pressure', 'tilt', 'azimuth'):
        require(type(capabilities.get(name)) is bool, 'capability-' + name)
    refresh = capabilities.get('refresh_hz')
    require(refresh is None or 0 < finite(refresh, 'refresh-hz') <= 1000, 'refresh-hz')
    if capabilities['pressure']:
        bounds = capabilities.get('pressure_range')
        require(isinstance(bounds, list) and len(bounds) == 2, 'pressure-range')
        low, high = [finite(v, 'pressure-range') for v in bounds]
        require(low < high and capabilities.get('pressure_fallback') is None, 'pressure-range')
    else:
        require(capabilities.get('pressure_range') is None, 'unexpected-pressure-range')
        require(0 <= finite(capabilities.get('pressure_fallback'), 'pressure-fallback') <= 1, 'pressure-fallback')
    require(doc.get('input_policy') in ('stylus-only', 'explicit-finger'), 'input-policy')
    transforms = {}
    snapshots = doc.get('coordinate_snapshots')
    require(isinstance(snapshots, list) and 0 < len(snapshots) <= 4096, 'snapshot-budget')
    for snapshot in snapshots:
        version = integer(snapshot.get('version'), 'viewport-version', 1)
        require(version not in transforms and snapshot.get('raw_unit') == 'view-point'
                and snapshot.get('scene_unit') == 'scene-point', 'snapshot-version-or-unit')
        matrix = snapshot.get('view_to_scene')
        require(isinstance(matrix, list) and len(matrix) == 6, 'matrix')
        matrix = [finite(v, 'matrix') for v in matrix]
        a, b, c, d, _, _ = matrix
        determinant = a * d - b * c
        require(math.isfinite(determinant) and determinant != 0, 'singular-matrix')
        transforms[version] = matrix
    events = doc.get('events')
    require(isinstance(events, list) and 0 < len(events) <= 200000, 'event-budget')
    generation = integer(doc.get('initial_generation'), 'initial-generation', 1)
    active = None
    last_event = -1
    last_sample = {}
    stats = dict(real_samples=0, predicted_samples=0, committed_samples=0, completed_strokes=0,
                 cancelled_strokes=0, navigation_samples=0)
    for sequence, event in enumerate(events):
        require(type(event.get('sequence')) is int and event['sequence'] == sequence, 'sequence')
        require(type(event.get('generation')) is int and event['generation'] == generation, 'stale-generation')
        event_raw = integer(event.get('time_raw'), 'event-time')
        require(event_raw >= last_event, 'event-time-order')
        last_event = event_raw
        time_us(event_raw, clock)
        kind = event.get('type')
        if kind == 'lifecycle':
            require(event.get('action') in ('detach', 'rotate', 'scene-switch'), 'lifecycle-action')
            generation += 1
            require(generation <= MAX_INT64, 'generation-overflow')
            if active is not None:
                stats['cancelled_strokes'] += 1
                active = None
            continue
        require(kind == 'pointer', 'event-type')
        pointer = integer(event.get('pointer_id'), 'pointer-id')
        tool = event.get('tool')
        require(tool in ('stylus', 'finger'), 'tool')
        if active is not None and active[0] == pointer:
            require(active[1] == tool, 'pointer-tool-change')
        phase = event.get('phase')
        require(phase in ('down', 'move', 'up', 'cancel'), 'phase')
        real, predicted = event.get('real_samples'), event.get('predicted_samples')
        require(isinstance(real, list) and isinstance(predicted, list) and len(real) + len(predicted) <= 1024, 'batch-budget')
        require(not predicted or capabilities['prediction'], 'prediction-capability')
        require(not predicted or phase == 'move', 'terminal-prediction')
        require((phase == 'cancel' and not real and not predicted) or (phase != 'cancel' and bool(real)), 'phase-samples')
        for points, is_prediction in ((real, False), (predicted, True)):
            previous = event_raw if is_prediction else last_sample.get(pointer, -1)
            for point in points:
                raw = integer(point.get('time_raw'), 'sample-time')
                require(raw >= previous and (raw > event_raw if is_prediction else raw <= event_raw), 'sample-time-order')
                previous = raw
                time_us(raw, clock)
                require(point.get('source') in (('predicted',) if is_prediction else ('historical', 'main')), 'sample-source')
                require(point['source'] != 'historical' or capabilities['history'], 'history-capability')
                version = point.get('viewport_version')
                require(type(version) is int and version in transforms, 'unknown-viewport')
                x, y = finite(point.get('raw_x'), 'coordinate'), finite(point.get('raw_y'), 'coordinate')
                a, b, c, d, tx, ty = transforms[version]
                require(all(math.isfinite(v) for v in (a*x+c*y+tx, b*x+d*y+ty)), 'coordinate-overflow')
                require(all(k in point for k in ('pressure_raw', 'tilt_radians', 'azimuth_radians')), 'missing-axis-field')
                pressure = point.get('pressure_raw')
                if capabilities['pressure']:
                    require(low <= finite(pressure, 'pressure') <= high, 'pressure')
                    normalized = (Decimal(str(pressure)) - Decimal(str(low))) / (Decimal(str(high)) - Decimal(str(low)))
                    require(normalized.is_finite() and 0 <= normalized <= 1, 'pressure-normalization')
                else:
                    require(pressure is None, 'unsupported-pressure')
                for axis in ('tilt', 'azimuth'):
                    value = point.get(axis + '_radians')
                    if capabilities[axis]:
                        require(0 <= finite(value, axis) <= (math.pi/2 if axis == 'tilt' else 2*math.pi), axis)
                    else:
                        require(value is None, 'unsupported-' + axis)
            if not is_prediction and points:
                last_sample[pointer] = points[-1]['time_raw']
        stats['real_samples'] += len(real)
        stats['predicted_samples'] += len(predicted)
        if tool == 'finger' and doc['input_policy'] == 'stylus-only':
            stats['navigation_samples'] += len(real)
            continue
        if phase == 'down':
            require(active is None, 'pointer-conflict')
            target = event.get('target_at_down', {})
            require(all(isinstance(target.get(k), str) and target[k] for k in ('scene_id', 'layer_id', 'brush_id')), 'target-at-down')
            active = [pointer, tool, len(real)]
        else:
            require(active is not None and active[:2] == [pointer, tool], 'inactive-pointer')
            active[2] += len(real)
            if phase == 'up':
                stats['completed_strokes'] += 1
                stats['committed_samples'] += active[2]
                active = None
            elif phase == 'cancel':
                stats['cancelled_strokes'] += 1
                active = None
    require(type(doc.get('truncated')) is bool, 'truncated-flag')
    require(doc['truncated'] or active is None, 'unfinished-not-truncated')
    require(isinstance(doc.get('counts'), dict) and set(doc['counts']) == set(stats)
            and all(type(v) is int for v in doc['counts'].values()) and doc['counts'] == stats, 'capture-counts')
    return {'schema_version': SCHEMA, 'origin': doc['origin'], 'counts': stats,
            'truncated': doc['truncated'], 'microsecond_rounding': 'floor exact nonnegative integer delta',
            'scope': 'capture record validation only; committed_samples is a synthetic reference projection, not a durable receipt',
            'device_execution_verified': False, 'performance_measured': False}


def validate(doc):
    try:
        return _validate(doc)
    except CaptureError:
        raise
    except (KeyError, TypeError, AttributeError, IndexError, OverflowError) as error:
        raise CaptureError('invalid-structure') from error


def read_capture(path):
    # File admission bound, not a measured Python/native heap guarantee.
    with path.open('rb') as stream:
        payload = stream.read(16 * 1024 * 1024 + 1)
    require(len(payload) <= 16 * 1024 * 1024, 'file-budget')
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'duplicate-field')
            result[key] = value
        return result
    def reject_constant(value):
        raise CaptureError('nonfinite-json')
    try:
        doc = json.loads(payload.decode('utf-8'), object_pairs_hook=object_pairs, parse_constant=reject_constant)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise CaptureError('invalid-json') from error
    return doc, validate(doc)
