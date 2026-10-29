#!/usr/bin/env python3
"""Verify retained ordinary SAF results after targeted background App process death."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/current-api35-picker-process'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def nodes(label):
    return list(ET.fromstring((CASE / (label + '.xml')).read_bytes()).iter('node'))


def main():
    runtime = json.loads((CASE / 'runtime.json').read_text(encoding='utf-8'))
    assert runtime['avd'] == 'NoteCoreCurrent35' and runtime['serial'] == 'emulator-5590'
    assert runtime['import_new_pid'] == runtime['export_old_pid']
    assert len({runtime['import_old_pid'], runtime['import_new_pid'], runtime['export_new_pid']}) == 3
    assert runtime['both_old_processes_observed_absent_before_picker_result']
    events = (CASE / 'events-final.txt').read_text(encoding='utf-8').splitlines()
    process_events = []
    for phase in ('import', 'export'):
        old, new = runtime[phase + '_old_pid'], runtime[phase + '_new_pid']
        kill = [i for i, line in enumerate(events) if re.search(r'am_kill\s*:', line)
                and f',{old},dev.notecore.preview,' in line and 'kill background' in line]
        start = [i for i, line in enumerate(events) if 'am_proc_start:' in line
                 and f',{new},' in line and ',dev.notecore.preview,top-activity,' in line]
        assert len(kill) == len(start) == 1 and kill[0] < start[0]
        callbacks = []
        for tag in ('wm_on_create_called:', 'wm_on_activity_result_called:'):
            matches = [i for i, line in enumerate(events) if i > start[0] and tag in line
                       and 'dev.notecore.app.MainActivity,' in line
                       and re.search(r'\s' + str(new) + r'\s+' + str(new) + r'\s+I\s', line)]
            assert matches, (phase, tag)
            callbacks.append(matches[0])
        assert callbacks[0] < callbacks[1]
        process_events.append({'phase': phase, 'kill': events[kill[0]], 'start': events[start[0]],
                               'callbacks': [events[i] for i in callbacks]})
    assert any(n.get('text') == '会话已重建，请重新导入' for n in nodes('03-import-old-result'))
    assert any(n.get('text') == '已导入为新笔记 · 2 笔' for n in nodes('05-fresh-import-success'))
    for label in ('02-picker-after-app-kill', '07-export-picker-app-dead', '04-fresh-import-picker', '09-export-retry-picker'):
        assert any(n.get('package') == 'com.android.documentsui' for n in nodes(label))
    previous = json.loads((ROOT / 'reports/current-api35-picker-recreation.json').read_text(encoding='utf-8'))
    for name, expected in previous['final_note_sha256'].items():
        assert sha(CASE / name) == expected
    new_name = (CASE / 'fresh-import-name.txt').read_text(encoding='utf-8')
    assert new_name not in previous['final_note_sha256']
    assert (CASE / new_name).read_bytes() == (CASE / 'retry-export.ncp').read_bytes()
    assert (CASE / 'rejected-export-target.bin').read_bytes() == b''
    assert (CASE / 'provider-import-source.ncp').read_bytes() == (CASE / 'f743ab19-790b-4ccb-8604-60ee539e4dbc.ncp').read_bytes()
    actions = [json.loads(s) for s in (CASE / 'actions.jsonl').read_text(encoding='utf-8').splitlines()]
    assert sum(a['command'] == ['shell', 'am', 'kill', 'dev.notecore.preview'] for a in actions) == 2
    listings = [a['stdout'].split() for a in actions if a['command'] ==
                ['shell', 'run-as', 'dev.notecore.preview', 'ls', 'files/preview-notes']]
    assert [len(values) for values in listings] == [4, 4, 5, 5, 5]
    assert listings[0] == listings[1] and listings[2] == listings[3] == listings[4]
    for action in actions:
        if action['command'][:4] == ['exec-out', 'run-as', 'dev.notecore.preview', 'cat']:
            assert action['stdout'].encode('utf-8') == (CASE / action['command'][4].split('/')[-1]).read_bytes()
    build = json.loads((ROOT / 'reports/current-api35-runtime.json').read_text(encoding='utf-8'))
    for path, expected in build['source_and_build_input_sha256'].items():
        assert sha(ROOT / path) == expected, ('stale source/build', path)
    result = {
        'schema': 1, 'status': 'passed', 'runtime': runtime, 'process_events': process_events,
        'scope': 'Actual system am kill of the background ordinary App while DocumentsUI remains foreground. Saved input files only; old import result refused, explicit new-session retry succeeds. Export loses old captured snapshot and refuses old result; new-session export succeeds.',
        'checks': ['two_background_process_kills_and_new_pid_result_delivery',
                   'stale_import_refused_without_new_copy', 'new_session_import_succeeds_once',
                   'four_existing_files_preserved', 'new_session_export_exact_bytes',
                   'provider_import_source_preserved', 'empty_rejected_export_target_retained'],
        'export_old_result_toast': {'text': '会话已重建，请重新导出',
                                    'screenshot': 'reports/current-api35-picker-process/08-export-old-result.png',
                                    'visually_verified': True, 'included_in_xml': False},
        'final_note_sha256': {p.name: sha(p) for p in CASE.glob('????????-????-????-????-????????????.ncp')},
        'in_flight_write_kill_verified': False, 'power_loss_verified': False,
        'physical_device_verified': False, 'apple_verified': False,
        'c1_complete': False, 'goal_complete': False,
        'source_build_report_sha256': sha(ROOT / 'reports/current-api35-runtime.json'),
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in CASE.iterdir() if p.is_file()},
        'verifier_sha256': sha(Path(__file__)),
    }
    (ROOT / 'reports/current-api35-picker-process.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': result['status'], 'checks': result['checks']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
