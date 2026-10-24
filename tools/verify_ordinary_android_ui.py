#!/usr/bin/env python3
"""Verify retained ordinary CUA/files/PID evidence; never infer UI actions from host tests."""
import hashlib
import json
import re
from pathlib import Path
from verify_ios_preview_ui import document

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT/'build/android-ordinary-ui'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(folder=CASE):
    case=load(folder/'case.json'); installed=load(folder/'installed-final.json')
    build=load(ROOT/'reports/preview-build.json')
    assert build['status']=='passed' and installed['artifact_sha256']==build['android']['sha256']==case['artifact_sha256']
    assert sha(ROOT/build['android']['artifact'])==installed['artifact_sha256']
    assert installed['api']==36 and installed['avd']=='NoteCorePreview' and installed['apk_matches_before_and_after']
    bindings=dict(build['source_and_build_input_sha256'])
    for relative,digest in bindings.items():
        assert sha(ROOT/relative)==digest, ('Stale build input',relative)
    before=load(folder/'files-before.json'); assert len(before)==64
    for name,digest in before.items():
        assert sha(folder/'originals'/name)==digest
    stages=sorted(folder.glob('stage-*.json')); assert len(stages)==13
    ordinary='efd7631e-a64b-40f9-a8ed-ae8ada14ea48.ncp'
    recovery=case['recovery_file']; parsed=[]
    for path in stages:
        stage=load(path); label=path.stem
        assert stage['original_files_unchanged']==64
        assert all(stage['files'].get(name)==digest for name,digest in before.items())
        assert set(stage['files'])-set(before) in ({ordinary},{ordinary,recovery})
        for doc in stage['new_documents']:
            assert sha(folder/(label+'-'+doc['file']))==doc['sha256']==stage['files'][doc['file']]
        parsed.append((stage,document(folder/(label+'-'+ordinary))))
    assert [(len(p),len(s),int(d[5])) for _,(d,p,s) in parsed[:4]]==[(1,2,3),(1,1,4),(1,2,5),(2,3,8)]
    def stable(s):
        row,points=s
        return row[:3]+row[4:],points
    original=parsed[0][1][2]; redone=parsed[2][1][2]
    assert list(map(stable,original))==list(map(stable,redone))
    assert int(redone[1][0][3])>int(original[1][0][3])
    assert stable(parsed[1][1][2][0])==stable(original[0])
    assert len({(p[1],p[2]) for p in original[0][1]})==1
    assert len({(p[1],p[2]) for p in original[1][1]})>1
    final_root,final_pages,final_strokes=parsed[-1][1]
    assert final_root[2]=='DEV040Android20261024'
    assert [p[5] for p in final_pages]==['BLANK','GRID']
    assert [s[0][4:8] for s in final_strokes]==[
        ['MONOLINE','1','-14339513','2.5'],['MONOLINE','1','-14339513','2.5'],['MONOLINE','1','-13276994','5.0']]
    assert len({s['files'][ordinary] for s,_ in parsed[3:]})==1
    assert case['recovery_seed_is_synthetic'] and case['recovery_seed_not_original_worker_write']
    seed=folder/'recovery-seed.ncp'
    for label in ('stage-08-pending-stop','stage-09-pending-reopened'):
        assert sha(folder/(label+'-'+recovery))==sha(seed)
    # Independent framing/point comparison of the pending command against the committed result.
    magic,metadata,frame,body=seed.read_text(encoding='utf-8').split('\n',3)
    assert magic=='NCRECOVERY\t1'
    base_units,ink_units,operation=frame.split('\t'); raw=body.encode('utf-16-le')
    a=int(base_units)*2; b=a+int(ink_units)*2
    assert raw[b:].decode('utf-16-le')=='RECOVERY-END\n'
    assert ''.join(chr(int(operation[i:i+4],16)) for i in range(0,len(operation),4))==case['recovery_operation']
    ink_rows=[r.split('\t') for r in raw[a:b].decode('utf-16-le').splitlines()]
    ink_stroke=next(r for r in ink_rows if r[0]=='S'); ink_points=[r for r in ink_rows if r[0]=='P']
    assert ink_stroke[1]==case['recovery_object'] and len(ink_points)==3
    committed=document(folder/('stage-10-committed-'+recovery))
    root,pages,strokes=committed
    assert (int(root[5]),len(pages),len(strokes))==(9,2,4)
    assert list(map(stable,strokes[:3]))==list(map(stable,final_strokes))
    assert strokes[-1][0][1]==ink_stroke[1] and strokes[-1][0][4:]==ink_stroke[4:] and strokes[-1][1]==ink_points
    assert sum(s[0][1]==case['recovery_object'] for s in strokes)==1
    assert len({sha(folder/(label+'-'+recovery)) for label in
        ('stage-10-committed','stage-11-saved-stop','stage-12-final-closed','stage-13-background-restored-closed')})==1
    pending_stop=load(folder/'stage-08-pending-stop.json'); saved_stop=load(folder/'stage-11-saved-stop.json')
    for stop,following in ((pending_stop,load(folder/'stage-09-pending-reopened.json')),
                            (saved_stop,load(folder/'stage-12-final-closed.json'))):
        assert stop['force_stop'] and stop['old_pid_gone'] and stop['files_unchanged_during_stop']
        assert stop['termination_not_native_receipt'] and stop['pid']!=stop['new_pid']==following['pid']
    receipts=[]
    assert load(folder/'stage-13-background-restored-closed.json')['pid']==saved_stop['new_pid']
    for label in ('stage-07-reopened-closed','stage-12-final-closed','stage-13-background-restored-closed'):
        stage=load(folder/(label+'.json')); events=[]
        for line in (folder/(label+'-render.log')).read_text(encoding='utf-8').splitlines():
            m=re.match(r'^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+D\s+NoteCoreRender\s*:\s*(.*)$',line)
            if m and int(m[2])==stage['pid']:
                events.append(m[3])
        assert any('frame-scheduled' in s and 'backend=surface-control' in s for s in events)
        assert any(s.startswith('release-receipt ') for s in events)
        assert events[-1]=='owner-drained charged=0'
        receipts.append({'pid':stage['pid'],'events':len(events),'frame_submissions':sum('frame-scheduled' in s for s in events),
            'release_receipts':sum(s.startswith('release-receipt ') for s in events),'last_event':events[-1]})
    obs=load(folder/'cua-observations.json')
    assert obs['known_cua_completed_strokes']==3 and obs['unattributed_additional_strokes']==0
    assert obs['inline_screenshots_observed'] and not obs['retained_pixel_oracle'] and obs['ordinary_app_not_instrumentation']
    assert obs['environment_exit']['not_native_release_evidence'] and obs['environment_exit']['cause']=='unknown'
    assert obs['continuous_background_restore_verified']
    assert installed['final_pid']==parsed[-1][0]['pid']
    for path in folder.rglob('*'):
        if path.is_file() and path.name!='emulator-window.log':
            bindings[path.relative_to(ROOT).as_posix()]=sha(path)
    for name in ('tools/capture_ordinary_android_case.py','tools/verify_ordinary_android_ui.py',
                 'tools/verify_ios_preview_ui.py','reports/preview-build.json',
                 'canvas-core/src/androidMain/kotlin/dev/notecore/canvas/prototype/AndroidInkFrameExperiment.kt'):
        bindings[name]=sha(ROOT/name)
    return {'schema':'ordinary-android-ui-1','status':'passed','scope':'Selected ordinary API36 emulator UI, actual files, SurfaceControl callbacks and two force-stop reopens',
        'artifact_sha256':installed['artifact_sha256'],'device':installed,'ordinary_receipts':receipts,
        'original_files_unchanged':64,'retained_new_documents':2,'ordinary_strokes':3,'recovered_total_strokes':4,
        'synthetic_recovery_seed_not_worker_persistence_proof':True,'ordinary_valid_fence_directly_observed':False,
        'startup_no_transient_blank_verified':False,'continuous_background_restore_verified':True,
        'cua_observations':obs,'source_and_artifact_sha256':dict(sorted(bindings.items())),
        'c1_frozen':False,'production_sqlite_authorized':False}


def main():
    report=verify()
    (ROOT/'reports/android-ordinary-recovery-ui.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('source_and_artifact_sha256','cua_observations')},ensure_ascii=False))


if __name__=='__main__':
    main()
