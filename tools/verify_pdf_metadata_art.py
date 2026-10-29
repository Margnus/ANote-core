#!/usr/bin/env python3
"""Audit raw metadata and actual ART observations without turning unsupported cases green."""
import hashlib
import json
from pathlib import Path

from pypdf import PdfReader
from verify_authorized_android_ui import read, pixel

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'android-app/src/androidTest/assets/pdf'
CASE = ROOT / 'reports/pdf-metadata-art'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = json.loads((ASSETS / 'metadata-manifest.json').read_text(encoding='utf-8'))
    original = PdfReader(ASSETS / 'crop-rotations.pdf')
    assert sha(ASSETS / 'crop-rotations.pdf') == manifest['source_sha256']
    assert sha(ROOT / 'tools/generate_pdf_metadata_fixtures.py') == manifest['generator_sha256']
    raw = PdfReader(ASSETS / 'inherited-geometry.pdf')
    for i, reference in enumerate(raw.trailer['/Root']['/Pages']['/Kids']):
        parent = reference.get_object(); leaf = parent['/Kids'][0].get_object()
        assert all(key not in leaf for key in manifest['inherited_leaf_keys_absent'])
        assert list(parent['/CropBox']) == [40, 50, 340, 230] and parent['/Rotate'] == i * 90
    unit = PdfReader(ASSETS / 'user-unit-2.pdf')
    assert all(page.get('/UserUnit') == 2 for page in unit.pages)
    recipe = json.loads((ASSETS / 'recipe.json').read_text(encoding='utf-8'))
    native = {}
    for group, filename in [('user-unit', 'user-unit-2.pdf'), ('inherited', 'inherited-geometry.pdf')]:
        path = ASSETS / filename
        assert sha(path) == manifest['fixture_sha256'][filename] == sha(CASE / group / 'source.pdf')
        pages = PdfReader(path).pages
        assert len(pages) == 4
        for i, page in enumerate(pages):
            assert page.get_contents().get_data() == original.pages[i].get_contents().get_data()
        result = json.loads((CASE / group / 'result.json').read_text(encoding='utf-8'))
        for name, expected in result['source_and_build_input_sha256'].items():
            assert sha(ROOT / name) == expected, ('stale build', name)
        assert result['avd'] == 'NoteCoreCurrent35' and len(result['files_before_and_after']) == 5
        native[group] = result
    assert native['user-unit']['status'] == 'failed' and native['user-unit']['observations'] is None
    failure = (CASE / 'user-unit/run.log').read_text(encoding='utf-8')
    assert 'size 300x180' in failure and 'UserUnit 2.0' in failure and 'INSTRUMENTATION_CODE: 0' in failure
    background = read(CASE / 'user-unit/background-0.png')
    assert background[:2] == (300, 180)
    # Only the emitted first page was observed; do not claim four failed pages or successful overlays.
    for xy, rgb in zip(recipe['expected_pages'][0]['marker_xy'], recipe['marker_rgb']):
        assert pixel(background, *xy) == tuple(rgb)
    assert not list((CASE / 'user-unit').glob('overlay-*.png'))
    assert native['inherited']['status'] == 'passed'
    obs = native['inherited']['observations']
    assert len(obs['pages']) == 4 and obs['source_sha256_before'] == obs['source_sha256_after'] == manifest['fixture_sha256']['inherited-geometry.pdf']
    for i, expected in enumerate(recipe['expected_pages']):
        page = obs['pages'][i]
        assert [page['width'], page['height']] == [expected['width'], expected['height']]
        bg = read(CASE / 'inherited' / f'background-{i}.png')
        overlay = read(CASE / 'inherited' / f'overlay-{i}.png')
        for xy, rgb in zip(expected['marker_xy'], recipe['marker_rgb']):
            assert pixel(bg, *xy) == tuple(rgb) and pixel(overlay, *xy) == (0, 0, 0)
    assert native['user-unit']['files_before_and_after'] == native['inherited']['files_before_and_after']
    report = {
        'schema': 1, 'status': 'completed_with_capability_limit', 'requirement': 'PDF-03',
        'inherited_crop_rotation': {'status': 'passed', 'pages': 4, 'anchors': 16, 'overlay_centers': 16},
        'nonunit_userunit_direct_dimensions': {'status': 'failed', 'pages_reached': 1,
            'user_unit': 2, 'reported_size': [300, 180], 'shared_physical_size': [600, 360],
            'decision': 'Reject using raw PdfRenderer dimensions directly as physical canonical page geometry for nonunit UserUnit. Metadata-to-render transform adapter is not implemented.'},
        'scope': 'Two metadata-only derivatives of original synthetic source; raw parent/leaf dictionaries and unchanged content streams audited. Actual API35 ART, no ordinary PDF feature, no production metadata parser or physical performance claim.',
        'provider_source_files_preserved': True, 'five_note_files_preserved': True,
        'poppler_review': 'First page rendered with explicit CropBox for visual context; missing display-font warnings retained. Not used to override ART size failure or as pixel oracle.',
        'apple_verified': False, 'app_pdf_integrated': False, 'c1_complete': False, 'goal_complete': False,
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in CASE.rglob('*') if p.is_file()},
        'fixture_sha256': manifest['fixture_sha256'], 'manifest_sha256': sha(ASSETS / 'metadata-manifest.json'),
        'generator_sha256': manifest['generator_sha256'], 'verifier_sha256': sha(Path(__file__)),
    }
    (ROOT / 'reports/pdf-metadata-art.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': report['status'], 'inherited': 'passed', 'user_unit_direct_size': 'failed'}))


if __name__ == '__main__':
    main()
