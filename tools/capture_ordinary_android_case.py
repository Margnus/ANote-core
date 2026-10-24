#!/usr/bin/env python3
"""Collect files and ordinary PID logs only; UI actions must be performed separately with CUA."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import uuid
from verify_ios_preview_ui import document

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'dev.notecore.preview'
CASE = ROOT / 'build/android-ordinary-ui'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--seed-recovery', action='store_true')
    parser.add_argument('--force-stop-restart', action='store_true')
    args = parser.parse_args()
    assert args.label.replace('-', '').isalnum()
    case = json.loads((CASE/'case.json').read_text(encoding='utf-8'))
    assert args.serial == case['current_serial']

    def adb(*command):
        return subprocess.check_output(['/Users/lixiaofan/Library/Android/sdk/platform-tools/adb',
            '-s', args.serial, *command], timeout=30)

    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name').decode().strip() == 'NoteCorePreview'
    before = json.loads((CASE/'files-before.json').read_text(encoding='utf-8'))
    if args.seed_recovery:
        assert 'recovery_file' not in case
        sources = list(CASE.glob('stage-07-reopened-closed-*.ncp'))
        assert len(sources) == 1
        source = sources[0]
        root, pages, strokes = document(source)
        assert root[2] == 'DEV040Android20261024' and len(pages)==2 and len(strokes)==3
        identity = str(uuid.uuid4())
        lines = source.read_text(encoding='utf-8').splitlines(keepends=True)
        header = lines[1].rstrip('\n').split('\t'); header[1]=identity; header[2]='DEV040AndroidRecovery20261024'
        lines[1]='\t'.join(header)+'\n'; base=''.join(lines)
        page=list(pages[1]); page[6]='0'
        operation='android-recovery-ui-op-1'; object_id='android-recovery-ui-ink-2'
        ink=('NCPREVIEW\t2\nD\t'+identity+'\t\t1\t1\t0\n'+'\t'.join(page)+'\n'+
             'S\t'+object_id+'\t'+page[2]+'\t1\tMONOLINE\t1\t-13276994\t5.0\t3\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\n'+
             'P\t64.0\t96.0\t0.25\t100\nP\t96.0\t144.0\t0.5\t200\nP\t128.0\t192.0\t0.75\t300\nEND\n')
        count=lambda s: len(s.encode('utf-16-le'))//2
        content=('NCRECOVERY\t1\n'+lines[1]+str(count(base))+'\t'+str(count(ink))+'\t'+
                 ''.join(f'{ord(c):04x}' for c in operation)+'\n'+base+ink+'RECOVERY-END\n').encode()
        name=identity+'.ncp'
        # Exclusive, unique fixture only; never overwrite a canonical document or claim a worker produced this seed.
        subprocess.run(['/Users/lixiaofan/Library/Android/sdk/platform-tools/adb','-s',args.serial,
            'exec-in','run-as',BUNDLE,'sh','-c','set -C; cat > files/preview-notes/'+name],
            input=content,check=True,timeout=30)
        assert adb('exec-out','run-as',BUNDLE,'cat','files/preview-notes/'+name)==content
        (CASE/'recovery-seed.ncp').write_bytes(content)
        case.update(recovery_file=name,recovery_operation=operation,recovery_object=object_id,
            recovery_seed_is_synthetic=True,recovery_seed_not_original_worker_write=True,
            recovery_base_sha256=hashlib.sha256(source.read_bytes()).hexdigest())
        (CASE/'case.json').write_text(json.dumps(case,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'synthetic_seed':name,'base_strokes':3,'pending_points':3})); return
    files = {}
    added = []
    for name in adb('shell', 'run-as', BUNDLE, 'ls', 'files/preview-notes').decode().splitlines():
        if not name.endswith('.ncp'):
            continue
        assert name.replace('-', '').replace('.ncp', '').isalnum()
        content = adb('exec-out', 'run-as', BUNDLE, 'cat', 'files/preview-notes/'+name)
        files[name] = hashlib.sha256(content).hexdigest()
        if name not in before:
            target = CASE/(args.label+'-'+name)
            target.write_bytes(content)
            record = {'file': name, 'sha256': files[name], 'format':content.splitlines()[0].decode()}
            if content.startswith(b'NCPREVIEW\t2\n'):
                root, pages, strokes = document(target)
                record.update(title=root[2], revision=int(root[5]), pages=len(pages), strokes=len(strokes),
                    points=[len(s[1]) for s in strokes], page_strokes=[sum(s[0][2]==p[2] for s in strokes) for p in pages])
            added.append(record)
    assert all(files.get(name)==sha for name,sha in before.items()), 'Original files changed; preserve and investigate'
    pid = int(adb('shell', 'pidof', BUNDLE).strip())
    logs = adb('logcat', '-d', '-v', 'epoch', '-s', 'NoteCoreRender:D')
    (CASE/(args.label+'-render.log')).write_bytes(logs)
    result = {'serial':args.serial, 'pid':pid, 'original_files_unchanged':len(before), 'files':files, 'new_documents':added}
    if args.force_stop_restart:
        adb('shell','am','force-stop',BUNDLE)
        assert not subprocess.run(['/Users/lixiaofan/Library/Android/sdk/platform-tools/adb','-s',args.serial,
            'shell','pidof',BUNDLE],capture_output=True,timeout=30).stdout.strip(), 'Old process still present'
        after_names=adb('shell','run-as',BUNDLE,'ls','files/preview-notes').decode().splitlines()
        after={name:hashlib.sha256(adb('exec-out','run-as',BUNDLE,'cat','files/preview-notes/'+name)).hexdigest()
               for name in after_names if name.endswith('.ncp')}
        assert after==files
        launch=adb('shell','am','start','-W','-n',BUNDLE+'/dev.notecore.app.MainActivity').decode()
        assert 'Status: ok' in launch
        new_pid=int(adb('shell','pidof',BUNDLE).strip()); assert new_pid!=pid
        result.update(force_stop=True,old_pid_gone=True,files_unchanged_during_stop=True,new_pid=new_pid,
            launch=launch,termination_not_native_receipt=True)
    (CASE/(args.label+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='files'},ensure_ascii=False))


if __name__ == '__main__':
    main()
