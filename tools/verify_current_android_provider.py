#!/usr/bin/env python3
"""Verify retained ordinary-App SAF export/import and rejected-source observations."""
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from verify_ios_preview_ui import document

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/current-api35-provider'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def texts(label):
    return [n.get('text', '') for n in ET.fromstring((CASE / (label + '.xml')).read_bytes()).iter('node')]


def main():
    runtime = json.loads((CASE / 'runtime.json').read_text(encoding='utf-8'))
    assert runtime['avd'] == 'NoteCoreCurrent35' and runtime['serial'] == 'emulator-5590'
    assert runtime['api'] == '35' and runtime['intentional_valid_import_actions'] == 2
    build = json.loads((ROOT / 'reports/current-api35-runtime.json').read_text(encoding='utf-8'))
    for relative, expected in build['source_and_build_input_sha256'].items():
        assert sha(ROOT / relative) == expected, ('stale source or build', relative)
    original_path = CASE / 'original.ncp'
    exported_path = CASE / 'exported.ncp'
    assert original_path.read_bytes() == exported_path.read_bytes()
    files = json.loads((CASE / 'files-after.json').read_text(encoding='utf-8'))
    identities = [runtime[key] for key in ('original_document', 'first_import_document', 'retry_import_document')]
    assert len(set(identities)) == 3 and set(files) == {value + '.ncp' for value in identities}
    assert (CASE / (identities[0] + '.ncp')).read_bytes() == original_path.read_bytes()
    for name, expected in files.items():
        assert sha(CASE / name) == expected
    actions = [json.loads(line) for line in (CASE / 'actions.jsonl').read_text(encoding='utf-8').splitlines()]
    listings = [a['stdout'].split() for a in actions if a['command'] ==
                ['shell', 'run-as', 'dev.notecore.preview', 'ls', 'files/preview-notes']]
    assert [len(values) for values in listings] == [1, 2, 2, 2, 3]
    assert listings[1] == listings[2] == listings[3]
    note_reads = [a for a in actions if a['command'][:4] ==
                  ['exec-out', 'run-as', 'dev.notecore.preview', 'cat']]
    for action in note_reads:
        name = action['command'][4].removeprefix('files/preview-notes/')
        assert action['stdout'].encode('utf-8') == (CASE / name).read_bytes()
    provider_reads = [a for a in actions if a['command'][:2] == ['exec-out', 'cat']
                      and a['command'][2].startswith('/sdcard/Download/')]
    assert len(provider_reads) == 3
    assert provider_reads[0]['stdout'].encode('utf-8') == exported_path.read_bytes()
    assert provider_reads[1]['stdout'].encode('utf-8') == exported_path.read_bytes()
    assert provider_reads[2]['stdout'].encode('utf-8') == exported_path.read_bytes()[:40]
    root, pages, strokes = document(original_path)
    assert len(pages) == 1 and len(strokes) == 2
    identity_sets = [{root[1], pages[0][1], pages[0][2], *(s[0][1] for s in strokes)}]
    for identity in identities[1:]:
        copy_root, copy_pages, copy_strokes = document(CASE / (identity + '.ncp'))
        assert copy_root[1] == identity and copy_root[2:5] == root[2:5] and copy_root[5] == '0'
        assert len(copy_pages) == len(pages) and len(copy_strokes) == len(strokes)
        for source, copied in zip(pages, copy_pages):
            assert source[3:6] == copied[3:6] and copied[6] == '0'
        for (source, points), (copied, copy_points) in zip(strokes, copy_strokes):
            assert source[4:] == copied[4:] and points == copy_points
            assert copied[2] == copy_pages[0][2] and copied[3] == '1'
        ids = {copy_root[1], copy_pages[0][1], copy_pages[0][2], *(s[0][1] for s in copy_strokes)}
        assert len(ids) == 5 and all(not ids.intersection(previous) for previous in identity_sets)
        identity_sets.append(ids)
    assert (CASE / 'NoteCore-invalid-truncated-20261029.ncp').read_bytes() == exported_path.read_bytes()[:40]
    assert '笔记格式无效或版本不支持，原文件已保留' in texts('06-invalid-refused')
    assert '已导入为新笔记 · 2 笔' in texts('04-imported')
    assert '已打开 · 2 笔' in texts('07-copy-reopened')
    assert '已导入为新笔记 · 2 笔' in texts('09-valid-retry-imported')
    assert sum(t.startswith('Current35-20261029\n') for t in texts('10-final-library')) == 3
    for label in ('01-export-picker', '03-import-picker', '05-invalid-picker', '08-valid-retry-picker'):
        assert any(n.get('package') == 'com.android.documentsui'
                   for n in ET.fromstring((CASE / (label + '.xml')).read_bytes()).iter('node'))
    result = {
        'schema': 1, 'status': 'passed', 'runtime': runtime,
        'scope': 'Current ordinary Android App, AOSP API35 Downloads DocumentsProvider. Original worker exports and imports via actual system picker/URI; two intentional valid imports create two copies. A separate truncated source is refused between them.',
        'checks': ['export_exact_original_bytes', 'actual_saf_picker_export_and_import',
                   'new_document_page_layer_object_identities', 'copy_geometry_brush_points_preserved',
                   'invalid_source_typed_ui_refusal', 'existing_two_documents_unchanged_after_refusal',
                   'copy_reopened', 'valid_import_retry_after_refusal', 'three_final_notes_retained',
                   'valid_and_invalid_provider_source_bytes_preserved'],
        'final_note_sha256': files, 'export_sha256': sha(exported_path),
        'installed_app_sha256_from_bound_runtime': build['installed_apk_sha256'],
        'source_build_binding_report_sha256': sha(ROOT / 'reports/current-api35-runtime.json'),
        'full_resource_backup_accepted': False, 'apple_verified': False,
        'permission_failure_verified': False, 'picker_process_death_verified': False,
        'c1_complete': False, 'goal_complete': False,
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in CASE.iterdir() if p.is_file()},
        'verifier_sha256': sha(Path(__file__)),
    }
    (ROOT / 'reports/current-api35-provider.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': result['status'], 'checks': result['checks']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
