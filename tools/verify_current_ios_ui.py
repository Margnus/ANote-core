#!/usr/bin/env python3
"""Bind a retained, manually observed current ordinary UIKit case; never infer UI from hashes."""
import argparse
import json
from pathlib import Path
import struct
from verify_ios_preview_ui import document, events, sha

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', type=Path, required=True)
    args = parser.parse_args()
    folder = args.case.resolve()
    folder.relative_to(ROOT/'build')
    case = load(folder/'case.json')
    build = load(ROOT/'reports/ios-preview-build.json')
    restored = load(ROOT/'reports/ios-preview-restored.json')
    host = load(ROOT/'reports/ios-host-tests.json')
    assert build['status'] == restored['status'] == host['status'] == 'passed'
    assert build['sha256'] == restored['artifact_sha256'] == case['artifact_sha256']
    assert sha(ROOT/build['artifact']) == build['sha256']
    assert restored['no_xctest_plugin'] and host['test_count'] == 36
    assert case['device'] == restored['device'] == host['device']['udid']
    bindings = {}
    for report, fields in ((build, ('source_and_build_input_sha256',)),
                           (host, ('source_and_framework_sha256', 'test_build_sha256', 'input_sdk_contract_sha256'))):
        for field in fields:
            assert report[field]
            for relative, expected in report[field].items():
                assert sha(ROOT/relative) == expected, ('Stale build/host', relative)
                bindings[relative] = expected
    required = {p.relative_to(ROOT).as_posix() for pattern in (
        'shared-core/src/commonMain/**/*.kt', 'shared-core/src/iosMain/**/*.kt',
        'canvas-core/src/commonMain/**/*.kt', 'canvas-core/src/iosMain/**/*.kt',
        'ios-app/NoteCorePreview/*.swift') for p in ROOT.glob(pattern)}
    assert required <= set(build['source_and_build_input_sha256'])
    assert load(folder/'payload-before.json') == load(folder/'payload-after.json') == restored['installed_payload_sha256']
    before, after = load(folder/'files-before.json'), load(folder/'files-after.json')
    assert before and all(after[k] == digest for k, digest in before.items())
    assert set(after) - set(before) == {case['note_relative_path']}
    names = ('stage-01-two-strokes.ncp', 'stage-02-undo.ncp', 'stage-03-redo.ncp',
             'stage-04-two-pages.ncp', 'stage-05-after-zoom.ncp', 'stage-06-reopened.ncp')
    docs = [document(folder/name) for name in names]
    assert [(len(d[1]), len(d[2]), int(d[0][5])) for d in docs] == [
        (1, 2, 3), (1, 1, 4), (1, 2, 5), (2, 3, 8), (2, 3, 8), (2, 3, 8)]
    def stable(stroke):
        row, points = stroke
        return row[:3] + row[4:], points
    assert [stable(s) for s in docs[0][2]] == [stable(s) for s in docs[2][2]]
    assert stable(docs[1][2][0]) == stable(docs[0][2][0])
    assert int(docs[2][2][1][0][3]) > int(docs[0][2][1][0][3])
    root, pages, strokes = docs[-1]
    assert root[2] == case['title'] and [p[5] for p in pages] == ['BLANK', 'GRID']
    assert [sum(s[0][2] == p[2] for s in strokes) for p in pages] == [2, 1]
    point_counts = [len(s[1]) for s in strokes]
    assert all(count >= 2 for count in point_counts)
    # Native callback delivery is variable; validate dot/line geometry, do not assume a prior run's point count.
    positions = [{(p[1], p[2]) for p in points} for _, points in strokes]
    assert len(positions[0]) == 1 and len(positions[1]) > 1 and len(positions[2]) > 1
    assert [s[0][4:8] for s in strokes] == [
        ['MONOLINE', '1', '-14339513', '2.5'], ['MONOLINE', '1', '-14339513', '2.5'],
        ['MONOLINE', '1', '-13276994', '5.0']]
    assert len({sha(folder/name) for name in names[3:]}) == 1
    assert after[case['note_relative_path']] == sha(folder/names[-1])
    process = load(folder/'ordinary-process.json')
    assert str(process['pid']) == process['launchctl'].split()[0]
    assert 'UIKitApplication:'+case['bundle']+'[' in process['launchctl']
    observed = events(folder/'render.ndjson', process['pid'])
    observations = load(folder/'ui-observations.json')
    assert observations['method'].startswith('CUA Device Hub') and observations['normal_close_reopen']
    states = {row['label']: ' '.join(row['ax_fragments']) for row in observations['states']}
    for label, fragments in {
        'undo': ('1 笔', '1笔'), 'redo': ('2 笔', '2笔'),
        'normal_reopened_page_one': ('1/2', '已打开 · 3 笔', '2笔'),
        'normal_reopened_page_two': ('2/2', '1笔'), 'final_library': ('本机笔记', case['title']),
    }.items():
        assert all(fragment in states[label] for fragment in fragments)
    origins = load(folder/'image-origin.json')
    assert set(origins) == {'page_one', 'page_two'}
    for item in origins.values():
        path = ROOT/item['artifact']
        assert sha(path) == sha(Path(item['source']))
        raw = path.read_bytes()
        assert raw[:8] == b'\x89PNG\r\n\x1a\n' and struct.unpack('>II', raw[16:24]) == (1206, 2622)
    paths = [p for p in folder.iterdir() if p.is_file() and not p.name.startswith('historical-')]
    paths += [Path(__file__).resolve(), ROOT/'tools/verify_ios_preview_ui.py', ROOT/build['artifact']]
    paths += [ROOT/'reports'/name for name in ('ios-preview-build.json', 'ios-preview-restored.json', 'ios-host-tests.json')]
    bindings.update({p.relative_to(ROOT).as_posix(): sha(p) for p in paths})
    report = {'schema': 'ios-preview-ui-2', 'status': 'passed', 'source_and_artifact_sha256': bindings,
              'scope': 'Current ordinary UIKit Simulator selected UI observed through CUA: new named note, dark dot/line, undo/redo, blue line on grid page two, zoom/fit/page isolation, Home/foreground and normal library close/reopen. Retained file bytes, PID-specific provider source-release/owner logs and original PNGs corroborate this manual observation. Hashes and AX snippets are not a pixel oracle.',
              'artifact_sha256': build['sha256'], 'device': case['device'], 'ordinary_process': observed,
              'original_ncp_files_unchanged': len(before), 'current_ncp_files': len(after),
              'new_test_documents': 1, 'pages': 2, 'strokes_per_page': [2, 1], 'real_point_rows': sum(point_counts), 'point_counts': point_counts,
              'normal_close_reopen_verified': True, 'ordinary_ui_selected_flows_verified': True,
              'ordinary_provider_frames_observed': True,
              'excluded_attempts': observations['coordinate_attempts_excluded'],
              'old_package_ui_inherited': False, 'in_progress_static_pixel_handoff_verified': False,
              'exclusive_backend_every_frame_verified': False, 'controlled_restart_or_power_loss_verified': False,
              'physical_device_or_heap_verified': False, 'android_ordinary_ui_verified': False,
              'system_picker_or_multitouch_rotation_verified': False, 'c1_gate_complete': False}
    (ROOT/'reports/ios-preview-ui.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'ordinary_process': observed, 'original_files_unchanged': len(before)}))


if __name__ == '__main__':
    main()
