#!/usr/bin/env python3
"""Audit actual Native geometry and CoreGraphics PNGs, retaining the rejected first composition."""
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT/'reports/apple-pdf-geometry-20261029'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def near(a, b):
    assert len(a) == len(b) and all(abs(x-y) < 1e-8 for x, y in zip(a, b)), (a, b)


def main():
    recipe = load(ROOT/'android-app/src/androidTest/assets/pdf/recipe.json')
    metadata = load(ROOT/'reports/apple-pdf-metadata-20261029/result.json')
    expected_sources = load(CASE/'source-pdfs-before.json')
    assert expected_sources == load(CASE/'source-pdfs-after.json')
    assert all(sha(ROOT/p) == digest for p, digest in expected_sources.items())
    assert load(CASE/'files-before.json') == load(CASE/'files-after.json')
    assert len(load(CASE/'files-before.json')) == 16
    assert load(CASE/'payload-before.json') == load(CASE/'payload-after.json')
    env = load(CASE/'environment.json')
    for name, digest in env['source_klib_and_binary_sha256'].items():
        assert sha(ROOT/name) == digest, name
    assert all('platform IOSSIMULATOR' in item['stdout'] for item in env['tool_outputs'])
    assert ':shared-core:compileKotlinIosSimulatorArm64 UP-TO-DATE' in (CASE/'native-klib-build.log').read_text(encoding='utf-8')
    assert not (CASE/'shared-probe-build.log').read_bytes()
    assert not (CASE/'swift-probe-build.log').read_bytes()
    geometries = load(CASE/'shared-geometry.json')
    inputs = load(CASE/'geometry-inputs.json')
    assert len(geometries) == len(inputs) == 12
    result = load(CASE/'render.json')
    rejected = load(CASE/'attempt-system-transform/render.json')
    assert result['pid'] != rejected['pid'] and result['os'].startswith('Version 27.0 ')
    assert len(result['files']) == len(rejected['files']) == 3
    for folder in (CASE, CASE/'attempt-system-transform'):
        commands = load(folder/'execution.json')
        assert len(commands) == 2 and all(c['exit_code'] == 0 and c['argv'][1:3] == ['simctl','spawn'] for c in commands)
        assert all(c['argv'][3] == env['device'] for c in commands)
        assert load(folder/'shared-geometry.json') == geometries
    expected_colors = [tuple(rgb+[255]) for rgb in recipe['marker_rgb']]
    background_count = overlay_count = rejected_matches = 0
    for group, file in enumerate(result['files']):
        assert Path(file['source_path']) == ROOT/list(expected_sources)[group]
        assert len(file['pages']) == 4
        unit = 2 if group == 2 else 1
        for index, page in enumerate(file['pages']):
            g = geometries[group*4+index]
            m = metadata['files'][group]['pages'][index]
            parsed = [float(v) for v in inputs[group*4+index].split(',')]
            assert parsed == [*m['native_crop_box'], m['native_rotation'], m['leaf_user_unit']]
            expected = recipe['expected_pages'][index]
            size = (expected['width']*unit, expected['height']*unit)
            anchors = [[x*unit, y*unit] for x,y in expected['marker_xy']]
            assert (g['width'],g['height']) == (page['width'],page['height']) == size
            assert g['anchors'] == page['anchors'] == anchors
            assert g['rotation'] == page['rotation'] == expected['rotation']
            assert g['user_unit'] == page['user_unit'] == unit
            a,b,c,d,tx,ty = g['pdf_to_page']
            near(page['combined_ctm'], [a,-b,c,-d,tx,page['height']-ty])
            for kind in ('background','overlay'):
                path = CASE/'images'/f'source-{group}-page-{index}-{kind}.png'
                with Image.open(path) as image:
                    assert image.size == size
                    rgba = image.convert('RGBA')
                    pixels = [rgba.getpixel(tuple(point)) for point in anchors]
                assert pixels == [tuple(p) for p in page[kind+'_rgba_at_shared_anchors']]
                if kind == 'background':
                    assert pixels == expected_colors
                    background_count += len(pixels)
                else:
                    assert pixels == [(0,0,0,255)]*4
                    overlay_count += len(pixels)
            old = rejected['files'][group]['pages'][index]
            with Image.open(CASE/'attempt-system-transform/images'/f'source-{group}-page-{index}-background.png') as image:
                actual = [image.convert('RGBA').getpixel(tuple(point)) for point in anchors]
            assert actual == [tuple(p) for p in old['background_rgba_at_shared_anchors']]
            rejected_matches += sum(a == b for a,b in zip(actual, expected_colors))
    assert background_count == overlay_count == 48 and rejected_matches == 0
    for index in range(4):
        for kind in ('background','overlay'):
            with Image.open(CASE/'images'/f'source-0-page-{index}-{kind}.png') as a, Image.open(CASE/'images'/f'source-1-page-{index}-{kind}.png') as b:
                assert ImageChops.difference(a.convert('RGB'), b.convert('RGB')).getbbox() is None
    paths = [p for p in CASE.rglob('*') if p.is_file()] + [Path(__file__).resolve(), ROOT/'android-app/src/androidTest/assets/pdf/recipe.json', ROOT/'reports/apple-pdf-metadata-20261029/result.json']
    bindings = dict(env['source_klib_and_binary_sha256'])
    bindings.update(expected_sources)
    bindings.update({p.relative_to(ROOT).as_posix(): sha(p) for p in paths})
    report = {'schema': 'apple-pdf-geometry-1', 'status': 'passed_limited_geometry_seam',
        'requirement': 'PDF-03', 'baseline_commit': env['baseline_commit'], 'device': env['device'],
        'renderer_pid': result['pid'], 'runtime': result['os'], 'documents': 3, 'pages': 12,
        'background_color_centers': background_count, 'overlay_centers': overlay_count,
        'inherited_and_direct_source_full_bitmap_equal': True,
        'shared_geometry_execution': 'Actual original shared-core Native klib via test-only friend module; no new public facade or copied geometry formula.',
        'rejected_first_composition': {'pid': rejected['pid'], 'matching_color_centers': rejected_matches,
            'expected_centers': 48, 'process_exit_was_success_not_pixel_pass': True,
            'reason': 'Extra y-flip with native getDrawingTransform produced inverted Unit1 backgrounds; Unit2 direct composition centered unscaled content away from shared anchors. Only this measured composition is rejected, not all possible platform API usage.'},
        'decision': 'Adopt shared raw-PDF-to-canonical matrix plus one canonical-y-down to bitmap-y-up conversion in this test seam. drawPDFPage consumes raw source coordinates; do not combine the accepted shared mapping with an additional native fit/rotation/unit transform.',
        'source_pdfs_unchanged': True, 'ordinary_apple_notes_unchanged': 16, 'ordinary_payload_unchanged': True,
        'visual_review': env['review'],
        'scope': 'Actual iOS CoreGraphics bitmaps on the original three synthetic PDFs; independent recipe labels/scaled positions and retained PNG pixels verified, including inherited attributes and UserUnit2. No ordinary App PDF integration.',
        'remaining': ['Arbitrary real/encrypted/malformed PDF, canonical annotation objects/export, images/text and production resource transactions remain unimplemented.',
            'No reader admission/cancellation/final native release receipt, full heap/physical latency or C1 acceptance; command scope/ARC is not a ledger receipt.'],
        'app_pdf_integrated': False, 'c1_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT/'reports/apple-pdf-geometry.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Apple native PDF seam:12 pages/48 color centers/48 overlay centers passed; first0-of48 composition retained rejected')


if __name__ == '__main__':
    main()
