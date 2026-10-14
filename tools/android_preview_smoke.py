#!/usr/bin/env python3
"""Exercise an explicitly selected emulator through real UI; never clear application data."""
import argparse
import hashlib
import json
import re
import subprocess
import time
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
    assert before.startswith('NCPREVIEW\t1\n') and before.endswith('END\n')
    stroke_rows = [r.split('\t') for r in before.splitlines() if r.startswith('S\t')]
    assert len(stroke_rows) == 2 and stroke_rows[0][2] != stroke_rows[1][2]
    assert sum(int(r[4]) for r in stroke_rows) == len([r for r in before.splitlines() if r.startswith('P\t')])
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
    output.write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    report = {'status': 'passed', 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'device': {'serial': args.serial, 'api': adb('shell', 'getprop', 'ro.build.version.sdk').strip(),
                         'abi': adb('shell', 'getprop', 'ro.product.cpu.abi').strip(), 'kind': 'emulator'},
              'checks': ['install', 'cold_launch', 'create', 'rename', 'draw_real_touch_events', 'color_capture',
                         'undo', 'redo', 'finger_toggle', 'save_acknowledgement', 'saved_point_count',
                         'force_stop_and_reopen', 'exact_saved_content_after_reopen', 'back_to_library_and_reopen'],
              'saved_strokes': 2, 'saved_real_points': sum(int(r[4]) for r in stroke_rows),
              'document_sha256': hashlib.sha256(before.encode()).hexdigest(), 'screenshot': str(output.relative_to(ROOT)),
              'real_stylus_verified': False, 'device_performance_verified': False,
              'power_loss_recovery_verified': False}
    (ROOT/'reports/android-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)

if __name__ == '__main__': main()
