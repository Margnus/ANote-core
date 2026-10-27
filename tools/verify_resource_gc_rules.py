#!/usr/bin/env python3
"""Execute source-backed GC eligibility rules on temporary host SQLite. Eligibility never mutates payloads; temporary fixtures are cleaned up."""
import hashlib
import json
import re
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FIXTURE=ROOT/'fixtures/storage/resource-gc-cases.json'
QUERY="""SELECT r.id FROM resource r WHERE r.space=? AND r.state='promoted'
 AND r.verified=1 AND r.promoted_at < ?
 AND NOT EXISTS (SELECT 1 FROM resource_ref f WHERE f.space=r.space AND f.hash=r.hash)
 ORDER BY r.id"""
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run_cases(recipe,query):
    observed=[]
    with tempfile.TemporaryDirectory(prefix='gc-eligibility-',dir=ROOT/'build') as temp:
        source=Path(temp)/'resources';source.mkdir()
        records={}
        for r in recipe['resources']:
            assert re.fullmatch(r'[a-z0-9-]{1,64}',r['id']) and r['id'] not in records
            payload=bytes.fromhex(r['hex']);assert len(payload)<=4096
            digest=hashlib.sha256(payload).hexdigest();path=source/(r['id']+'.bin')
            if not r.get('missing',False):path.write_bytes(payload+(b'!' if r.get('corrupt',False) else b''))
            verified=path.is_file() and path.stat().st_size==len(payload) and sha(path)==digest
            records[r['id']]=(r['space'],digest,len(payload),r['state'],int(verified),r['promoted_at'])
        originals={p.name:sha(p) for p in source.iterdir()}
        for case in recipe['cases']:
            with closing(sqlite3.connect(':memory:',isolation_level=None)) as db:
                db.execute('PRAGMA foreign_keys=ON')
                db.execute('PRAGMA user_version='+('1' if case.get('schema_known',True) else '99'))
                db.execute('CREATE TABLE resource(id TEXT UNIQUE NOT NULL,space TEXT NOT NULL,hash TEXT NOT NULL,bytes INTEGER NOT NULL CHECK(bytes>=0),state TEXT NOT NULL,verified INTEGER NOT NULL,promoted_at INTEGER,PRIMARY KEY(space,hash))')
                db.execute('CREATE TABLE resource_ref(space TEXT NOT NULL,hash TEXT NOT NULL,kind TEXT NOT NULL,owner TEXT NOT NULL,FOREIGN KEY(space,hash) REFERENCES resource(space,hash))')
                db.executemany('INSERT INTO resource VALUES(?,?,?,?,?,?,?)',[(rid,*values) for rid,values in records.items()])
                def reference(rid,kind):
                    space,digest,*_=records[rid]
                    db.execute('INSERT INTO resource_ref VALUES(?,?,?,?)',(space,digest,kind,'fixture-owner'))
                for ref in case.get('refs',[]):reference(ref['resource'],ref['kind'])
                def select():
                    if db.execute('PRAGMA user_version').fetchone()[0]!=1:return [],'UNKNOWN_SCHEMA'
                    if not case.get('references_complete',True):return [],'REFERENCES_INCOMPLETE'
                    if case['cutoff'] is None:return [],'POLICY_UNSET'
                    assert type(case['cutoff']) is int and case['cutoff']>=0
                    return [r[0] for r in db.execute(query,(case.get('space','local-a'),case['cutoff']))],None
                ids,refusal=select()
                assert ids==case['expected'] and refusal==case.get('refusal'),(case['id'],ids,refusal)
                after=None
                if 'late_reference' in case:
                    reference(case['late_reference'],'running-task');after,_=select()
                    assert after==case['expected_after'],case['id']
                if 'rollback_reference' in case:
                    db.execute('BEGIN IMMEDIATE');reference(case['rollback_reference'],'document');db.execute('ROLLBACK')
                    after,_=select();assert after==case['expected_after'],case['id']
                assert {p.name:sha(p) for p in source.iterdir()}==originals,'Eligibility must not delete or mutate any source'
                observed.append({'id':case['id'],'eligible':ids,'refusal':refusal,'after_mutation':after})
    return observed

def checks():
    build=(ROOT/'build').resolve()
    assert build.is_relative_to(ROOT), 'Temporary fixture cleanup must stay inside this workspace'
    build.mkdir(exist_ok=True)
    recipe=json.loads(FIXTURE.read_text(encoding='utf-8'))
    assert recipe['schema']=='resource-gc-rules-1-draft' and recipe['origin']=='synthetic'
    assert recipe['production_schema_frozen'] is False
    assert len({c['id'] for c in recipe['cases']})==len(recipe['cases'])
    observed=run_cases(recipe,QUERY)
    mutants={'ignore_references':QUERY.replace(' AND NOT EXISTS (SELECT 1 FROM resource_ref f WHERE f.space=r.space AND f.hash=r.hash)',''),
             'ignore_age':QUERY.replace(' AND r.promoted_at < ?',' AND ? IS NOT NULL'),
             'ignore_verification':QUERY.replace(' AND r.verified=1',''),
             'ignore_workspace':QUERY.replace('r.space=?','? IS NOT NULL'),
             'ignore_state':QUERY.replace(" AND r.state='promoted'",'')}
    killed=[]
    for name,query in mutants.items():
        try:run_cases(recipe,query)
        except AssertionError:killed.append(name)
        else:raise AssertionError('Unsafe query was not rejected: '+name)
    return {'status':'passed','case_count':len(observed),'observations':observed,'unsafe_query_mutations_rejected':killed,
        'host_sqlite_version':sqlite3.sqlite_version,'source_payloads_unchanged_during_checks':True,'collector_deleted_resource_files':False,'temporary_fixture_cleanup':True,
        'scope':'Draft eligibility query on synthetic opaque bytes and temporary host SQLite; not a production GC implementation, import transaction, final deletion authorization or platform durability test.',
        'candidate_is_deletion_authorization':False,'production_schema_frozen':False,'production_sqlite_authorized':False,'c1_gate_complete':False,
        'limits':recipe['limits']}
def main():
    report=checks()
    report['source_and_artifact_sha256']={p.relative_to(ROOT).as_posix():sha(p) for p in (FIXTURE,Path(__file__))}
    (ROOT/'reports/resource-gc-rules.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k not in ('observations','source_and_artifact_sha256')},ensure_ascii=False))
if __name__=='__main__':main()
