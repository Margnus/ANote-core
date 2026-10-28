#!/usr/bin/env python3
"""Check the isolated Android consumer evidence; not a stable SDK, native-pool or physical-device gate."""
import argparse
import zipfile
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

def verify_current(case):
    case=(ROOT/case).resolve()
    assert case.is_relative_to((ROOT/'build').resolve())
    bindings={}
    for line in (case/'build-inputs.sha256').read_text(encoding='utf-8').splitlines():
        digest,name=line.split('  ',1); assert sha(ROOT/name)==digest, ('Stale current consumer',name)
        bindings[name]=digest
    projects=ROOT/'build/sketch-host/runtime-projects.txt'
    assert set(projects.read_text(encoding='utf-8').splitlines())=={':samples:sketch-host',':shared-core',':canvas-core'}
    log=(case/'instrumentation.log').read_text(encoding='utf-8')
    for value in ('INSTRUMENTATION_RESULT: status=passed','INSTRUMENTATION_CODE: -1','policy_boundary=passed:',
                  'input_policy_resources=passed:','lifecycle_owner=passed:','actual_points='):
        assert value in log, value
    before=read(case/'notecore-before.json');after=read(case/'notecore-after.json');assert before==after and len(before['files'])==66
    built=read(ROOT/'reports/preview-build.json');assert built['status']=='passed' and before['apk_sha256']==built['android']['sha256']
    device=read(case/'device.json');assert device['api']==36 and device['avd']=='NoteCorePreview' and device['serial']=='emulator-5554'
    apk=ROOT/'samples/sketch-host/build/outputs/apk/debug/sketch-host-debug.apk'
    assert device['ordinary_sample_relaunched'] and device['ordinary_launch_pid']>1
    assert 'Status: ok' in (case/'ordinary-launch.log').read_text(encoding='utf-8')
    assert device['installed_sample_apk_sha256']==sha(apk) and device['runner_sha256']==sha(ROOT/'tools/run_sketch_host.py')
    with zipfile.ZipFile(apk) as archive:
        dex=[archive.read(n) for n in archive.namelist() if n.endswith('.dex')]
    assert dex and any(b'Ldev/notecore/sample/sketch/SketchActivity;' in d for d in dex)
    assert all(b'Ldev/notecore/app/' not in d for d in dex), 'NoteCore App descriptor entered independent APK'
    issues=ET.parse(ROOT/'samples/sketch-host/build/reports/lint-results-debug.xml').getroot().findall('issue')
    assert not any(i.attrib['severity'] in ('Error','Fatal') for i in issues)
    paths=[projects,ROOT/'reports/preview-build.json',ROOT/'tools/run_sketch_host.py',Path(__file__),
           ROOT/'samples/sketch-host/README.md',ROOT/'docs/最小预览消费者契约.md']
    paths += [case/n for n in ('build-inputs.sha256','instrumentation.log','notecore-before.json','notecore-after.json','device.json','ordinary-launch.log')]
    bindings.update({p.relative_to(ROOT).as_posix():sha(p) for p in paths})
    return {'schema':'independent-sketch-current-1','status':'passed','package':'dev.notecore.sample.sketch',
        'artifact':apk.relative_to(ROOT).as_posix(),'artifact_sha256':sha(apk),'device':device,
        'source_and_artifact_sha256':bindings,'original_notecore_files_preserved':66,'original_notecore_apk_preserved':True,
        'project_dependencies':['samples:sketch-host','shared-core','canvas-core'],'note_app_dex_descriptor_absent':True,
        'observations':log.splitlines(),'ordinary_ui_verified':False,
        'scope':'Current independent API36 instrumented sample, injected tools/background, full tool validation, restricted edit refusal before Core, original input/undo/redo, actual Activity recreation and owner rejection. DEX descriptor scan plus build dependency evidence; no security-sandbox or ordinary CUA claim. Historical fixed-package/UI report remains separate.',
        'decision':'Adopt the corrected sample boundary over the original notebook; no App/Store/worker/native pool or new authority.',
        'c1_gate_complete':False,'production_sqlite_authorized':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--case-directory');args=parser.parse_args()
    report=verify_current(args.case_directory) if args.case_directory else verify()
    name='second-host-android-current.json' if args.case_directory else 'second-host-android.json'
    (ROOT/'reports'/name).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('source_and_artifact_sha256','ordinary_cua_observations','instrumented_observations','observations')},ensure_ascii=False))
