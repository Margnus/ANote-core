#!/usr/bin/env python3
"""Stream deterministic ink-only fixture bytes; never measure device performance here."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROFILES = [
    ('S1-ink-current-page', 1000, 100, 'grid-100-columns'),
    ('S2-ink-dense-page', 10000, 100, 'overlap-10-columns'),
    ('S9-ink-long-stroke', 1, 100000, 'long-zigzag'),
]
HEADER = struct.Struct('<8sII')
POINT = struct.Struct('<ddfq')

def point(stroke, index, distribution):
    # Binary-exact fractions and integer zigzags avoid platform libm differences.
    columns = 10 if distribution == 'overlap-10-columns' else 100
    spacing = 2 if distribution == 'overlap-10-columns' else 32
    x = (stroke % columns) * spacing + index / 4
    y = (stroke // columns) * spacing + (index % 32 if index % 64 < 32 else 63 - index % 64) / 4
    pressure = (8 + index % 16) / 32
    return x, y, pressure, index * 4167

def generate(output):
    output.mkdir(parents=True, exist_ok=True)
    entries = []
    for name, strokes, samples, distribution in PROFILES:
        path = output / f'{name}.bin'
        digest = hashlib.sha256()
        bounds = [float('inf'), float('inf'), float('-inf'), float('-inf')]
        # Replace only after generation completes; interrupted runs never yield a verified final file.
        temp = path.with_suffix('.bin.tmp')
        with temp.open('wb') as stream:
            header = HEADER.pack(b'NCSCALE1', strokes, samples)
            stream.write(header); digest.update(header)
            for stroke in range(strokes):
                for i in range(samples):
                    x, y, pressure, time = point(stroke, i, distribution)
                    bounds = [min(bounds[0], x), min(bounds[1], y), max(bounds[2], x), max(bounds[3], y)]
                    record = POINT.pack(x, y, pressure, time)
                    stream.write(record); digest.update(record)
        temp.replace(path)
        entries.append({'id': name, 'file': path.name, 'strokes': strokes, 'points_per_stroke': samples,
                        'total_points': strokes * samples, 'distribution': distribution,
                        'bounds_scene_points': bounds, 'bytes': HEADER.size + strokes * samples * POINT.size,
                        'sha256': digest.hexdigest()})
    return {'schema_version': 'scale-ink-1-draft', 'generator_version': 1, 'origin': 'synthetic',
            'clock_origin': 'stroke-relative microseconds; each stroke replayed in an isolated session',
            'units': {'coordinates': 'scene-point', 'time': 'microsecond', 'pressure': 'normalized-0-to-1'},
            'encoding': {'header': 'little-endian 8-byte NCSCALE1 + uint32 strokes + uint32 points_per_stroke',
                         'point': 'little-endian float64 x, float64 y, float32 pressure, int64 time_us',
                         'point_record_bytes': POINT.size},
            'scope': 'ink subset only; not full S1/S2 documents or all S9 adversarial scenarios',
            'missing_baseline_assets': ['S1 other 49 pages, 2 text boxes, 2 images',
                                        'S2 100 mixed objects and lasso transform',
                                        'S9 queue/surface/lease scheduling'],
            'device_performance_measured': False, 'profiles': entries}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT/'.generated-fixtures/scale')
    parser.add_argument('--write-manifest', action='store_true')
    args = parser.parse_args()
    manifest = generate(args.output)
    path = ROOT/'fixtures/scale/manifest.json'
    if args.write_manifest:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    else:
        if json.loads(path.read_text(encoding='utf-8')) != manifest:
            raise SystemExit('Generated scale bytes differ from tracked manifest; investigate before updating it.')
    (args.output/'verified-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'profiles': len(manifest['profiles']),
                      'total_points': sum(p['total_points'] for p in manifest['profiles']),
                      'device_performance_measured': False}))

if __name__ == '__main__': main()
