#!/usr/bin/env python3
"""Check the isolated Android consumer evidence; not a stable SDK, native-pool or physical-device gate."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CASE = ROOT/'build/sketch-host'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def verify():
    bindings={}
    stamp=CASE/'build-inputs.sha256'
    for line in stamp.read_text(encoding='utf-8').splitlines():
        digest,name=line.split('  ',1)
        assert sha(ROOT/name)==digest, ('Stale sample build',name)
        bindings[name]=digest
    assert set((CASE/'runtime-projects.txt').read_text(encoding='utf-8').splitlines())=={':samples:sketch-host',':shared-core',':canvas-core'}
    classes=(CASE/'dex-classes.txt').read_text(encoding='utf-8')
    assert 'Ldev/notecore/sample/sketch/SketchActivity;' in classes and 'Ldev/notecore/app/' not in classes
    log=(CASE/'instrumentation.log').read_text(encoding='utf-8')
    for expected in ('INSTRUMENTATION_RESULT: status=passed','INSTRUMENTATION_CODE: -1','input_policy_resources=passed:',
                     'lifecycle_owner=passed:','actual_points='):
        assert expected in log, ('Missing instrumented result',expected)
    before=read(CASE/'notecore-before.json'); after=read(CASE/'notecore-after.json')
    assert before==after and len(before['files'])==66
    assert before['apk_sha256']=='ce08c7d5b4e45a69266daad314db4a18c938c4c46ffa889e49f4aed246dec4a1'
    device=read(CASE/'device.json')
    apk=ROOT/'samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk'
    assert device['api']==36 and device['avd']=='NoteCorePreview' and device['installed_sample_apk_sha256']==sha(apk)
    obs=read(CASE/'cua-observations.json')
    assert obs['ordinary_app'] and obs['known_completed_strokes']==obs['readonly_preserved_strokes']==2
    issues=ET.parse(ROOT/'samples/sketch-host/build/reports/lint-results-debug.xml').getroot().findall('issue')
    assert not [i for i in issues if i.attrib['severity'] in ('Error','Fatal')]
    for name in ('build/sketch-host/build-inputs.sha256','build/sketch-host/runtime-projects.txt','build/sketch-host/dex-classes.txt',
                 'build/sketch-host/instrumentation.log','build/sketch-host/notecore-before.json','build/sketch-host/notecore-after.json',
                 'build/sketch-host/device.json','build/sketch-host/cua-observations.json','build/sketch-host/first-instrumentation.log',
                 'build/sketch-host/first-crash.log','tools/run_sketch_host.py','tools/verify_sketch_host.py','tools/check_architecture.py',
                 'samples/sketch-host/README.md'):
        bindings[name]=sha(ROOT/name)
    return {'schema':'independent-sketch-host-1','status':'passed','scope':'Independent API36 Android memory-sketch consumer: instrumented exact real samples, injected tools/background, readonly policy, owner and actual Activity recreation; selected CUA ordinary flow separately observed',
        'package':'dev.notecore.sample.sketch','apk':apk.relative_to(ROOT).as_posix(),'apk_sha256':sha(apk),'device':device,
        'resolved_project_dependencies':['samples:sketch-host','shared-core','canvas-core'],'apk_has_notecore_app_classes':False,
        'instrumented_observations':log.splitlines(),'ordinary_cua_observations':obs,'notecore_existing_notes_unchanged':66,
        'notecore_apk_unchanged':True,'lint_warnings':[i.attrib['id'] for i in issues],
        'first_failure':'MotionEvent float scalar quantization invalidated decimal-coordinate expectation; final test strictly compares all actual source-event x/y/time scalars. Initial crash retained.',
        'adoption':'Adopt as unfrozen independent Android sample; one original notebook/authority, direct canonical MONOLINE Path and borrowed background Canvas. No NoteStore, secondary storage queue or native resource pool.',
        'limits':['Memory only: no process durability or production storage','Policy at sample input/edit boundary; not a sandbox against host access to notebook',
                  'One fixed-size MONOLINE page; no mixed/PDF/complete annotation SDK','Actual recreation covers same main owner only; no cross-thread or sudden owner-loss guarantee',
                  'CUA screenshots observed inline; no retained ordinary pixel oracle','No physical stylus/heap/performance, API26-32, Apple independent sample or stable public whitelist acceptance'],
        'eng19_accepted':False,'c1_frozen':False,'production_sqlite_authorized':False,'source_and_artifact_sha256':dict(sorted(bindings.items()))}

if __name__=='__main__':
    report=verify()
    (ROOT/'reports/second-host-android.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('source_and_artifact_sha256','ordinary_cua_observations','instrumented_observations')},ensure_ascii=False))
