#!/usr/bin/env python3
"""Check retained current Files evidence; UI facts remain manual CUA observations."""
import hashlib
import json
from pathlib import Path
from verify_ios_preview_ui import document

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT / 'reports/apple-system-files-20261029'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    start = load(CASE/'start.json')
    ui = load(CASE/'ui-observations.json')
    build = load(ROOT/'reports/ios-preview-build.json')
    restored = load(ROOT/'reports/ios-preview-restored.json')
    assert start['artifact_sha256'] == build['sha256'] == restored['artifact_sha256']
    assert sha(ROOT/build['artifact']) == build['sha256']
    assert start['device'] == ui['device'] == restored['device']
    payload = load(CASE/'payload-before.json')
    assert payload == load(CASE/'payload-final.json') == restored['installed_payload_sha256']
    assert restored['no_xctest_plugin'] and not any('.xctest' in name for name in payload)
    before = load(CASE/'files-before.json')
    after = load(CASE/'files-after-import.json')
    assert len(before) == 14 and len(after) == 16
    assert all(after.get(name) == digest for name, digest in before.items())
    assert after == load(CASE/'files-after-import-cancel.json') == load(CASE/'files-final.json')
    assert set(after)-set(before) == {ui['source_file'], ui['imported_file']}
    assert after[ui['source_file']] == sha(CASE/'source-note.ncp')
    assert after[ui['imported_file']] == sha(CASE/'imported-note.ncp')
    assert (CASE/'source-note.ncp').read_bytes() == (CASE/'system-export.ncp').read_bytes()
    assert sha(CASE/'system-export.ncp') == load(CASE/'export-origin.json')['sha256']
    a, ap, ast = document(CASE/'source-note.ncp')
    b, bp, bst = document(CASE/'imported-note.ncp')
    assert a[2] == b[2] == 'C1-Files-20261029' and a[2:5] == b[2:5]
    assert len(ap) == len(bp) == len(ast) == len(bst) == 1
    aid = {a[1], ap[0][1], ap[0][2], ast[0][0][1]}
    bid = {b[1], bp[0][1], bp[0][2], bst[0][0][1]}
    assert len(aid) == len(bid) == 4 and aid.isdisjoint(bid)
    assert ap[0][3:6] == bp[0][3:6]
    assert ast[0][0][2] == ap[0][2] and bst[0][0][2] == bp[0][2]
    assert ast[0][0][4:] == bst[0][0][4:] and ast[0][1] == bst[0][1]
    assert len(ast[0][1]) == 4
    assert ui['method'].startswith('CUA Device Hub')
    assert ui['export_completed'] and ui['import_completed'] and ui['import_cancel_returned_to_library']
    assert ui['keyboard_capture_restored_off'] and ui['mac_locked_at_end']
    assert not ui['export_cancel_verified'] and not ui['post_cancel_reopen_verified']
    states = {row['label']: row for row in ui['states']}
    assert '备份已导出' in states['system_export']['ax_fragments']
    assert '已导入为新笔记 · 1 笔' in states['system_import']['ax_fragments']
    assert '本机笔记' in states['import_cancel']['ax_fragments']
    pid = str(ui['successful_roundtrip_pid'])
    for name in ('process-after-recovery.log', 'process-final.log'):
        rows = [row for row in (CASE/name).read_text(encoding='utf-8').splitlines() if 'UIKitApplication:dev.notecore.preview[' in row]
        assert len(rows) == 1 and rows[0].split()[0] == pid
    runtime = (CASE/'files-runtime-final.log').read_text(encoding='utf-8')
    assert 'DocumentManagerUICore.Service' in runtime and f'NoteCorePreview[{pid}:' in runtime
    bindings = {}
    for name, digest in build['source_and_build_input_sha256'].items():
        assert sha(ROOT/name) == digest, name
        bindings[name] = digest
    paths = list(CASE.iterdir()) + [Path(__file__).resolve(), ROOT/build['artifact'],
        ROOT/'reports/ios-preview-build.json', ROOT/'reports/ios-preview-restored.json']
    bindings.update({p.relative_to(ROOT).as_posix(): sha(p) for p in paths if p.is_file()})
    result = {
        'schema': 'apple-system-files-1', 'status': 'passed_with_deferred_checks',
        'scope': 'Current ordinary iOS27.0 Simulator package: manual CUA system Files local export/import and import-sheet dismissal, corroborated by retained actual file bytes and ordinary PID. UI observations and file hashes are not an automated pixel oracle.',
        'source_commit': start['source_commit'], 'artifact_sha256': build['sha256'],
        'device': start['device'], 'ordinary_pid': int(pid),
        'export_byte_identical': True, 'imported_document_page_layer_object_ids_fresh': True,
        'normalized_title_paper_geometry_style_transform_and_points_equal': True,
        'pages': 1, 'strokes': 1, 'points': 4,
        'original_files_unchanged': 14, 'new_synthetic_notes_retained': 2,
        'import_cancel_files_unchanged': True, 'ordinary_payload_unchanged_without_xctest': True,
        'initial_picker_attempt': 'Interrupted after initial white screen/no AX nodes; exact root cause unestablished. App Switcher closed old PID4629; new PID7586 completed selected flows.',
        'adoption': 'Adopt current ordinary local Files success and import dismissal evidence only; no business code change required by these observed flows.',
        'limitations': ['Mac locked before post-cancel reopening; export cancellation remains unverified.',
            'No permission revocation, in-flight write/process failure, cloud provider, rotation, multitouch, physical stylus/heap/performance or full C1 acceptance.',
            'Initial white screen and missing picker AX nodes are retained observations, not a diagnosed product defect or an inferred pass.'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'whole_requirement_accepted': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items()))}
    (ROOT/'reports/apple-system-files.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Apple Files retained evidence matched; original14 preserved, 2 synthetic notes; remaining GUI checks deferred')


if __name__ == '__main__':
    main()
