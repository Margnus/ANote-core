#!/usr/bin/env python3
"""Check retained framework lifecycle and SAF outcomes after real font configuration changes."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/current-api35-picker-recreation'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def texts(label):
    return [n.get('text', '') for n in ET.fromstring((CASE / (label + '.xml')).read_bytes()).iter('node')]


def main():
    runtime = json.loads((CASE / 'runtime.json').read_text(encoding='utf-8'))
    assert runtime['avd'] == 'NoteCoreCurrent35' and runtime['serial'] == 'emulator-5590'
    assert runtime['font_scale_before'] == runtime['font_scale_after'] == '1.0'
    assert runtime['font_scale_transitions'] == ['1.1', '1.0', '1.1', '1.0']
    build = json.loads((ROOT / 'reports/current-api35-runtime.json').read_text(encoding='utf-8'))
    for name, expected in build['source_and_build_input_sha256'].items():
        assert sha(ROOT / name) == expected, ('stale source/build', name)
    actions = [json.loads(s) for s in (CASE / 'actions.jsonl').read_text(encoding='utf-8').splitlines()]
    settings = [a['command'][5] for a in actions if a['command'][:5] ==
                ['shell', 'settings', 'put', 'system', 'font_scale']]
    assert settings == runtime['font_scale_transitions']
    restored = [a['stdout'].strip() for a in actions if a['command'] ==
                ['shell', 'settings', 'get', 'system', 'font_scale']]
    assert restored == ['1.0']
    events = (CASE / 'events-final.txt').read_text(encoding='utf-8').splitlines()
    starts = [i for i, line in enumerate(events) if 'wm_relaunch_resume_activity:' in line
              and 'dev.notecore.preview/dev.notecore.app.MainActivity,40000000]' in line]
    assert len(starts) == 4
    lifecycles = []
    for number, start in enumerate(starts):
        block = events[start:starts[number + 1] if number + 1 < len(starts) else len(events)]
        found = []
        for tag in ('wm_on_destroy_called:', 'wm_on_create_called:', 'wm_on_activity_result_called:'):
            candidates = [(i, line) for i, line in enumerate(block) if tag in line
                          and 'dev.notecore.app.MainActivity,' in line]
            assert candidates, (number, tag)
            i, line = candidates[0]
            assert re.search(r'\s' + str(runtime['ordinary_pid']) + r'\s+' + str(runtime['ordinary_pid']) + r'\s+I\s', line), line
            found.append((i, line))
        assert [item[0] for item in found] == sorted(item[0] for item in found)
        lifecycles.append({'phase': ('import_cancel', 'import_success', 'export_success', 'export_cancel')[number],
                           'relaunch': block[0], 'callbacks': [line for _, line in found]})
    assert '导入备份' in texts('03-import-cancelled')
    assert '已导入为新笔记 · 2 笔' in texts('06-import-result-after-recreate')
    for label in ('09-export-result-after-recreate', '11-export-cancelled-after-recreate', '13-final-editor'):
        assert any(text in ('已打开 · 2 笔', '已保存到本机 · 2 笔') for text in texts(label))
    for label in ('04-import-retry-picker', '12-export-request-after-cancel'):
        assert any(n.get('package') == 'com.android.documentsui'
                   for n in ET.fromstring((CASE / (label + '.xml')).read_bytes()).iter('node'))
    previous = json.loads((ROOT / 'reports/current-api35-provider.json').read_text(encoding='utf-8'))
    for name, expected in previous['final_note_sha256'].items():
        assert sha(CASE / name) == expected
    new_name = (CASE / 'new-import-name.txt').read_text(encoding='utf-8')
    assert new_name not in previous['final_note_sha256']
    assert (CASE / new_name).read_bytes() == (CASE / 'export-after-recreate.ncp').read_bytes()
    reads = [a for a in actions if a['command'][:4] == ['exec-out', 'run-as', 'dev.notecore.preview', 'cat']]
    for action in reads:
        assert action['stdout'].encode('utf-8') == (CASE / action['command'][4].split('/')[-1]).read_bytes()
    assert len(list(CASE.glob('????????-????-????-????-????????????.ncp'))) == 4
    result = {
        'schema': 1, 'status': 'passed', 'runtime': runtime, 'lifecycles': lifecycles,
        'scope': 'Ordinary App same process, four system font-scale changes while real SAF picker is foreground. Framework events prove MainActivity destroy/create before result delivery. Rotation is handled in manifest and is not used as recreation evidence.',
        'checks': ['four_real_main_activity_recreations_before_result', 'import_cancel_then_new_request',
                   'import_success_after_recreation', 'export_exact_snapshot_after_recreation',
                   'export_cancel_then_new_request', 'three_prior_notes_unchanged',
                   'one_intentional_import_copy_retained', 'font_setting_restored'],
        'final_note_sha256': {p.name: sha(p) for p in CASE.glob('????????-????-????-????-????????????.ncp')},
        'source_build_report_sha256': sha(ROOT / 'reports/current-api35-runtime.json'),
        'process_death_verified': False, 'permission_revocation_verified': False,
        'physical_device_verified': False, 'apple_verified': False, 'c1_complete': False, 'goal_complete': False,
        'evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in CASE.iterdir() if p.is_file()},
        'verifier_sha256': sha(Path(__file__)),
    }
    (ROOT / 'reports/current-api35-picker-recreation.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': result['status'], 'checks': result['checks']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
