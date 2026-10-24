#!/usr/bin/env python3
"""Bind complete typed checkpoint reading and original actual consumers, not a C1 approval."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
CHECKS={'fullCheckpointRetainsPendingUntilOriginalOwnerExplicitlyCommitsOnce','ordinaryV1V2RemainReadableWithoutManufacturingPending',
        'malformedOrOverCapacityInputNeverReturnsPartialBaseAndLaterReadStillWorks','validButUnsupportedOrdinaryAndEnvelopeContentKeepCapabilityRefusal'}

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def main():
    bindings={}; reports={}
    for name,field in [('verification','verification_input_sha256'),('shared-native-contracts','source_and_artifact_sha256'),
                       ('android-input-host-tests','source_and_artifact_sha256'),('ios-host-tests','source_and_framework_sha256'),
                       ('public-boundary-inventory','source_and_artifact_sha256')]:
        p=ROOT/'reports'/(name+'.json');r=read(p);assert r['status']=='passed'
        for relative,digest in r[field].items():
            assert sha(ROOT/relative)==digest,(name,relative)
        bindings[p.relative_to(ROOT).as_posix()]=sha(p);reports[name]=r
    xml=ROOT/'shared-core/build/test-results/jvmTest/TEST-dev.notecore.document.PreviewRecoveryReaderTest.xml'
    suite=ET.parse(xml).getroot()
    assert int(suite.attrib['tests'])==4 and all(int(suite.attrib[k])==0 for k in ('failures','errors','skipped'))
    assert {n.attrib['name'].removesuffix('[jvm]').removesuffix('()') for n in suite.findall('testcase')}==CHECKS
    assert {'dev.notecore.document.PreviewRecoveryReaderTest.'+n for n in CHECKS}<=set(reports['shared-native-contracts']['checks'])
    android=reports['android-input-host-tests'];assert android['all_original_files_unchanged'] and android['original_file_count']==66
    assert 'pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen' in android['storage_retention_checks']['checks']
    assert 'testPendingRecoveryOriginalModelFailureRetryNewOwnerAndCanonicalReopen()' in reports['ios-host-tests']['checks']
    assert 'XCTAssertThrowsError(try PreviewRecoveryCodec.shared.decode(text: corrupt))' in (ROOT/'ios-app/NoteCorePreviewTests/PagedHostTests.swift').read_text(encoding='utf-8')
    channels=reports['public-boundary-inventory']['reviewed_declaration_channels']
    for name,declares in [('read(text:)',False),('decode(text:)',True)]:
        assert any(n['objc_name']=='NCPPreviewRecoveryCodec' and n['swift_name']==name and n['declares_nserror']==declares for n in channels)
    folder=ROOT/'build/typed-recovery-read';before=read(folder/'ios-before.json')
    assert len(before)==14 and before==read(folder/'ios-after.json')
    for name,digest in before.items():
        assert sha(folder/'ios-originals'/name)==digest
    restored=read(ROOT/'reports/ios-preview-restored.json')
    assert restored['ordinary_package_restored_after_host_tests'] and restored['no_xctest_plugin'] and not restored['data_deleted']
    for p in [xml,Path(__file__),*folder.glob('*.json'),ROOT/'reports/ios-preview-restored.json',ROOT/'reports/preview-build.json',ROOT/'reports/ios-preview-build.json',
              ROOT/'shared-core/src/commonMain/kotlin/dev/notecore/document/PreviewRecoveryCodec.kt',
              ROOT/'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewRecoveryReaderTest.kt',
              ROOT/'android-app/src/main/java/dev/notecore/app/PreviewFileIO.kt',ROOT/'android-app/src/androidTest/java/dev/notecore/app/PendingRecoveryHostChecks.kt',
              ROOT/'ios-app/NoteCorePreview/NoteCorePreviewApp.swift',ROOT/'ios-app/NoteCorePreviewTests/PagedHostTests.swift']:
        bindings[p.relative_to(ROOT).as_posix()]=sha(p)
    report={'schema':'typed-recovery-read-1','status':'passed','scope':'Original pure bounded recovery reader: complete snapshot or typed refusal; shared JVM/Native plus actual Android Store/Swift Model worker reads; no ordinary UI or physical durability claim',
        'shared_checks':sorted(CHECKS),'native_checks':sorted(CHECKS),'ordinary_v1_v2_and_pending_preserved':True,
        'unsupported_ordinary_base_and_pending_capability_refusal':True,'malformed_input_never_returns_base_only':True,'legacy_throwing_decode_swift_runtime_refusal_verified':True,
        'android_original_files_unchanged':66,'ios_original_files_unchanged':14,
        'decision':'Adopt typed read on original codec, reuse existing canonical reader/decoder and full checkpoint. No IO/owner/callback inside content boundary; Android/Swift map refusal at original worker adapters. Legacy throwing decode stays available.',
        'limits':['Only decoder IllegalArgument validation is mapped; other exception types are not caught. Reader executes no IO, owner or callback',
                  'No mutable session until existing owner openRecovery; decoding never commits pending','No stable public whitelist or arbitrary constructor/null/index/owner safety claim',
                  'No unconfirmed IO interruption, replacement crash window, power loss, physical heap/stylus or current ordinary UI verification'],
        'ordinary_ui_verified':False,'c1_gate_complete':False,'production_sqlite_authorized':False,
        'source_and_artifact_sha256':dict(sorted(bindings.items()))}
    (ROOT/'reports/typed-recovery-read.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='source_and_artifact_sha256'},ensure_ascii=False))

if __name__=='__main__':
    main()
