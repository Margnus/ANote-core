"""Audit retained real Apple PDF observations against source bytes and existing recipe."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess

from PIL import Image
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    folder = Path(args.evidence).resolve()
    report = json.loads((folder / 'report.json').read_text(encoding='utf-8'))
    assert report['status'] == 'collected_pending_pixel_verification'
    assert not report['pixel_verification_complete'] and not report['app_integration'] and not report['c1_complete']

    def source(name):
        return subprocess.check_output(['git', 'show', report['commit'] + ':' + name], cwd=ROOT)

    for name, digest in report['source_sha256'].items():
        assert sha(source(name)) == digest, name
    for name, digest in report['evidence_sha256'].items():
        assert Path(name).name == name and sha((folder / name).read_bytes()) == digest, name
    recipe = json.loads(source('fixtures/pdf/recipe.json'))
    manifest = json.loads(source('android-app/src/androidTest/assets/pdf/metadata-manifest.json'))
    prefix = 'android-app/src/androidTest/assets/pdf/'
    original = source(prefix + 'crop-rotations.pdf')
    assert sha(original) == manifest['source_sha256']
    streams = [page.get_contents().get_data() for page in PdfReader(io.BytesIO(original)).pages]
    names = {'crop-rotations.pdf', 'inherited-geometry.pdf', 'user-unit-2.pdf'}
    assert {f['name'] for f in report['observations']['files']} == names
    checks = []
    for file in report['observations']['files']:
        name = file['name']
        data = source(prefix + name)
        if name in manifest['fixture_sha256']:
            assert sha(data) == manifest['fixture_sha256'][name]
        reader = PdfReader(io.BytesIO(data))
        if name == 'inherited-geometry.pdf':
            # Inspect leaf dictionaries before pypdf materializes inherited fields.
            for parent in reader.trailer['/Root']['/Pages']['/Kids']:
                leaf = parent.get_object()['/Kids'][0].get_object()
                assert all(k not in leaf for k in ('/MediaBox', '/CropBox', '/Rotate'))
        assert len(reader.pages) == len(file['pages']) == 4
        for index, (page, observed, expected) in enumerate(zip(reader.pages, file['pages'], recipe['expected_pages'])):
            assert page.get_contents().get_data() == streams[index]
            unit = 2 if name == 'user-unit-2.pdf' else 1
            assert list(page.cropbox) == recipe['crop_box'] and page.rotation == expected['rotation']
            assert page.get('/UserUnit', 1) == unit
            metadata = (observed['index'] == index and observed['rotation'] == expected['rotation']
                        and observed['crop_box'] == recipe['crop_box'] and observed['media_box'] == recipe['media_box']
                        and observed['user_unit'] == unit and observed['leaf_user_unit_present'] == (unit == 2))
            entry = {'file': name, 'page': index, 'metadata_matches_source': metadata, 'images': []}
            for kind, scale in [('raw', 1), ('physical', unit)]:
                image_name = observed[kind + '_image']
                assert Path(image_name).name == image_name
                image = Image.open(folder / image_name).convert('RGBA')
                size = (expected['width'] * scale, expected['height'] * scale)
                matched = image.size == size
                pixels = []
                for xy, color in zip(expected['marker_xy'], recipe['marker_rgb']):
                    point = tuple(value * scale for value in xy)
                    actual = image.getpixel(point) if all(0 <= point[i] < image.size[i] for i in range(2)) else None
                    ok = actual is not None and actual[3] == 255 and max(abs(actual[i] - color[i]) for i in range(3)) <= 2
                    matched = matched and ok
                    pixels.append({'xy': point, 'observed': actual, 'expected_rgb': color, 'matched': ok})
                entry['images'].append({'kind': kind, 'file': image_name, 'size': image.size,
                                        'expected_size': size, 'anchors_match': matched, 'pixels': pixels})
            checks.append(entry)
    passed = all(c['metadata_matches_source'] and all(i['anchors_match'] for i in c['images']) for c in checks)
    result = {'status': 'passed' if passed else 'completed_with_capability_limit', 'commit': report['commit'],
              'run_id': report['run_id'], 'source_and_retained_hashes_verified': True,
              'inherited_leaf_keys_absent_and_content_streams_unchanged': True,
              'source_report_sha256': sha((folder / 'report.json').read_bytes()),
              'verifier_sha256': sha(Path(__file__).read_bytes()), 'pages': checks,
              'scope': 'Actual isolated iOS simulator CoreGraphics metadata/rendering for original three fixed PDFs. No shared runtime adapter, App import/annotation/export, owner/cancellation or physical acceptance.',
              'app_integration': False, 'c1_complete': False, 'goal_complete': False}
    (folder / 'verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': result['status'], 'pages': len(checks), 'metadata_passed': sum(c['metadata_matches_source'] for c in checks),
                      'image_checks_passed': sum(i['anchors_match'] for c in checks for i in c['images'])}))


if __name__ == '__main__':
    main()
