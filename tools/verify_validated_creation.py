#!/usr/bin/env python3
"""Verify typed original Notebook creation and source-bound actual consumers; no SDK freeze."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CHECKS={'invalidRawIdentityTitleAndNonceReturnFailureBeforeCreatingAuthority','invalidRawPageCannotReachPageDescriptorPrecondition',
        'validBoundaryCreationPreservesUnicodeAndUsesOriginalEditableAuthority','capacityIsTypedAndOwnerFailuresNeverBecomeContentRefusal'}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def main():
    bindings={}
    reports={}
    for name,field in [('verification','verification_input_sha256'),('shared-native-contracts','source_and_artifact_sha256'),
                       ('android-input-host-tests','source_and_artifact_sha256'),('ios-host-tests','source_and_framework_sha256'),
                       ('public-boundary-inventory','source_and_artifact_sha256')]:
        p=ROOT/'reports'/(name+'.json');r=read(p);assert r['status']=='passed'
        for relative,digest in r[field].items():
            assert sha(ROOT/relative)==digest,(name,relative)
        bindings[p.relative_to(ROOT).as_posix()]=sha(p);reports[name]=r
    suite=ROOT/'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.PreviewNotebookCreationTest.xml'
    xml=ET.parse(suite).getroot()
    assert int(xml.attrib['tests'])==4 and all(int(xml.attrib[k])==0 for k in ('failures','errors','skipped'))
    assert {n.attrib['name'].removesuffix('[jvm]').removesuffix('()') for n in xml.findall('testcase')}==CHECKS
    native=reports['shared-native-contracts']
    assert {'dev.notecore.document.PreviewNotebookCreationTest.'+n for n in CHECKS}<=set(native['checks'])
    android=reports['android-input-host-tests']
    assert 'validated_creation_refusal_and_independent_authority' in android['storage_retention_checks']['checks']
    assert android['all_original_files_unchanged'] and android['original_file_count']==66
    assert 'testValidatedCreationRejectsRawParametersAndModelUsesFactoryNotebook()' in reports['ios-host-tests']['checks']
    channels=reports['public-boundary-inventory']['reviewed_declaration_channels']
    assert any(n['swift_name']=='createValidated(documentId:title:sessionNonce:width:height:paper:budget:ownerCheck:)' and not n['declares_nserror'] for n in channels)
    folder=ROOT/'build/validated-creation'
    before=read(folder/'ios-before.json');assert before==read(folder/'ios-after.json') and len(before)==14
    for name,digest in before.items():
        assert sha(folder/'ios-originals'/name)==digest
    sample=folder/'sample-final'
    assert read(sample/'notecore-before.json')==read(sample/'notecore-after.json')
    result=(sample/'instrumentation.log').read_text(encoding='utf-8')
    assert 'INSTRUMENTATION_RESULT: status=passed' in result and 'INSTRUMENTATION_CODE: -1' in result and 'lifecycle_owner=passed:' in result
    stamp=ROOT/'build/sketch-host/build-inputs.sha256'
    for line in stamp.read_text(encoding='utf-8').splitlines():
        digest,name=line.split('  ',1);assert sha(ROOT/name)==digest,('stale sample build',name);bindings[name]=digest
    restored=read(ROOT/'reports/ios-preview-restored.json')
    assert restored['ordinary_package_restored_after_host_tests'] and restored['no_xctest_plugin'] and not restored['data_deleted']
    for p in [suite,stamp,Path(__file__),ROOT/'tools/run_sketch_host.py',ROOT/'reports/ios-preview-restored.json',
              ROOT/'reports/ios-preview-build.json',ROOT/'reports/preview-build.json',
              *folder.glob('*.json'),sample/'notecore-before.json',sample/'notecore-after.json',sample/'instrumentation.log']:
        bindings[p.relative_to(ROOT).as_posix()]=sha(p)
    report={'schema':'validated-creation-1','status':'passed','scope':'Raw ID/title/nonce/page creation refusal and original Notebook capacity/owner behavior, current Android Store/Swift Model and independent Android sample actual consumers; no arbitrary Swift budget constructor or stable SDK claim',
        'shared_creation_checks':sorted(CHECKS),'native_creation_checks':sorted(CHECKS),
        'swift_consumer_test':'testValidatedCreationRejectsRawParametersAndModelUsesFactoryNotebook()',
        'android_consumer_check':'validated_creation_refusal_and_independent_authority',
        'sample_activity_recreation_verified':True,'original_android_files_unchanged':66,'original_ios_files_unchanged':14,
        'decision':'Adopt unfrozen companion createValidated over the existing open/MemoryDocumentAuthority. Consumers directly retain returned original Notebook; no temporary Notebook plus second adoption. Caller must explicitly supply ownerCheck; no new default no-op owner. Keep legacy trusted throwing helper and budget/value constructor preconditions explicit.',
        'limits':['DocumentBudget and paper are already-valid values; their constructors, nullability and owner/programmer errors are outside typed content refusal',
                  'Canonical Unicode IDs are not a filesystem-safe-ID promise; App supplies UUIDs','Legacy exported constructors are not hidden or certified',
                  'Only same owner; no sudden owner loss or cross-thread transfer','No current ordinary UI, physical stylus/heap/performance or unconfirmed IO/power-loss proof'],
        'all_exported_apis_safe_for_arbitrary_swift_input':False,'ordinary_ui_verified':False,'c1_gate_complete':False,'production_sqlite_authorized':False,
        'source_and_artifact_sha256':dict(sorted(bindings.items()))}
    (ROOT/'reports/validated-creation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='source_and_artifact_sha256'},ensure_ascii=False))

if __name__=='__main__':
    main()
