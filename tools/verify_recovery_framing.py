#!/usr/bin/env python3
"""Bind the original checkpoint decoder regression and actual host refusal evidence."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CHECK = 'signedOrNonAsciiFramingCannotChangeRecoveredOperationIdentity'

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def main():
    bindings={}
    for name,field in [('verification','verification_input_sha256'),('shared-native-contracts','source_and_artifact_sha256'),
                       ('android-input-host-tests','source_and_artifact_sha256'),('ios-host-tests','source_and_framework_sha256')]:
        p=ROOT/'reports'/(name+'.json'); r=read(p); assert r['status']=='passed'
        for relative,digest in r[field].items():
            assert sha(ROOT/relative)==digest, (name,relative)
        bindings[p.relative_to(ROOT).as_posix()]=sha(p)
    native=read(ROOT/'reports/shared-native-contracts.json')
    assert 'dev.notecore.document.PreviewRecoveryTest.'+CHECK in native['checks']
    xml=ROOT/'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.PreviewRecoveryTest.xml'
    suite=ET.parse(xml).getroot()
    assert int(suite.attrib['tests'])==7 and all(int(suite.attrib[k])==0 for k in ('failures','errors','skipped'))
    assert any(c.attrib['name'].startswith(CHECK) for c in suite.findall('testcase'))
    android=read(ROOT/'reports/android-input-host-tests.json')
    assert android['all_original_files_unchanged'] and android['original_file_count']==66
    assert 'pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen' in android['storage_retention_checks']['checks']
    apple=read(ROOT/'reports/ios-host-tests.json')
    assert 'testPendingRecoveryOriginalModelFailureRetryNewOwnerAndCanonicalReopen()' in apple['checks']
    folder=ROOT/'build/recovery-framing'
    before=read(folder/'ios-before.json'); after=read(folder/'ios-after.json')
    assert before==after and len(before)==14
    for name,digest in before.items():
        assert sha(folder/'ios-originals'/name)==digest
    restored=read(ROOT/'reports/ios-preview-restored.json')
    assert restored['ordinary_package_restored_after_host_tests'] and restored['no_xctest_plugin'] and not restored['data_deleted']
    assert '1 test completed, 1 failed' in (folder/'before-test.log').read_text(encoding='utf-8')
    for path in [xml,*folder.glob('*.json'),folder/'before-codec.kt',folder/'before-test.log',
                 ROOT/'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewRecoveryCodec.kt',
                 ROOT/'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewRecoveryTest.kt',
                 ROOT/'android-app/src/androidTest/java/dev/notecore/app/PendingRecoveryHostChecks.kt',
                 ROOT/'ios-app/NoteCorePreviewTests/PagedHostTests.swift',Path(__file__),
                 ROOT/'reports/ios-preview-restored.json',ROOT/'reports/preview-build.json',ROOT/'reports/ios-preview-build.json']:
        bindings[path.relative_to(ROOT).as_posix()]=sha(path)
    report={'schema':'recovery-framing-1','status':'passed','scope':'Original pending checkpoint ASCII framing: JVM negative regression, complete shared Native suite and actual Android Store / Swift Model isolated file refusal; no ordinary UI or physical durability inference',
        'before_fix_core':'8b90e55841d0bd04309f6e9252673b0252b1451f','regression_reproduced_before_fix':True,
        'checks':['signed/Unicode hex refused before radix conversion','signed/non-ASCII base and ink lengths refused',
                  'uppercase ASCII hex remains supported','valid Chinese/supplementary Unicode operation roundtrip',
                  'Android and Swift original file readers refuse truncated/signed-op/signed-length input without opening owner or rewriting files',
                  'existing pending failure/retry/new owner/explicit single commit/canonical reopen still passes'],
        'shared_recovery_test_count':7,'native_test_count':native['native_tests'] if 'native_tests' in native else len(native['checks']),
        'android_original_note_count':66,'ios_original_note_count':14,'all_original_files_unchanged':True,
        'decision':'Adopt strict ASCII lexical validation in original internal envelope; no format-version change for valid writer output, no fallback to base-only and no new owner/queue.',
        'ordinary_ui_verified':False,'interrupted_unconfirmed_write_verified':False,'power_loss_durability_verified':False,
        'physical_heap_or_stylus_verified':False,'stable_public_sdk_approved':False,'c1_gate_complete':False,'production_sqlite_authorized':False,
        'source_and_artifact_sha256':dict(sorted(bindings.items()))}
    (ROOT/'reports/recovery-framing.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='source_and_artifact_sha256'},ensure_ascii=False))

if __name__=='__main__':
    main()
