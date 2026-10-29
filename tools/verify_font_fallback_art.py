#!/usr/bin/env python3
"""Validate retained actual per-glyph font selection; no font binaries or coverage parser required."""
import hashlib
import json
from pathlib import Path

from verify_authorized_android_ui import read, pixel

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/font-fallback-art'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    native = json.loads((CASE / 'result.json').read_text(encoding='utf-8'))
    assert native['status'] == 'passed' and native['avd'] == 'NoteCoreCurrent35' and native['api'] == '35'
    for relative, expected in native['source_and_build_input_sha256'].items():
        assert sha(ROOT / relative) == expected, ('stale source/build', relative)
    observed = native['observations']
    rows = observed['samples']
    assert [row['text'] for row in rows] == ['A', '笔', '😀', 'e\u0301']
    assert [row['utf16_length'] for row in rows] == [1, 1, 2, 2]
    assert all(row['has_glyph'] and row['glyph_count'] == 1 for row in rows)
    assert [row['fallback_observed'] for row in rows] == [False, True, True, False]
    hashes = observed['font_sha256_before_and_after']
    primary = hashes[observed['primary_font']]
    for i, row in enumerate(rows):
        assert len(row['fonts']) == row['glyph_count']
        different = False
        for font in row['fonts']:
            assert font['glyph_id'] > 0 and font['ttc_index'] >= 0
            assert len(hashes[font['file']]) == 64
            different |= hashes[font['file']] != primary or font['ttc_index'] != 0
        assert different == row['fallback_observed']
    assert rows[1]['fonts'][0]['file'].endswith('/NotoSansCJK-Regular.ttc')
    assert rows[2]['fonts'][0]['file'].endswith('/NotoColorEmoji.ttf')
    assert len(native['files_before_and_after']) == 5 and observed['input_text_unchanged']
    assert not observed['native_font_release_verified'] and not observed['app_text_box_or_cross_platform_layout_verified']
    image = read(CASE / 'samples.png')
    assert image[:2] == (320, 320)
    ink_pixels = [sum(pixel(image, x, y) != (255, 255, 255)
                      for y in range(top, bottom) for x in range(16, 112))
                  for top, bottom in [(8, 80), (80, 152), (152, 224), (224, 304)]]
    assert all(value > 0 for value in ink_pixels)
    result = {
        'schema': 1, 'status': 'passed', 'requirement': 'OBJ-02',
        'scope': 'Read-only actual system-font samples on API35. TextRunShaper per-glyph Font file hash/TTC index distinguishes requested font and fallback despite hasGlyph=true. Native Canvas render retained; no font binary redistribution, App text box or production font catalog.',
        'native_result_sha256': sha(CASE / 'result.json'), 'native_samples': rows,
        'font_file_sha256': hashes, 'rendered_ink_pixels_per_sample': ink_pixels,
        'decision': 'Do not use Paint.hasGlyph alone to certify requested-font coverage. Keep logical UTF-16 text separate from glyph count; actual fallback source must be explicit in a future font policy.',
        'cross_platform_font_equivalence_verified': False, 'legacy_api26_diagnostics_verified': False,
        'font_native_release_verified': False, 'app_text_box_integrated': False,
        'apple_verified': False, 'physical_verified': False, 'c1_complete': False, 'goal_complete': False,
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in CASE.iterdir() if p.is_file()},
        'verifier_sha256': sha(Path(__file__)),
    }
    (ROOT / 'reports/font-fallback-art.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': 'passed', 'native_samples': 4, 'fallback_samples': 2,
                      'text_lengths': [row['utf16_length'] for row in rows]}))


if __name__ == '__main__':
    main()
