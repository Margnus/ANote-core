#!/usr/bin/env python3
"""Exercise an explicitly selected emulator through real UI; never clear application data."""
import argparse
import hashlib
import json
import re
import subprocess
import time
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.notecore.preview'

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--adb', default='adb')
    args = ap.parse_args()
    if not args.serial.startswith('emulator-'): raise SystemExit('This smoke script requires a dedicated emulator; it never clears data.')
    def adb(*command, binary=False, allow_failure=False):
        result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, timeout=45)
        if result.returncode and not allow_failure: raise RuntimeError(result.stderr.decode(errors='replace'))
        return result.stdout if binary else result.stdout.decode(errors='replace')
    def dump():
        adb('shell', 'uiautomator', 'dump', '/sdcard/NoteCoreSmoke.xml')
        return ET.fromstring(adb('exec-out', 'cat', '/sdcard/NoteCoreSmoke.xml'))
    def wait(predicate, message, timeout=45):
        start = time.monotonic()
        while time.monotonic() - start < timeout:
            tree = dump(); value = predicate(tree)
            if value is not None and value is not False: return value
        raise AssertionError(message)
    def find(tree, **attrs):
        return next((n for n in tree.iter('node') if all(n.attrib.get(k) == v for k, v in attrs.items())), None)
    def click(node):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    def text(value): return wait(lambda tree: find(tree, text=value), f'Cannot find {value}')
    def count(expected):
        return wait(lambda tree: find(tree, **{'content-desc': f'手写画布，{expected}笔'}), f'Ink count not {expected}')
    def saved(expected):
        return wait(lambda tree: find(tree, text=f'已保存到本机 · {expected} 笔'), 'Save acknowledgement missing')
    def draw(canvas, offset=0):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', canvas.attrib['bounds']))
        width = x2-x1; height = y2-y1
        adb('shell', 'input', 'swipe', str(int(x1+width*.28)), str(int(y1+height*.28)+offset),
            str(int(x1+width*.68)), str(int(y1+height*.44)+offset), '450')
    apk = ROOT/'dist/NoteCore-0.1.0-preview.apk'
    print(adb('install', '-r', str(apk)).strip(), flush=True)
    adb('shell', 'am', 'force-stop', PACKAGE)
    launch = adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/dev.notecore.app.MainActivity')
    assert 'Status: ok' in launch
    click(text('新建笔记')); saved(0)
    click(text('未命名笔记'))
    field = wait(lambda tree: find(tree, **{'class': 'android.widget.EditText'}), 'Rename field absent')
    click(field); adb('shell', 'input', 'keyevent', '123')
    if field.attrib.get('text'): adb('shell', 'input', 'keyevent', *(['67'] * len(field.attrib['text'])))
    title = 'NoteCoreSmoke_' + time.strftime('%Y%m%d_%H%M%S')
    adb('shell', 'input', 'text', title); click(text('完成')); saved(0)
    print('create and rename passed', flush=True)
    canvas = count(0); draw(canvas); count(1); saved(1)
    click(text('深蓝')); draw(count(1), 100); count(2); saved(2)
    click(text('撤销')); count(1); saved(1)
    click(text('重做')); count(2); saved(2)
    switch = wait(lambda tree: find(tree, **{'class': 'android.widget.Switch'}), 'Finger control absent')
    click(switch); draw(count(2)); count(2); click(wait(lambda tree: find(tree, **{'class': 'android.widget.Switch'}), 'Finger control absent'))
    files = adb('shell', 'run-as', PACKAGE, 'ls', 'files/preview-notes').splitlines()
    contents = []
    for filename in files:
        if re.fullmatch(r'[a-zA-Z0-9-]+\.ncp', filename):
            value = adb('exec-out', 'run-as', PACKAGE, 'cat', f'files/preview-notes/{filename}')
            rows = value.splitlines()
            if len(rows) > 1 and rows[1].split('\t')[2] == title: contents.append((filename, value))
    assert len(contents) == 1
    filename, before = contents[0]
    assert before.startswith('NCPREVIEW\t2\n') and before.endswith('END\n')
    stroke_rows = [r.split('\t') for r in before.splitlines() if r.startswith('S\t')]
    assert len(stroke_rows) == 2 and stroke_rows[0][6] != stroke_rows[1][6]
    assert sum(int(r[8]) for r in stroke_rows) == len([r for r in before.splitlines() if r.startswith('P\t')])
    print('draw, colors, undo/redo, finger policy and saved point counts passed', flush=True)
    adb('shell', 'am', 'force-stop', PACKAGE)
    assert 'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/dev.notecore.app.MainActivity')
    entry = wait(lambda tree: next((n for n in tree.iter('node') if n.attrib.get('text', '').startswith(title+'\n')), None), 'Saved note missing after process restart')
    click(entry); count(2)
    after = adb('exec-out', 'run-as', PACKAGE, 'cat', f'files/preview-notes/{filename}')
    assert before == after
    adb('shell', 'input', 'keyevent', '4')
    click(wait(lambda tree: next((n for n in tree.iter('node') if n.attrib.get('text', '').startswith(title+'\n')), None), 'Back did not return to library'))
    count(2)
    output = ROOT/'reports/android-preview.png'
    click(text('放大')); count(2)
    click(text('缩小')); count(2)
    click(text('适合页面')); count(2)
    output.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    # Real system document picker, not a direct invocation of storage implementation.
    click(text('导出'))
    field = wait(lambda tree: find(tree, **{'class': 'android.widget.EditText', 'resource-id': 'android:id/title'}), 'Export picker absent')
    export_name = field.attrib['text']
    assert re.fullmatch(r'NoteCore-[a-zA-Z0-9-]+\.ncp', export_name)
    click(wait(lambda tree: find(tree, **{'resource-id': 'android:id/button1'}), 'Picker SAVE absent'))
    text('导出')
    exported = adb('exec-out', 'cat', '/sdcard/Download/' + export_name)
    assert exported == before
    click(text('返回'))
    click(text('导入备份')); wait(lambda tree: find(tree, **{'package': 'com.google.android.documentsui'}), 'Import picker absent')
    adb('shell', 'input', 'keyevent', '4'); text('导入备份')
    original_files = set(adb('shell', 'run-as', PACKAGE, 'ls', 'files/preview-notes').splitlines())
    imported_ids = []
    for _ in range(2):
        click(text('导入备份'))
        click(text(export_name))
        count(2); text('已导入为新笔记 · 2 笔')
        files_now = set(adb('shell', 'run-as', PACKAGE, 'ls', 'files/preview-notes').splitlines())
        new_files = [f for f in files_now - original_files if f.endswith('.ncp')]
        assert len(new_files) == 1
        imported = adb('exec-out', 'run-as', PACKAGE, 'cat', 'files/preview-notes/' + new_files[0])
        def content(value):
            rows = [line.split('\t') for line in value.splitlines()]
            pages = [r for r in rows if r[0] == 'G']
            layers = {r[2]: index for index, r in enumerate(pages)}
            return ([r[3:6] for r in pages],
                    [[layers[r[2]], *r[4:]] if r[0] == 'S' else r for r in rows if r[0] in ('S', 'P')])
        assert content(imported) == content(before)
        for kind, index in [('G', 1), ('G', 2), ('S', 1)]:
            old_ids = {r.split('\t')[index] for r in before.splitlines() if r.startswith(kind+'\t')}
            new_ids = {r.split('\t')[index] for r in imported.splitlines() if r.startswith(kind+'\t')}
            assert old_ids.isdisjoint(new_ids)
        meta = imported.splitlines()[1].split('\t')
        assert meta[1] != filename[:-4] and meta[2] == title
        imported_ids.append(meta[1]); original_files = files_now
        click(text('返回'))
    assert len(set(imported_ids)) == 2
    corrupt_name = 'NoteCoreCorrupt-' + time.strftime('%Y%m%d%H%M%S') + '.ncp'
    with tempfile.TemporaryDirectory(prefix='notecore-backup-smoke-') as tmp:
        corrupt = Path(tmp)/corrupt_name; corrupt.write_text('NCPREVIEW\t9\ninvalid\n')
        adb('push', str(corrupt), '/sdcard/Download/' + corrupt_name)
    click(text('导入备份')); click(text(corrupt_name))
    wait(lambda tree: next((n for n in tree.iter('node') if n.attrib.get('text', '').startswith('导入失败：')), None), 'Corrupt backup was not rejected')
    assert set(adb('shell', 'run-as', PACKAGE, 'ls', 'files/preview-notes').splitlines()) == original_files
    assert adb('exec-out', 'run-as', PACKAGE, 'cat', f'files/preview-notes/{filename}') == before
    print('system export exact bytes, picker cancellation, repeated copy import and corrupt rejection passed', flush=True)
    report = {'status': 'passed', 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'device': {'serial': args.serial, 'api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
                         'abi': adb('shell', 'getprop', 'ro.product.cpu.abi').strip(), 'kind': 'emulator'},
              'checks': ['install', 'cold_launch', 'create', 'rename', 'draw_real_touch_events', 'color_capture',
                         'undo', 'redo', 'finger_toggle', 'save_acknowledgement', 'saved_point_count',
                         'force_stop_and_reopen', 'exact_saved_content_after_reopen', 'back_to_library_and_reopen',
                         'system_picker_export_exact_bytes', 'import_picker_cancel', 'import_as_new_document',
                         'repeated_import_unique_identity', 'corrupt_backup_rejected', 'original_preserved_after_import', 'viewport_toolbar'],
              'imported_document_ids': imported_ids,
              'saved_strokes': 2, 'saved_real_points': sum(int(r[8]) for r in stroke_rows),
              'document_sha256': hashlib.sha256(before.encode()).hexdigest(), 'screenshot': str(output.relative_to(ROOT)),
              'real_stylus_verified': False, 'device_performance_verified': False,
              'power_loss_recovery_verified': False}
    (ROOT/'reports/android-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)

if __name__ == '__main__': main()
