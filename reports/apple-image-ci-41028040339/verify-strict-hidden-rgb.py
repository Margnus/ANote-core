"""Audit retained real ImageIO rasters against the existing encoded-image oracle."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True)
    folder = Path(parser.parse_args().evidence).resolve()
    report = json.loads((folder / 'report.json').read_text(encoding='utf-8'))
    assert report['status'] == 'collected_pending_pixel_verification'
    def sha(data):
        return hashlib.sha256(data).hexdigest()
    def source(name):
        return subprocess.check_output(['git', 'show', report['commit'] + ':' + name], cwd=ROOT)
    for name, digest in report['source_sha256'].items():
        assert sha(source(name)) == digest, name
    for name, digest in report['evidence_sha256'].items():
        assert Path(name).name == name and sha((folder / name).read_bytes()) == digest, name
    manifest = json.loads(source('fixtures/image-orientation/manifest.json'))
    expected = {c['file']: c for c in manifest['cases']}
    rows = report['observations']['rows']
    assert len(rows) == len(expected) == 16 and {r['source'] for r in rows} == set(expected)
    checks = []
    for row in rows:
        case = expected[row['source']]
        assert sha(source('fixtures/image-orientation/' + row['source'])) == case['sha256']
        metadata = [row['metadata_width'], row['metadata_height']] == case['raw_size'] and row['metadata_orientation'] == case['orientation']
        frames = []
        for kind, samples, size, divisor in [('raw', case['raw_samples'], case['raw_size'], 1),
                ('thumbnail_raw', case['raw_samples'], [n // 2 for n in case['raw_size']], 2),
                ('thumbnail_oriented', case['display_samples'], [n // 2 for n in case['display_size']], 2)]:
            info = row[kind]
            assert Path(info['file']).name == info['file']
            image = Image.open(folder / info['file']).convert('RGBA')
            matched = list(image.size) == size == [info['width'], info['height']]
            pixels = []
            for sample in samples:
                xy = (sample['x'] // divisor, sample['y'] // divisor)
                actual = image.getpixel(xy) if 0 <= xy[0] < image.width and 0 <= xy[1] < image.height else None
                rgba = sample['rgba']
                ok = actual is not None and actual[3] == rgba[3] and max(abs(actual[i] - rgba[i]) for i in range(3)) <= 3
                matched = matched and ok
                pixels.append({'xy': xy, 'expected_rgba': rgba, 'observed': actual, 'matched': ok})
            assert info['row_payload_bytes'] == info['row_bytes'] * info['height']
            frames.append({'kind': kind, 'file': info['file'], 'pixels_match': matched, 'pixels': pixels,
                'row_payload_bytes': info['row_payload_bytes'], 'packed_argb_bytes': info['width'] * info['height'] * 4,
                'bits_per_pixel': info['bits_per_pixel'], 'bits_per_component': info['bits_per_component']})
        checks.append({'source': row['source'], 'metadata_matches': metadata, 'frames': frames})
    passed = all(c['metadata_matches'] and all(f['pixels_match'] for f in c['frames']) for c in checks)
    result = {'status': 'passed' if passed else 'completed_with_capability_limit', 'commit': report['commit'],
        'run_id': report['run_id'], 'report_sha256': sha((folder / 'report.json').read_bytes()),
        'verifier_sha256': sha(Path(__file__).read_bytes()), 'checks': checks,
        'scope': 'Actual iOS ImageIO fixed original JPEG/PNG metadata and raw/thumbnail raster observations. No shared runtime adapter, complete decode budget, owner/pin/App or physical acceptance.'}
    (folder / 'verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': result['status'], 'metadata_passed': sum(c['metadata_matches'] for c in checks),
        'frames_passed': sum(f['pixels_match'] for c in checks for f in c['frames']),
        'frames_exceed_packed_argb_payload': sum(f['row_payload_bytes'] > f['packed_argb_bytes'] for c in checks for f in c['frames'])}))


if __name__ == '__main__':
    main()
