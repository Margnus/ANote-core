#!/usr/bin/env python3
"""Dedicated emulator UI paging checks; preserve all existing documents."""
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
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('Dedicated emulator required')

    def adb(*command):
        result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, text=True, timeout=45)
        if result.returncode:
            raise RuntimeError(result.stderr)
        return result.stdout

    def dump():
        adb('shell', 'uiautomator', 'dump', '/sdcard/NoteCorePagedSmoke.xml')
        return ET.fromstring(adb('exec-out', 'cat', '/sdcard/NoteCorePagedSmoke.xml'))

    def wait(predicate, label):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            value = predicate(dump())
            if value is not None and value is not False:
                return value
        raise AssertionError(label)

    def find(tree, **attrs):
        return next((n for n in tree.iter('node') if all(n.attrib.get(k) == v for k, v in attrs.items())), None)

    def text(value):
        return wait(lambda tree: find(tree, text=value), value)

    def bounds(node):
        return list(map(int, re.findall(r'\d+', node.attrib['bounds'])))

    def click(node):
        x1, y1, x2, y2 = bounds(node)
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

    def count(value):
        return wait(lambda tree: find(tree, **{'content-desc': f'手写画布，{value}笔'}), f'{value} strokes')

    def page(value):
        return wait(lambda tree: find(tree, text=value, **{'content-desc': '当前页码'}), f'page {value}')

    def saved(value):
        text(f'已保存到本机 · {value} 笔')

    def draw(value):
        x1, y1, x2, y2 = bounds(count(value))
        adb('shell', 'input', 'swipe', str((x1+x2)//2-50), str((y1+y2)//2-50),
            str((x1+x2)//2+50), str((y1+y2)//2+50), '350')

    def launch():
        adb('shell', 'am', 'force-stop', PACKAGE)
        assert 'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', f'{PACKAGE}/dev.notecore.app.MainActivity')

    def read(filename):
        return adb('exec-out', 'run-as', PACKAGE, 'cat', f'files/preview-notes/{filename}')

    def files():
        return set(adb('shell', 'run-as', PACKAGE, 'ls', 'files/preview-notes').splitlines())

    launch(); existing = files()
    click(text('新建笔记')); saved(0); page('1/1')
    created = [name for name in files()-existing if name.endswith('.ncp')]
    assert len(created) == 1
    filename = created[0]
    draw(0); count(1); saved(1)
    first = read(filename)
    click(text('加页')); page('2/2'); count(0); saved(1)
    click(text('深蓝')); draw(0); count(1); saved(2)
    two = read(filename)
    pages = [row.split('\t') for row in two.splitlines() if row.startswith('G\t')]
    assert len(pages) == 2 and pages[0][1] != pages[1][1]
    click(text('上一页')); page('1/2'); count(1)
    assert read(filename) == two  # Selection is presentation state, not a save/edit.
    click(text('下一页')); page('2/2'); count(1)
    click(text('复制页')); page('3/3'); count(1); saved(3)
    copied = read(filename)
    objects = [row.split('\t') for row in copied.splitlines() if row.startswith('S\t')]
    assert len({row[1] for row in objects}) == 3
    assert objects[1][6:] == objects[2][6:]
    click(text('撤销')); page('2/2'); saved(2)
    click(text('重做')); page('2/3'); saved(3)
    click(text('下一页')); page('3/3'); count(1)
    click(text('删页')); click(text('删除')); page('2/2'); saved(2)
    click(text('撤销')); page('2/3'); saved(3)
    # Move tools can be offscreen on phones; scroll the page toolbar only.
    label = page('2/3'); _, y1, _, y2 = bounds(label)
    adb('shell', 'input', 'swipe', '900', str((y1+y2)//2), '150', str((y1+y2)//2), '350')
    click(text('前移')); saved(3)
    moved = read(filename)
    moved_pages = [row.split('\t')[1] for row in moved.splitlines() if row.startswith('G\t')]
    assert moved_pages[:2] == [pages[1][1], pages[0][1]]
    click(text('后移')); saved(3)
    adb('shell', 'input', 'swipe', '150', str((y1+y2)//2), '1000', str((y1+y2)//2), '350')
    page('2/3')
    final = read(filename)
    assert [row.split('\t')[1] for row in final.splitlines() if row.startswith('G\t')][:2] == [pages[0][1], pages[1][1]]
    # Rename to a unique title so process restart selects precisely this synthetic note.
    click(text('未命名笔记'))
    field = wait(lambda tree: find(tree, **{'class': 'android.widget.EditText'}), 'rename field')
    click(field); adb('shell', 'input', 'keyevent', '123')
    adb('shell', 'input', 'keyevent', *(['67'] * len(field.attrib.get('text', ''))))
    title = 'NoteCorePaged_' + time.strftime('%Y%m%d_%H%M%S')
    adb('shell', 'input', 'text', title); click(text('完成')); saved(3)
    final = read(filename)
    launch()
    entry = wait(lambda tree: next((n for n in tree.iter('node') if n.attrib.get('text', '').startswith(title+'\n')), None), title)
    click(entry); page('1/3'); count(1)
    click(text('下一页')); page('2/3'); count(1)
    click(text('下一页')); page('3/3'); count(1)
    assert read(filename) == final
    screenshot = ROOT/'reports/android-paged-preview.png'
    image = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'screencap', '-p'], capture_output=True, check=True).stdout
    screenshot.write_bytes(image)
    click(text('导出'))
    field = wait(lambda tree: find(tree, **{'class': 'android.widget.EditText', 'resource-id': 'android:id/title'}), 'export picker')
    export_name = field.attrib['text']
    click(wait(lambda tree: find(tree, **{'resource-id': 'android:id/button1'}), 'export save'))
    text('导出')
    assert adb('exec-out', 'cat', '/sdcard/Download/'+export_name) == final
    click(text('返回'))
    before_import = files()
    click(text('导入备份')); click(text(export_name)); text('已导入为新笔记 · 3 笔')
    page('1/3'); count(1)
    added = [name for name in files()-before_import if name.endswith('.ncp')]
    assert len(added) == 1
    imported = read(added[0])

    def content(value):
        rows = [line.split('\t') for line in value.splitlines()]
        descriptors = [r for r in rows if r[0] == 'G']
        layers = {r[2]: index for index, r in enumerate(descriptors)}
        return ([r[3:6] for r in descriptors],
                [[layers[r[2]], *r[4:]] if r[0] == 'S' else r for r in rows if r[0] in ('S', 'P')])

    assert content(imported) == content(final)
    for kind, index in [('G', 1), ('G', 2), ('S', 1)]:
        source_ids = {row.split('\t')[index] for row in final.splitlines() if row.startswith(kind+'\t')}
        copied_ids = {row.split('\t')[index] for row in imported.splitlines() if row.startswith(kind+'\t')}
        assert source_ids.isdisjoint(copied_ids)
    assert read(filename) == final
    click(text('下一页')); page('2/3'); count(1)
    click(text('下一页')); page('3/3'); count(1)
    click(text('返回'))
    report = {'status': 'passed', 'apk_sha256': hashlib.sha256((ROOT/'dist/NoteCore-0.1.0-preview.apk').read_bytes()).hexdigest(),
              'serial': args.serial, 'checks': ['page_add', 'per_page_ink', 'selection_no_content_write', 'copy_fresh_ids',
                  'page_copy_undo_redo', 'page_delete_undo', 'move_forward_backward_stable_ids', 'all_pages_force_stop_reopen',
                  'whole_document_system_export_from_last_page', 'multi_page_import_fresh_ids_and_original_preserved'],
              'document_sha256': hashlib.sha256(final.encode()).hexdigest(), 'pages': 3, 'strokes': 3,
              'screenshot': screenshot.relative_to(ROOT).as_posix(), 'screenshot_sha256': hashlib.sha256(image).hexdigest(),
              'event_source': 'Android input taps/swipes through real native UI; dedicated emulator',
              'device_performance_verified': False, 'ios_paged_ui_verified': False}
    (ROOT/'reports/android-paged-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
