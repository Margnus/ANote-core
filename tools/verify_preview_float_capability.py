#!/usr/bin/env python3
"""Bind actual preview adoption/worker checks; not a production geometry or device gate."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from verification_hash import verification_digest
ROOT=Path(__file__).resolve().parents[1]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    bindings={}
    reports={}
    for name,fields in [('verification',('source_sha256','verification_input_sha256')),('android-input-host-tests',('source_and_artifact_sha256','sdk_contract_sha256'))]:
        path=ROOT/'reports'/(name+'.json'); report=json.loads(path.read_text(encoding='utf-8'))
        assert report['status']=='passed'
        for field in fields:
            for relative,digest in report[field].items():
                target=ROOT/relative
                assert (verification_digest(target) if name=='verification' else sha(target))==digest,(name,relative)
        reports[name]=report;bindings[path.relative_to(ROOT).as_posix()]=sha(path)
    folder=ROOT/'build/preview-float-capability'
    before=ET.parse(folder/'before-fix.xml').getroot()
    assert int(before.attrib['tests'])==7 and int(before.attrib['failures'])==1
    for cls,count in [('PreviewDocumentReaderTest',7),('PreviewRecoveryReaderTest',5),('PreviewNotebookOpenTest',8)]:
        path=ROOT/'shared-core/build/test-results/jvmTest'/('TEST-dev.notecore.document.'+cls+'.xml')
        suite=ET.parse(path).getroot();assert int(suite.attrib['tests'])==count and int(suite.attrib['failures'])==0 and int(suite.attrib['errors'])==0
        bindings[path.relative_to(ROOT).as_posix()]=sha(path)
    current=ROOT/'build/preview-construction'
    construction=ET.parse(current/'before-fix.xml').getroot()
    assert int(construction.attrib['tests'])==8 and int(construction.attrib['failures'])==2
    assert construction.find("testcase[@name='directConstructorCannotBypassTheOriginalPreviewCapabilityBoundary[jvm]']/failure") is not None
    original=json.loads((current/'original-before.json').read_text(encoding='utf-8'))
    after=json.loads((current/'original-after.json').read_text(encoding='utf-8'))
    assert original['files']==after['files']
    host=reports['android-input-host-tests']
    assert after['apk_sha256']==host['apk_sha256'] and host['all_original_files_unchanged']
    for relative in ['tools/verify_preview_float_capability.py','build/preview-float-capability/before-fix.xml','build/preview-float-capability/before-fix-reader.kt','build/preview-float-capability/before-fix.log','build/preview-construction/before-fix.xml','build/preview-construction/before-fix-notebook.kt','build/preview-construction/before-fix.log','build/preview-construction/build-android.log','build/preview-construction/verify.log','build/preview-construction/original-before.json','build/preview-construction/original-after.json']:
        bindings[relative]=sha(ROOT/relative)
    report={'schema':'preview-float-capability-1','status':'passed','decision':'Refuse canonical widths with zero Float radius and transforms that lose invertibility in the existing Float adapter before preview adoption; preserve canonical doubles and source files. Apply through existing ordinary/recovery/retained/open boundaries and the original throwing constructor before authority allocation. Twelve original editing/history/reopen checkpoints remain supported; no new authority or format.',
        'jvm_tests':reports['verification']['jvm_contract_tests']['tests'],'reader_tests':7,'recovery_reader_tests':5,'opening_tests':8,'direct_constructor_refuses_before_authority':True,'editing_reopen_checkpoints':12,'before_fix_tests':7,'before_fix_failures':1,'construction_before_fix_failures':2,'construction_before_fix_failure_scope':'One capability bypass and one test fixture selecting the source page after copy undo/redo; fixture corrected to explicitly select restored copy',
        'android_api':host['api'],'android_view_checks':host['check_count'],'android_storage_checks':host['storage_retention_check_count'],'android_owner_checks':host['owner_retirement_check_count'],
        'original_note_count':len(original['files']),'original_files_unchanged':True,'ordinary_apk_sha256':after['apk_sha256'],
        'limits':['Float representability refusal is not a complete numeric accuracy, AA, near-boundary or production geometry contract','Android native worker uses unique cache fixtures and original owner callbacks, not ordinary UI or physical stylus','Current Apple Native/framework/Swift execution unavailable; shared source adoption is not dual-platform verification','Physical M-1, total budgets, public/owner/IO/power-loss and full C1 remain pending'],
        'c1_gate_complete':False,'production_sqlite_authorized':False,'source_and_artifact_sha256':dict(sorted(bindings.items()))}
    (ROOT/'reports/preview-float-capability.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({k:v for k,v in report.items() if k!='source_and_artifact_sha256'},ensure_ascii=False))
if __name__=='__main__':main()
