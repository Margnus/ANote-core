#!/usr/bin/env python3
"""Check retained actual ART system PDF backgrounds against the original independent recipe."""
import hashlib
import json
from pathlib import Path

from verify_authorized_android_ui import read, pixel

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/pdf-art-geometry'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    native = json.loads((CASE / 'result.json').read_text(encoding='utf-8'))
    assert native['status'] == 'passed' and native['avd'] == 'NoteCoreCurrent35'
    assert native['serial'] == 'emulator-5590' and len(native['files_before_and_after']) == 5
    for name, expected in native['source_and_build_input_sha256'].items():
        assert sha(ROOT / name) == expected, ('stale source/build', name)
    source = '8c29b75c713114288652cbd9afe54a65fc82865543e8d5fd81d3bf8a737aa9ee'
    assert sha(CASE / 'source.pdf') == source
    assert sha(ROOT / 'android-app/src/androidTest/assets/pdf/crop-rotations.pdf') == source
    recipe = json.loads((ROOT / 'fixtures/pdf/recipe.json').read_text(encoding='utf-8'))
    assert json.loads((ROOT / 'android-app/src/androidTest/assets/pdf/recipe.json').read_text(encoding='utf-8')) == recipe
    observed = native['observations']
    assert observed['source_sha256_before'] == observed['source_sha256_after'] == source
    assert observed['user_unit'] == 1 and not observed['app_annotation_or_export_verified']
    assert len(observed['pages']) == 4
    checks = []
    for i, page in enumerate(observed['pages']):
        expected = recipe['expected_pages'][i]
        assert page['page'] == i and page['rotation'] == expected['rotation']
        assert (page['width'], page['height']) == (expected['width'], expected['height'])
        background = read(CASE / f'background-{i}.png')
        overlay = read(CASE / f'overlay-{i}.png')
        assert background[:2] == overlay[:2] == (page['width'], page['height'])
        assert len(page['anchors']) == 4
        for j, anchor in enumerate(page['anchors']):
            x, y = expected['marker_xy'][j]
            assert [anchor['x'], anchor['y']] == [x, y]
            actual = pixel(background, x, y)
            assert list(actual) == anchor['rgb']
            assert all(abs(a - b) <= 2 for a, b in zip(actual, recipe['marker_rgb'][j]))
            assert pixel(overlay, x, y) == (0, 0, 0)
            checks.append({'page': i, 'rotation': page['rotation'], 'x': x, 'y': y, 'background_rgb': actual})
    result = {
        'schema': 1, 'status': 'passed', 'requirement': 'PDF-03',
        'scope': 'Actual API35 system PdfRenderer/Canvas consumes original nonzero CropBox four-rotation UserUnit=1 PDF; test-only reflection calls current internal shared matrix. No App annotation/resource/export integration or Apple/physical acceptance.',
        'native_result_sha256': sha(CASE / 'result.json'), 'source_pdf_sha256': source,
        'page_count': 4, 'anchor_checks': checks, 'overlay_centers_black': 16,
        'decision': 'Adopt actual ART path for this background/shared-coordinate seam; preserve host NoSuchMethodError report as failed_environment.',
        'app_feature_integrated': False, 'apple_verified': False, 'physical_verified': False,
        'arbitrary_pdf_or_nonunit_userunit_verified': False, 'c1_complete': False, 'goal_complete': False,
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in CASE.iterdir() if p.is_file()},
        'recipe_sha256': sha(ROOT / 'fixtures/pdf/recipe.json'), 'verifier_sha256': sha(Path(__file__)),
    }
    (ROOT / 'reports/pdf-art-geometry.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': 'passed', 'pages': 4, 'anchors': len(checks), 'overlay_centers': 16}))


if __name__ == '__main__':
    main()
