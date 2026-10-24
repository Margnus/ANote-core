#!/usr/bin/env python3
"""Verify known read refusals reach the original owner separately from unknown failures."""
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(path):
    return json.loads(path.read_text(encoding='utf-8'))

def main():
    bindings={};reports={}
    for name,field in [('verification','verification_input_sha256'),('android-input-host-tests','source_and_artifact_sha256'),
                       ('ios-host-tests','source_and_framework_sha256')]:
        path=ROOT/'reports'/(name+'.json');r=read(path);assert r['status']=='passed'
        for relative,digest in r[field].items():
            assert sha(ROOT/relative)==digest,(name,relative)
        bindings[path.relative_to(ROOT).as_posix()]=sha(path);reports[name]=r
    android=reports['android-input-host-tests']
    assert 'read_failure_classification_to_owner' in android['storage_retention_checks']['checks']
    assert android['all_original_files_unchanged'] and android['original_file_count']==66
    assert 'testKnownReadRefusalsReachOwnerSeparatelyFromIOAndUnknown()' in reports['ios-host-tests']['checks']
    folder=ROOT/'build/read-owner';before=read(folder/'ios-before.json')
    assert len(before)==14 and before==read(folder/'ios-after.json')
    for name,digest in before.items():
        assert sha(folder/'ios-originals'/name)==digest
    restored=read(ROOT/'reports/ios-preview-restored.json')
    assert restored['ordinary_package_restored_after_host_tests'] and restored['no_xctest_plugin'] and not restored['data_deleted']
    for p in [Path(__file__),*folder.glob('*.json'),ROOT/'reports/ios-preview-restored.json',ROOT/'reports/preview-build.json',ROOT/'reports/ios-preview-build.json',
              ROOT/'android-app/src/main/java/dev/notecore/app/PreviewFileIO.kt',ROOT/'android-app/src/main/java/dev/notecore/app/NoteStore.kt',
              ROOT/'android-app/src/main/java/dev/notecore/app/MainActivity.kt',ROOT/'android-app/src/androidTest/java/dev/notecore/app/ReadFailureHostChecks.kt',
              ROOT/'android-app/src/androidTest/java/dev/notecore/app/PendingRecoveryHostChecks.kt',
              ROOT/'ios-app/NoteCorePreview/PreviewFileIO.swift',ROOT/'ios-app/NoteCorePreview/NoteCorePreviewApp.swift',ROOT/'ios-app/NoteCorePreviewTests/PagedHostTests.swift']:
        bindings[p.relative_to(ROOT).as_posix()]=sha(p)
    report={'schema':'read-owner-outcomes-1','status':'passed','scope':'Actual original Android Store/Swift Model worker-to-owner read outcomes and UI message/status consumers, isolated cache fixtures; no ordinary GUI or physical performance/durability claim',
        'known_failures':['invalid/unsupported format','unsupported preview content','character capacity','file byte capacity','invalid UTF8','document identity mismatch','recognized file IO failure'],
        'known_business_refusals_return_through_successful_worker_delivery':True,'unknown_non_io_error_injected_and_kept_distinct':True,
        'swift_system_cocoa_corruption_not_mislabeled_utf8':True,
        'checks':['known refusals create no owner and preserve exact fixture bytes','missing file reports IO without promising nonexistent file is retained',
                  'injected unknown is distinct and preserves existing valid fixture','unsupported retained ledger snapshot is refused, kept by identity, then repaired and actually written','valid retained snapshot identity is not cloned','later valid file opens after refusals','existing pending save/retry/reopen and lifecycle suites pass'],
        'original_android_files_unchanged':66,'original_ios_files_unchanged':14,
        'decision':'Adopt platform-local immutable Opened/Refused results on the original worker and owner. Existing retained-ledger preference, epoch guards, queue refusal and ACK remain. No new authority/queue/persistence schema.',
        'limits':['Unknown failure is controlled non-IO injection, not spontaneous OS fault proof','Ordinary UI message wiring is present; host assertions cover owner callback/status, no current CUA GUI acceptance',
                  'Shared common code unchanged; prior Native169 is not counted as a new run','No full public SDK/owner loss/cross-thread, unconfirmed IO interruption, power loss or physical heap/stylus validation'],
        'ordinary_ui_verified':False,'c1_gate_complete':False,'production_sqlite_authorized':False,
        'source_and_artifact_sha256':dict(sorted(bindings.items()))}
    (ROOT/'reports/read-owner-outcomes.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='source_and_artifact_sha256'},ensure_ascii=False))

if __name__=='__main__':
    main()
