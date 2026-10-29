#!/usr/bin/env python3
"""Compare retained iOS CoreGraphics metadata with independent raw PDF dictionaries."""
import hashlib
import json
from pathlib import Path
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT/'reports/apple-pdf-metadata-20261029'
ASSETS = ROOT/'android-app/src/androidTest/assets/pdf'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    result = load(CASE/'result.json')
    env = load(CASE/'environment.json')
    manifest = load(ASSETS/'metadata-manifest.json')
    recipe = load(ASSETS/'recipe.json')
    before = load(CASE/'source-pdfs-before.json')
    assert before == load(CASE/'source-pdfs-after.json')
    expected = [manifest['source_sha256'], manifest['fixture_sha256']['inherited-geometry.pdf'],
                manifest['fixture_sha256']['user-unit-2.pdf']]
    paths = [ROOT/name for name in before]
    assert [sha(p) for p in paths] == list(before.values()) == expected
    assert env['final_compile_exit_code'] == env['spawn_exit_code'] == 0
    assert result['pid'] > 0 and result['os'].startswith('Version 27.0 ')
    assert len(result['files']) == 3 and len(env['spawn_argv']) == 8
    assert env['compile_argv'][env['compile_argv'].index('-target')+1] == 'arm64-apple-ios27.0-simulator'
    vtool = next(row['stdout'] for row in env['tool_outputs'] if '-show-build' in row['argv'])
    assert 'platform IOSSIMULATOR' in vtool and 'minos 27.0' in vtool
    assert sha(ROOT/env['probe_binary_local_path']) == env['probe_binary_sha256']
    sdk = Path(env['sdk_path'])
    assert all(sha(sdk/name) == digest for name, digest in env['sdk_headers_and_settings_sha256'].items())
    original = PdfReader(paths[0])
    count = 0
    for group, (path, file) in enumerate(zip(paths, result['files'])):
        assert Path(file['source_path']) == path and file['page_count'] == len(file['pages']) == 4
        assert not file['encrypted'] and file['unlocked']
        raw = PdfReader(path)
        children = raw.trailer['/Root']['/Pages']['/Kids']
        # Audit raw leaves before pypdf.pages materializes inherited attributes.
        for index, reference in enumerate(children):
            branch = reference.get_object()
            leaf = branch['/Kids'][0].get_object() if group == 1 else branch
            native = file['pages'][index]
            assert native['index'] == index
            assert native['leaf_keys_present'] == {key: '/'+key in leaf for key in ('MediaBox', 'CropBox', 'Rotate', 'UserUnit')}
            for key in ('MediaBox', 'CropBox', 'Rotate'):
                assert native['attribute_depth'][key] == (1 if group == 1 else 0)
                dictionary = branch if group == 1 else leaf
                raw_value = dictionary['/'+key]
                value = int(raw_value) if key == 'Rotate' else [float(v) for v in raw_value]
                field = {'MediaBox': 'media_box', 'CropBox': 'crop_box', 'Rotate': 'rotation'}[key]
                assert native['raw_effective_'+field] == native['native_'+field] == value
            assert native['native_media_box'] == recipe['media_box']
            assert native['native_crop_box'] == recipe['crop_box']
            assert native['native_rotation'] == recipe['rotations'][index]
            assert native['leaf_user_unit'] == float(leaf.get('/UserUnit', 1)) == (2 if group == 2 else 1)
            assert native['unit_default_from_proven_leaf_absence'] == (group != 2)
            count += 1
        pages = raw.pages
        for index, page in enumerate(pages):
            assert page.get_contents().get_data() == original.pages[index].get_contents().get_data()
    assert count == 12
    assert load(CASE/'files-before.json') == load(CASE/'files-after.json')
    assert len(load(CASE/'files-before.json')) == 16
    assert load(CASE/'payload-before.json') == load(CASE/'payload-after.json')
    probe = ROOT/'tools/probes/pdf-apple/PdfMetadataProbe.swift'
    bindings = {p.relative_to(ROOT).as_posix(): sha(p) for p in [*CASE.iterdir(), *paths, probe,
        Path(__file__).resolve(), ASSETS/'recipe.json', ASSETS/'metadata-manifest.json', ROOT/env['probe_binary_local_path']] if p.is_file()}
    report = {'schema': 'apple-pdf-metadata-1', 'status': 'passed_limited_metadata_capability',
        'requirement': 'PDF-03', 'source_commit': env['source_commit'], 'device': env['device'],
        'probe_pid': result['pid'], 'runtime': result['os'], 'documents': 3, 'pages': count,
        'scope': 'Actual iOS27.0 Simulator command-line CoreGraphics process, independent of ordinary App/test host. Three existing synthetic PDFs,12 pages; raw leaf/parent dictionary audit and unchanged streams. Metadata only, no pixels or shared matrix execution.',
        'effective_media_crop_and_rotations_match': True, 'parent_inheritance_observed': True,
        'leaf_user_unit_2_and_absent_default_distinguished': True,
        'native_boxes_remain_raw_coordinates_for_user_unit_2': True,
        'source_pdfs_unchanged': True, 'ordinary_apple_notes_unchanged': 16,
        'ordinary_payload_unchanged': True,
        'decision': 'Retain CGPDFPage effective box/rotation queries plus native raw page dictionary access as an Apple metadata-port candidate. UserUnit must be read independently; getBoxRect alone is not physical canonical geometry. The fixture-only32-parent traversal is not a production PDF reader.',
        'remaining': ['No pixel/background/overlay alignment, nonunit rendering transform or actual shared geometry execution in this probe.',
            'No arbitrary malformed/encrypted PDF policy, missing CropBox/Rotate fallback, full tree/reader budgets, cancellation or native-final-reader receipt validation.',
            'No ordinary PDF feature, Android metadata port, production resource transaction or C1/physical M-1 acceptance.'],
        'app_pdf_integrated': False, 'c1_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT/'reports/apple-pdf-metadata.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Apple CoreGraphics metadata matched independent dictionaries:3 documents/12 pages; metadata-port candidate only')


if __name__ == '__main__':
    main()
