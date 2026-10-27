#!/usr/bin/env python3
"""Drive actual abrupt child exits at host file/SQLite boundaries; no production store integration."""
import hashlib
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
from contextlib import closing
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PROBE=ROOT/'tools/resource_commit_probe.py'
RECIPE=ROOT/'fixtures/storage/resource-commit-cases.json'
PAYLOADS=ROOT/'fixtures/storage/resource-gc-cases.json'
MARKER='notecore-synthetic-resource-commit-fixture-1'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def snapshot(folder):
    with closing(sqlite3.connect(folder/'catalog.sqlite')) as db:
        assert db.execute('PRAGMA foreign_key_check').fetchall()==[]
        return {'documents':db.execute('SELECT count(*) FROM document').fetchone()[0],
            'operations':db.execute('SELECT count(*) FROM operation').fetchone()[0],
            'resources':db.execute('SELECT count(*) FROM resource').fetchone()[0],
            'task':db.execute('SELECT state,hash FROM import_task').fetchone()}
def invoke(folder,attempt,fault=None,document='fixture-doc',action='commit'):
    command=[sys.executable,str(PROBE),'--root',str(folder),'--attempt',attempt,'--document',document]
    command+=['--action',action]
    if fault:command+=['--fault',fault]
    p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',timeout=15,env={**os.environ,'PYTHONUTF8':'1'})
    if fault:
        assert p.returncode==73 and not p.stdout.strip() and not p.stderr.strip(),(fault,p.returncode,p.stdout,p.stderr)
        return None
    assert p.returncode==0,(p.returncode,p.stdout,p.stderr)
    return json.loads(p.stdout)['status']
def task_identity(folder):
    with closing(sqlite3.connect(folder/'catalog.sqlite')) as db:
        return db.execute('SELECT state,cancel_requested,active_attempt,producer_pid FROM import_task').fetchone()

def live_cancel(folder,stale=False):
    if stale:invoke(folder,'old','stage-closed')
    child=subprocess.Popen([sys.executable,str(PROBE),'--root',str(folder),'--attempt','live','--hold-after-stage'],
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8',env={**os.environ,'PYTHONUTF8':'1'})
    try:
        deadline=time.monotonic()+5
        while not (folder/'live.ready').exists():
            assert child.poll() is None,'Producer exited before ready'
            assert time.monotonic()<deadline,'Producer readiness timed out'
            time.sleep(0.01)
        before=task_identity(folder);assert before==('running',0,'live',child.pid)
        assert invoke(folder,'live',action='cancel-confirm')=='NO_CANCEL_REQUEST'
        assert invoke(folder,'duplicate')=='PRODUCER_ACTIVE'
        if stale:
            for action in ('cancel-request','cancel-confirm'):
                assert invoke(folder,'old',action=action)=='STALE_ATTEMPT'
                assert task_identity(folder)==before
        assert invoke(folder,'live',action='cancel-request')=='CANCEL_REQUESTED'
        assert invoke(folder,'live',action='cancel-confirm')=='PRODUCER_ACTIVE'
        assert child.poll() is None and task_identity(folder)==('running',1,'live',child.pid)
        stage=folder/'staging/live.bin';original=signature(stage)
        assert snapshot(folder)['documents']==0 and not (folder/'resources').exists()
        (folder/'live.release').write_text('release',encoding='utf-8',newline='\n')
        stdout,stderr=child.communicate(timeout=5)
        assert child.returncode==0 and not stderr and json.loads(stdout)['status']=='CANCELLED'
        assert task_identity(folder)==('cancelled',1,'live',child.pid) and signature(stage)==original
        assert snapshot(folder)['documents']==snapshot(folder)['operations']==snapshot(folder)['resources']==0
        assert invoke(folder,'retry')=='CANCELLED' and not (folder/'staging/retry.bin').exists()
    finally:
        if child.poll() is None:
            (folder/'live.release').write_text('release',encoding='utf-8',newline='\n')
            try:child.communicate(timeout=5)
            except subprocess.TimeoutExpired:child.kill();child.communicate();raise

def signature(path):return (path.stat().st_ino,path.stat().st_mtime_ns,path.stat().st_size,sha(path))
def checks():
    build=(ROOT/'build').resolve();assert build.is_relative_to(ROOT);build.mkdir(exist_ok=True)
    recipe=json.loads(RECIPE.read_text(encoding='utf-8'));assert recipe['schema']=='resource-commit-boundaries-1-draft'
    assert recipe['origin']=='synthetic' and recipe['production_schema_frozen'] is False and recipe['space']=='fixture-space'
    source=json.loads(PAYLOADS.read_text(encoding='utf-8'))
    payload=bytes.fromhex(next(r['hex'] for r in source['resources'] if r['id']==recipe['payload_resource']))
    digest=hashlib.sha256(payload).hexdigest();observations=[]
    assert {r['fault'] for r in recipe['crash_cases']}=={'partial-stage','stage-closed','promoted','db-uncommitted','db-committed'}
    with tempfile.TemporaryDirectory(prefix='resource-joint-boundaries-',dir=build) as temporary:
        base=Path(temporary)
        def prepare(name):
            folder=base/name;folder.mkdir();(folder/'.fixture-scope').write_text(MARKER,encoding='utf-8',newline='\n')
            (folder/'source.bin').write_bytes(payload);return folder
        for case in recipe['crash_cases']:
            folder=prepare(case['fault']);invoke(folder,'first',case['fault']);before=snapshot(folder)
            target=folder/'resources'/digest;stage=folder/'staging/first.bin'
            assert before['documents']==case['visible_documents'] and before['operations']==case['operations'],case
            assert before['task'][0]==case['task_state'] and target.is_file()==case['resource_present'],case
            assert stage.is_file() and (sha(stage)==digest)==case['stage_complete'],case
            if case['fault']=='partial-stage':assert 0<stage.stat().st_size<len(payload) and before['task'][1] is None
            else:assert before['task'][1]==digest
            held_by_task=target.exists() and before['task']==('running',digest)
            if case['fault'] in ('promoted','db-uncommitted'):assert held_by_task and before['resources']==0
            existing=signature(target) if target.exists() else None
            status=invoke(folder,'retry')
            assert status==('ALREADY_COMMITTED' if case['fault']=='db-committed' else 'COMMITTED')
            after=snapshot(folder);assert after['documents']==after['operations']==after['resources']==1
            assert after['task']==('complete',digest) and sha(target)==digest
            if existing is not None:assert signature(target)==existing,'Retry must not overwrite promoted immutable bytes'
            assert invoke(folder,'replay')=='ALREADY_COMMITTED' and not (folder/'staging/replay.bin').exists()
            assert snapshot(folder)==after and (folder/'source.bin').read_bytes()==payload
            observations.append({'fault':case['fault'],'abrupt_exit':73,'before':before,'retry_status':status,
                'after':after,'promoted_but_uncommitted_held_by_task':bool(held_by_task),'source_preserved':True})
        folder=prepare('existing-bad-resource');target=folder/'resources'/digest;target.parent.mkdir();target.write_bytes(b'bad-resource')
        bad=signature(target);assert invoke(folder,'first')=='REPAIR_REQUIRED' and signature(target)==bad
        state=snapshot(folder);assert state['documents']==state['operations']==state['resources']==0 and state['task']==('running',digest)
        observations.append({'case':'preexisting_corrupt_resource_is_not_overwritten','status':'REPAIR_REQUIRED','visible_documents':0})
        folder=prepare('committed-replay');assert invoke(folder,'first')=='COMMITTED'
        state=snapshot(folder);target=folder/'resources'/digest;original=signature(target)
        assert invoke(folder,'conflict',document='other-document')=='IDENTITY_CONFLICT'
        assert snapshot(folder)==state and signature(target)==original and not (folder/'staging/conflict.bin').exists()
        observations.append({'case':'operation_identity_conflict_does_not_stage_or_commit','status':'IDENTITY_CONFLICT'})
        target.write_bytes(b'fixture-damage');damaged=signature(target)
        assert invoke(folder,'damaged-replay')=='REPAIR_REQUIRED' and signature(target)==damaged
        assert snapshot(folder)==state and (folder/'source.bin').read_bytes()==payload
        observations.append({'case':'committed_metadata_is_not_success_when_resource_damaged','status':'REPAIR_REQUIRED'})
        assert recipe['cancellation_cases']==['partial-stage','promoted','committed','live-producer','stale-attempt']
        for fault in ('partial-stage','promoted'):
            folder=prepare('cancel-'+fault);invoke(folder,'first',fault)
            stage=folder/'staging/first.bin';original=signature(stage)
            target=folder/'resources'/digest;promoted=signature(target) if target.exists() else None
            assert invoke(folder,'first',action='cancel-request')=='CANCEL_REQUESTED'
            assert task_identity(folder)[:3]==('running',1,'first')
            assert invoke(folder,'wrong',action='cancel-confirm')=='STALE_ATTEMPT'
            assert invoke(folder,'first',action='cancel-confirm')=='CANCELLED'
            assert invoke(folder,'retry')=='CANCELLED' and not (folder/'staging/retry.bin').exists()
            assert snapshot(folder)['documents']==snapshot(folder)['operations']==snapshot(folder)['resources']==0
            assert signature(stage)==original and (folder/'source.bin').read_bytes()==payload
            if promoted:assert signature(target)==promoted
            observations.append({'case':'cancel-'+fault,'status':'CANCELLED','files_preserved':True})
        folder=prepare('cancel-committed');assert invoke(folder,'first')=='COMMITTED'
        original=signature(folder/'resources'/digest);state=snapshot(folder);identity=task_identity(folder)
        for action in ('cancel-request','cancel-confirm'):
            assert invoke(folder,'first',action=action)=='ALREADY_COMMITTED'
            assert snapshot(folder)==state and task_identity(folder)==identity
            assert signature(folder/'resources'/digest)==original
        observations.append({'case':'cancel-committed','status':'ALREADY_COMMITTED'})
        for stale in (False,True):
            folder=prepare('stale-attempt' if stale else 'live-producer');live_cancel(folder,stale)
            assert (folder/'source.bin').read_bytes()==payload
            observations.append({'case':folder.name,'status':'CANCELLED','live_confirmation_refused':True})
    return {'status':'passed','host_sqlite_version':sqlite3.sqlite_version,'abrupt_process_exit_cases':len(recipe['crash_cases']),
        'integrity_identity_cases':3,'cancellation_cases':5,'live_producer_cases':2,'observations':observations,'source_payloads_preserved':True,
        'scope':'Host child-process exit, no-clobber file link and temporary SQLite transaction design probes; not a production importer or mobile durability implementation.',
        'directory_fsync_verified':False,'power_loss_verified':False,'resource_gc_deletion_implemented':False,
        'production_schema_frozen':False,'production_sqlite_authorized':False,'c1_gate_complete':False,'limits':recipe['limits']}
def main():
    report=checks();report['source_and_artifact_sha256']={p.relative_to(ROOT).as_posix():sha(p) for p in (PROBE,RECIPE,PAYLOADS,Path(__file__))}
    (ROOT/'reports/resource-commit-rules.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('observations','source_and_artifact_sha256')},ensure_ascii=False))
if __name__=='__main__':main()
