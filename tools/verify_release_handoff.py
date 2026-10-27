#!/usr/bin/env python3
"""Verify original release admission guard; not abrupt owner loss or physical GPU support."""
import json,hashlib
from pathlib import Path
from verification_hash import verification_digest
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    bindings={};reports={}
    for name,fields in [('verification',('source_sha256','verification_input_sha256')),('android-input-host-tests',('source_and_artifact_sha256','sdk_contract_sha256')),('android-min-api-host-tests',('source_and_artifact_sha256','sdk_contract_sha256'))]:
        path=ROOT/'reports'/(name+'.json');r=json.loads(path.read_text(encoding='utf-8'));assert r['status']=='passed'
        for field in fields:
            for relative,digest in r[field].items():assert (verification_digest(ROOT/relative) if name=='verification' else sha(ROOT/relative))==digest,(name,relative)
        reports[name]=r;bindings[path.relative_to(ROOT).as_posix()]=sha(path)
    for name,api in [('android-input-host-tests','35'),('android-min-api-host-tests','26')]:
        r=reports[name];assert r['api']==api and r['owner_retirement_check_count']==7
        assert 'unadmitted_release_refuses_before_retiring_authority' in r['owner_retirement_checks']['checks']
    folder=ROOT/'build/release-handoff'
    assert 'Unadmitted content was retired' in (folder/'before-fix-crash.log').read_text(encoding='utf-8')
    surface=(folder/'surface.log').read_text(encoding='utf-8');assert 'surface_status=passed' in surface and 'INSTRUMENTATION_CODE: -1' in surface
    before=json.loads((folder/'original-before.json').read_text(encoding='utf-8'))
    after=json.loads((folder/'original-after.json').read_text(encoding='utf-8'))
    assert before['files']==after['files'] and after['apk_sha256']==reports['android-input-host-tests']['apk_sha256']
    for relative in ['tools/verify_release_handoff.py','build/release-handoff/before-fix.log','build/release-handoff/before-fix-crash.log','build/release-handoff/before-fix-store.kt','build/release-handoff/surface.log','build/release-handoff/original-before.json','build/release-handoff/original-after.json']:
        bindings[relative]=sha(ROOT/relative)
    r={'status':'passed','scope':'Original Android Store release guard and original worker/renderer consumers on API26 and API35. Ordinary UI, current Apple, abrupt owner/thread disappearance and physical GPU remain unverified.',
       'decision':'Refuse active input and ordinary roots without exact durable or original ledger handoff before retiring the existing authority. Recovery still requires its exact completed IO receipt; admitted failed writes remain in the original ledger.',
       'jvm_tests':reports['verification']['jvm_contract_tests']['tests'],'android_apis':[26,35],'owner_checks_each':7,'input_checks_each':10,'storage_checks_each':10,
       'surface_host_passed':True,'original_notes_unchanged':len(before['files']),'c1_gate_complete':False,'production_sqlite_authorized':False,
       'source_and_artifact_sha256':dict(sorted(bindings.items()))}
    (ROOT/'reports/release-handoff.json').write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in r.items() if k!='source_and_artifact_sha256'},ensure_ascii=False))
if __name__=='__main__':main()
