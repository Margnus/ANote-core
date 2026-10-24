#!/usr/bin/env python3
"""File/PID evidence only. All ordinary UI interaction is performed separately through CUA."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
from verify_ios_preview_ui import document

ROOT = Path(__file__).resolve().parents[1]
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
BUNDLE = 'dev.notecore.preview'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sim(*args):
    return subprocess.check_output(['xcrun', 'simctl', *args], text=True, timeout=30).strip()


def process():
    rows = [row for row in sim('spawn', DEVICE, 'launchctl', 'list').splitlines() if 'UIKitApplication:'+BUNDLE+'[' in row]
    assert len(rows) == 1 and rows[0].split()[0].isdigit(), rows
    return {'pid': int(rows[0].split()[0]), 'launchctl': rows[0], 'label': rows[0].split()[-1]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', type=Path, required=True)
    parser.add_argument('--action', choices=['stage', 'seed', 'kill', 'final'], required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--recovery', action='store_true')
    args = parser.parse_args()
    folder = args.case.resolve(); folder.relative_to(ROOT/'build')
    case = load(folder/'case.json'); assert case['device'] == DEVICE and case['bundle'] == BUNDLE
    data = Path(sim('get_app_container', DEVICE, BUNDLE, 'data'))
    app = Path(sim('get_app_container', DEVICE, BUNDLE, 'app'))
    notes = data/'Documents/PreviewNotes'
    assert '/' not in args.label and args.label.replace('-', '').replace('_', '').isalnum()
    if args.action == 'seed':
        assert 'recovery_relative_path' not in case
        source = folder/'stage-06-reopened.ncp'; root, pages, strokes = document(source)
        assert root[2] == case['title'] and len(pages) == 2 and len(strokes) == case['expected_total_strokes']
        identity = str(uuid.uuid4()).upper()
        lines = source.read_text(encoding='utf-8').splitlines(keepends=True)
        old = lines[1].rstrip('\n').split('\t'); old[1] = identity; old[2] = case['recovery_title']
        lines[1] = '\t'.join(old)+'\n'; base = ''.join(lines)
        page = list(pages[1]); page[6] = '0'
        operation = 'recovery-ui-op-1'; object_id = 'recovery-ui-ink-2'
        assert all(stroke[0][1] != object_id for stroke in strokes)
        ink = ('NCPREVIEW\t2\nD\t'+identity+'\t\t1\t1\t0\n'+'\t'.join(page)+'\n'+
               'S\t'+object_id+'\t'+page[2]+'\t1\tMONOLINE\t1\t-13276994\t5.0\t3\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\n'+
               'P\t64.0\t96.0\t0.25\t100\nP\t96.0\t144.0\t0.5\t200\nP\t128.0\t192.0\t0.75\t300\nEND\n')
        def characters(text):
            return len(text.encode('utf-16-le'))//2
        encoded = ('NCRECOVERY\t1\n'+lines[1]+str(characters(base))+'\t'+str(characters(ink))+'\t'+
                   ''.join(f'{ord(c):04x}' for c in operation)+'\n'+base+ink+'RECOVERY-END\n')
        destination = notes/(identity+'.ncp'); assert not destination.exists()
        # Explicit synthetic input fixture, never an accepted original-worker write or interchange export.
        with destination.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(encoded)
        (folder/'recovery-seed.ncp').write_bytes(destination.read_bytes())
        case.update(recovery_relative_path=destination.relative_to(data).as_posix(), recovery_operation=operation,
                    recovery_object=object_id, recovery_seed_source_sha256=sha(source),
                    recovery_seed_is_synthetic=True, recovery_seed_not_original_worker_write=True)
        write(folder/'case.json', case)
        print(json.dumps({'seeded_unique_recovery_file':case['recovery_relative_path'], 'base_revision':int(root[5]), 'pending_points':3}))
        return
    title = case['recovery_title'] if args.recovery else case['title']
    if args.action == 'stage':
        found = [p for p in notes.glob('*.ncp') if p.read_text(encoding='utf-8').splitlines()[1].split('\t')[2] == title]
        assert len(found) == 1, found
        content = found[0].read_bytes(); target = folder/(args.label+'.ncp'); target.write_bytes(content)
        record = {'pid':process()['pid'], 'relative_path':found[0].relative_to(data).as_posix(), 'sha256':sha(target),
                  'format':content.splitlines()[0].decode('utf-8')}
        if content.startswith(b'NCPREVIEW\t2\n'):
            root, pages, strokes = document(target)
            record.update(revision=int(root[5]), pages=len(pages), strokes=len(strokes), points=[len(s[1]) for s in strokes])
        write(folder/(args.label+'.json'), record)
        if not args.recovery:
            case['note_relative_path'] = record['relative_path']; write(folder/'case.json', case)
        print(json.dumps(record)); return
    if args.action == 'kill':
        original = process()
        before = {p.name:sha(p) for p in notes.glob('*.ncp')}
        target = 'user/'+str(os.getuid())+'/'+original['label']
        sim('spawn', DEVICE, 'launchctl', 'kill', 'SIGKILL', target)
        # Poll the exact old PID; do not interpret a timeout as successful termination.
        for _ in range(100):
            rows = sim('spawn', DEVICE, 'launchctl', 'list').splitlines()
            if not any(row.split()[0] == str(original['pid']) for row in rows if BUNDLE in row):
                break
            time.sleep(.02)
        else:
            raise AssertionError('Original ordinary process is still registered')
        after = {p.name:sha(p) for p in notes.glob('*.ncp')}; assert after == before
        write(folder/(args.label+'.json'), {'old_process':original, 'service_target':target, 'signal':'SIGKILL', 'old_pid_gone':True,
            'note_files_unchanged_during_kill':True, 'files':after})
        print(json.dumps({'terminated_pid':original['pid'], 'signal':'SIGKILL', 'files_unchanged':True})); return
    payload = {p.relative_to(app).as_posix():sha(p) for p in app.rglob('*') if p.is_file()}
    files = {p.relative_to(data).as_posix():sha(p) for p in notes.glob('*.ncp')}
    before = load(folder/'files-before.json')
    assert all(files.get(name) == digest for name, digest in before.items())
    assert set(files)-set(before) == {case['note_relative_path'], case['recovery_relative_path']}
    assert payload == load(folder/'payload-before.json') and not list(app.rglob('*.xctest'))
    write(folder/'payload-after.json', payload); write(folder/'files-after.json', files)
    write(folder/'ordinary-process-final.json', process())
    print(json.dumps({'original_files_unchanged':len(before), 'new_synthetic_test_documents':2, 'payload_matches':True}))


if __name__ == '__main__':
    main()
