#!/usr/bin/env python3
"""Check retained DEV-040I observations; does not automate or infer UI interactions."""
import hashlib
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'build/dev040i'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def document(path):
    rows = [line.split('\t') for line in path.read_text(encoding='utf-8').splitlines()]
    assert rows[0] == ['NCPREVIEW', '2']
    root = rows[1]
    pages = [r for r in rows if r[0] == 'G']
    strokes = []
    for index, row in enumerate(rows):
        if row[0] != 'S':
            continue
        points = rows[index + 1:index + 1 + int(row[8])]
        assert len(points) == int(row[8]) and all(p[0] == 'P' for p in points)
        strokes.append((row, points))
    assert len(pages) == int(root[3]) and len(strokes) == int(root[4])
    return root, pages, strokes


def events(path, pid):
    result = []
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line.startswith('{'):
            continue
        row = json.loads(line)
        if row.get('processID') == pid and row.get('eventMessage', '').startswith('NOTECORE_RENDER '):
            result.append(row['eventMessage'])
    assert result and result[-1] == 'NOTECORE_RENDER owner-drained charged=0'
    presented = [s for s in result if 'frame-presented ' in s]
    assert presented and all('bitmap=0 source=provider' in s for s in presented)
    freed = settled = 0
    for item in result:
        if item == 'NOTECORE_RENDER provider-bytes-freed':
            freed += 1
        if 'provider-owner-settled ' in item:
            settled += 1
            assert settled <= freed
    assert freed == settled and freed > 0
    return {'pid': pid, 'events': len(result), 'presented': len(presented),
            'source_releases': freed, 'owner_settlements': settled, 'last_event': result[-1]}


def main():
    bindings = {}
    for name, fields in (
        ('ios-preview-build', ('source_and_build_input_sha256',)),
        ('ios-host-tests', ('source_and_framework_sha256', 'test_build_sha256', 'input_sdk_contract_sha256')),
    ):
        path = ROOT / 'reports' / (name + '.json')
        report = load(path)
        assert report['status'] == 'passed'
        for field in fields:
            assert report[field]
            for relative, digest in report[field].items():
                assert sha(ROOT / relative) == digest, relative
                bindings[relative] = digest
        bindings[str(path.relative_to(ROOT))] = sha(path)
    build = load(ROOT / 'reports/ios-preview-build.json')
    restored = load(ROOT / 'reports/ios-preview-restored.json')
    assert restored['status'] == 'passed' and restored['no_xctest_plugin']
    assert build['sha256'] == restored['artifact_sha256'] == load(CASE / 'start.json')['artifact_sha256']
    assert sha(ROOT / build['artifact']) == build['sha256']
    before, after = load(CASE / 'files-before.json'), load(CASE / 'files-after.json')
    assert len(before) == 73 and len(after) == 74
    assert all(after[k] == v for k, v in before.items())
    new = set(after) - set(before)
    assert new == {load(CASE / 'ordinary-process.json')['note_relative_path']}
    for name in ('payload-before.json', 'payload-after.json'):
        assert load(CASE / name) == restored['installed_payload_sha256']
    stages = [CASE / name for name in ('stage-01-two-strokes.ncp', 'stage-02-undo.ncp',
              'stage-03-redo.ncp', 'stage-04-two-pages.ncp',
              'stage-05-after-zoom-background.ncp', 'stage-06-reopened-final.ncp')]
    docs = [document(p) for p in stages]
    assert [(len(d[1]), len(d[2]), int(d[0][5])) for d in docs] == [
        (1, 2, 3), (1, 1, 4), (1, 2, 5), (2, 3, 8), (2, 3, 8), (2, 3, 8)]
    def stable(stroke):
        row, points = stroke
        return row[:3] + row[4:], points
    assert list(map(stable, docs[0][2])) == list(map(stable, docs[2][2]))
    assert int(docs[2][2][1][0][3]) > int(docs[0][2][1][0][3])
    final = docs[-1]
    assert final[0][2] == 'DEV040I-Ordinary-20261020'
    assert [p[5] for p in final[1]] == ['BLANK', 'GRID']
    assert [sum(s[0][2] == p[2] for s in final[2]) for p in final[1]] == [2, 1]
    assert [len(s[1]) for s in final[2]] == [2, 3, 3]
    assert [(s[0][4:8]) for s in final[2]] == [
        ['MONOLINE', '1', '-14339513', '2.5'], ['MONOLINE', '1', '-14339513', '2.5'],
        ['MONOLINE', '1', '-13276994', '5.0']]
    assert len({sha(p) for p in stages[3:]}) == 1 and after[next(iter(new))] == sha(stages[-1])
    old_pid = load(CASE / 'ordinary-process.json')['pid']
    new_pid = load(CASE / 'ordinary-process-resumed.json')['pid']
    assert old_pid != new_pid == load(CASE / 'final-live-check.json')['pid']
    epochs = [events(CASE / 'render-log-so-far.ndjson', old_pid),
              events(CASE / 'render-resumed-final.ndjson', new_pid)]
    observations = load(CASE / 'ui-observations.json')
    assert observations['method'] == 'CUA Device Hub; manual visual observations, not an automated pixel oracle'
    assert observations['normal_close_reopen'] and observations['two_pages_visually_confirmed']
    assert '本机笔记' in observations['final_ax_diff']
    for item in load(CASE / 'ui-image-origin.json').values():
        image = ROOT / item['artifact']
        raw = image.read_bytes()
        assert raw[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II', raw[16:24]) == (1206, 2622)
        assert sha(Path(item['source'])) == sha(image)
    paths = [*CASE.iterdir(), ROOT / build['artifact'], ROOT / 'reports/ios-preview-restored.json', Path(__file__)]
    bindings.update({str(p.relative_to(ROOT)): sha(p) for p in paths if p.is_file() and p.suffix != '.log'})
    report = {
        'schema': 'ios-preview-ui-1', 'status': 'passed', 'source_and_artifact_sha256': bindings,
        'scope': 'Selected ordinary UIKit simulator UI on current 4c7f78dd package, manually observed through CUA, staged native document bytes and PID-specific provider logs. No host test rerun or physical-device acceptance.',
        'artifact_sha256': build['sha256'], 'device': load(CASE / 'start.json')['device'],
        'ordinary_ui_selected_flows_verified': True, 'ordinary_provider_frames_observed': True,
        'process_epochs': epochs, 'original_ncp_files_unchanged': 73, 'new_test_documents': 1,
        'pages': 2, 'strokes_per_page': [2, 1], 'real_point_rows': 8,
        'normal_close_reopen_verified': True,
        'interruption_scope': 'Simulator was found stopped after an external interruption; persisted bytes and UI were reopened in a new ordinary PID. Not a controlled force-stop or power-loss experiment. First epoch log ends after Home, not at process exit.',
        'image_scope': 'Actual Device Hub camera PNGs copied unchanged; visual review confirms dark line/dot on blank page one and blue line/grid on page two. Hashes bind files, not pixel correctness.',
        'in_progress_static_pixel_handoff_verified': False, 'exclusive_backend_every_frame_verified': False,
        'physical_device_or_heap_verified': False, 'controlled_power_loss_verified': False,
        'android_ordinary_ui_verified': False, 'c1_gate_complete': False,
        'remaining': ['physical stylus/latency/heap', 'multi-touch/rotation/system pickers',
                      'Android ordinary UI', 'full C1 and production SQLite'],
    }
    (ROOT / 'reports/ios-preview-ui.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'epochs': epochs, 'original_files_unchanged': 73}))


if __name__ == '__main__':
    main()
