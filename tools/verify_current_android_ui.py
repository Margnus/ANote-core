#!/usr/bin/env python3
"""Verify retained observations from the authorized current API35 ordinary App."""
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

from verify_ios_preview_ui import document
from verify_authorized_android_ui import read, pixel

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/current-api35-ui'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def nodes(label):
    return list(ET.fromstring((CASE / (label + '.xml')).read_bytes()).iter('node'))


def main():
    runtime = json.loads((ROOT / 'reports/current-api35-runtime.json').read_text(encoding='utf-8'))
    assert runtime['status'] == 'passed' and runtime['avd'] == 'NoteCoreCurrent35'
    for relative, expected in runtime['source_and_build_input_sha256'].items():
        assert digest(ROOT / relative) == expected, ('source changed after runtime', relative)
    ordinary = json.loads((CASE / 'runtime.json').read_text(encoding='utf-8'))
    assert ordinary['old_ordinary_pid'] != ordinary['new_ordinary_pid']
    assert ordinary['before_instrumentation_notes'] == {} and ordinary['notes_retained']
    labels = ('06-line', '07-dot', '08-undo', '09-redo', '19-cold-reopened')
    docs = [document(CASE / (label + '.ncp')) for label in labels]
    assert [len(doc[2]) for doc in docs] == [1, 2, 1, 2, 2]
    assert docs[0][2] == docs[2][2]
    for before, after in zip(docs[1][2], docs[3][2]):
        assert before[0][:3] + before[0][4:] == after[0][:3] + after[0][4:]
        assert before[1] == after[1]
    assert docs[3] == docs[4]
    final = CASE / '19-cold-reopened.ncp'
    assert final.read_bytes() == (CASE / '09-redo.ncp').read_bytes()
    assert digest(final) == ordinary['saved_reopened_sha256']
    assert docs[4][0][2] == 'Current35-20261029'
    assert any(n.get('text') == '已打开 · 2 笔' for n in nodes('19-cold-reopened'))
    for label in ('11-import-system-picker', '14-export-system-picker'):
        assert any(n.get('package') == 'com.android.documentsui' for n in nodes(label))
    for label in ('12-import-cancelled', '15-export-cancelled'):
        assert any(n.get('package') == 'dev.notecore.preview' for n in nodes(label))
    assert any(n.get('package') == 'com.android.launcher3' for n in nodes('16-home'))
    ink = tuple((int(docs[4][2][0][0][6]) >> shift) & 255 for shift in (16, 8, 0))
    for label in ('07-dot', '09-redo', '19-cold-reopened'):
        assert pixel(read(CASE / (label + '.png')), 600, 1350) == ink, label
    assert pixel(read(CASE / '08-undo.png'), 600, 1350) == (255, 255, 255)
    result = {
        'schema': 1, 'status': 'passed', 'avd': runtime['avd'], 'serial': runtime['serial'],
        'installed_apk_sha256': runtime['installed_apk_sha256'], 'ordinary_runtime': ordinary,
        'checks': ['create_rename_and_system_input_line_dot', 'undo_redo_file_and_visible_dot',
                   'save_library_reopen', 'system_import_and_export_picker_cancel_return',
                   'home_foreground', 'force_stop_new_pid_exact_saved_file_reopen'],
        'stroke_counts': [len(doc[2]) for doc in docs],
        'scope': 'Authorized ADB system input in a separately launched ordinary App, new API35 AVD. Completed-write force-stop only; no partial-write crash, physical stylus/latency/heap or Apple acceptance.',
        'full_provider_file_exchange_verified': False, 'c1_complete': False, 'goal_complete': False,
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): digest(p) for p in CASE.iterdir() if p.is_file()},
        'runtime_report_sha256': digest(ROOT / 'reports/current-api35-runtime.json'),
        'verifier_sha256': digest(Path(__file__)),
    }
    (ROOT / 'reports/current-api35-ordinary-ui.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': result['status'], 'checks': result['checks']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
