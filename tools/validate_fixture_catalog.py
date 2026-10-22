#!/usr/bin/env python3
"""Offline cross-schema gates, not a legacy decoder or device benchmark."""
import hashlib
import json
import re
from pathlib import Path
from verification_hash import verification_bytes
from verify_input_capture import checks as capture_checks

ROOT = Path(__file__).resolve().parents[1]

def load(relative):
    return json.loads((ROOT/relative).read_text(encoding='utf-8'))

def main():
    capture_results, capture_summary = capture_checks()
    trace = load('fixtures/input/manifest.json')
    payload = verification_bytes(ROOT/'fixtures/input'/trace['file'])
    assert len(payload) == trace['bytes'], 'fixture byte length mismatch'
    assert hashlib.sha256(payload).hexdigest() == trace['sha256'], 'fixture digest mismatch'
    scale = load('fixtures/scale/manifest.json')
    assert scale['origin'] == 'synthetic' and scale['device_performance_measured'] is False
    assert scale['encoding']['point_record_bytes'] == 28
    required = {'S1-ink-current-page': (1000, 100), 'S2-ink-dense-page': (10000, 100), 'S9-ink-long-stroke': (1, 100000)}
    assert {p['id'] for p in scale['profiles']} == set(required) and len(scale['profiles']) == 3
    for p in scale['profiles']:
        assert (p['strokes'], p['points_per_stroke']) == required[p['id']]
        assert p['total_points'] == p['strokes'] * p['points_per_stroke']
        assert p['bytes'] == 16 + 28 * p['total_points']
        assert re.fullmatch(r'[0-9a-f]{64}', p['sha256'])
        assert Path(p['file']).name == p['file']
    source = load('fixtures/legacy/source-schema.json')
    assert source['aar_equivalence_verified'] is False and source['personal_notes_read'] is False
    for entry in source['source_files']:
        assert re.fullmatch(r'[0-9a-f]{64}', entry['sha256']) and entry['lines'] > 0
    paths = {entry['path'] for entry in source['source_files']}
    for anchor in source['anchors']:
        assert anchor['path'] in paths and anchor['line'] > 0 and anchor['anchor']
    models = {key: {field['name']: field for field in fields} for key, fields in source['models'].items()}
    points = models['PressurePoint']
    assert len(points) == 16 and not any(name.startswith('DEFAULT_') for name in models['DrawRecord'])
    assert points['mSoundPosition']['json_key'] == 'e' and points['mPointFlag']['json_key'] == 'f'
    assert points['velocity']['serialize_false'] and points['velocity']['transient']
    assert points['alpha']['initializer_expression'].startswith('1f')
    point_proto = {f['name']: f for f in source['proto_messages']['PressurePointWrapper']}
    record_proto = {f['name']: f for f in source['proto_messages']['DrawRecordWrapper']}
    assert set(point_proto) == {'x', 'y', 'p', 'action', 'lastType', 'nextType', 'mSoundPosition', 'mPointFlag',
                                'bmType', 'pencilW', 'pencilH', 'rotation', 'tilt', 'alpha'}
    assert 'angle' not in point_proto and 'layerId' not in record_proto
    assert point_proto['alpha']['tag'] == 14 and record_proto['width']['tag'] == 4
    assert all(f['presence'] == 'optional' for f in point_proto.values())
    assert set(models['StorePressurePoint']) == {'x', 'y', 'sp', 'm'}
    assert not {'layerId', 'adhesiveTapeBg', 'adhesiveTapeType'} & set(models['StoreDrawRecord'])
    pen = source['pen_constants']
    assert pen['IMAGE'] == pen['GEOMETRY_LINE_PEN'] == 21
    assert pen['PDF'] == pen['GEOMETRY_DOTTED_PEN'] == 22
    print(json.dumps({'status': 'passed', 'scale_profiles': 3, 'synthetic_scale_points': 1200000,
                      'capture_contract_checks': len(capture_results), 'capture_origin': capture_summary['origin'],
                      'legacy_source_files': len(paths), 'legacy_anchors': len(source['anchors']),
                      'scope': 'catalog hashes and selected cross-schema facts; no migration/legacy runtime/device validation'}))

if __name__ == '__main__': main()
