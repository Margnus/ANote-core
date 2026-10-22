#!/usr/bin/env python3
"""Run isolated system-SQLite probes in actual ART and iOS Simulator processes."""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'dev.notecore.preview'
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
JAVA = 'android-app/src/androidTest/java/dev/notecore/app/probes/SqliteCrashProbe.java'
SWIFT = 'tools/probes/SqliteCrashProbe.swift'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, **options):
    return subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=60, **options)


def matrix(invoke, log):
    observations = []
    def step(stage, revision=None, payload=None, crash=None):
        result = invoke(stage)
        log.write(json.dumps({'stage': stage, 'exit': result.returncode}, sort_keys=True)+'\n'+result.stdout+result.stderr+'\n')
        log.flush()
        records = [json.loads(line.split('NOTECORE_SQLITE ',1)[1]) for line in result.stdout.splitlines() if line.startswith('NOTECORE_SQLITE ')]
        if crash:
            assert result.returncode != 0 and any(r.get('crash_point') == crash for r in records), (stage, result.stdout, result.stderr)
            if stage == 'kill_after_receipt': assert any(r.get('receipt_revision') == 3 for r in records)
            observations.append({'stage': stage, 'writer_exit': result.returncode, 'crash_point': crash})
            return
        assert result.returncode == 0 and len(records) == 1, (stage, result.stdout, result.stderr)
        value = records[0]
        assert value['stage'] == stage
        if revision is not None:
            assert (value['revision'], value['payload'], value['operation_count'], value['schema']) == (revision,payload,revision,1), value
            assert value['journal_mode'] == 'wal' and value['synchronous'] == 2, value
        if stage in ('unknown_schema','corrupt_readonly'): assert value['refused_without_modification'] is True
        if stage in ('conflict','stale','replay_base_conflict'): assert value['refused'] is True
        if stage == 'capacity_full': assert value['sqlite_capacity_full'] is True
        observations.append(value)
    step('init',1,'A')
    step('kill_uncommitted',crash='uncommitted'); step('verify',1,'A')
    step('kill_committed',crash='committed_no_receipt'); step('verify',2,'B')
    for stage in ('replay','conflict','replay_base_conflict','stale','rollback','migration_failure','capacity_full'):
        step(stage,2,'B')
    for stage in ('unknown_schema','corrupt_readonly'):
        step(stage); step('verify',2,'B')
    step('kill_after_receipt',crash='after_receipt'); step('verify',3,'C')
    step('replay_final',3,'C'); step('verify',3,'C')
    return observations


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--device', required=True)
    args = parser.parse_args()
    assert args.serial == 'emulator-5580' and args.device == DEVICE, 'Only existing dedicated simulators are authorized'
    build = ROOT/'build/sqlite-capability-bin'; build.mkdir(parents=True, exist_ok=True)
    def adb(*command):
        result = run([args.adb,'-s',args.serial,*command]); assert result.returncode == 0, result.stderr
        return result.stdout.strip()
    def android_files():
        result = adb('shell','run-as '+BUNDLE+' sh -c '+shlex.quote('sha256sum files/preview-notes/*.ncp'))
        files = {}
        for line in result.splitlines():
            digest, name = line.split(None,1)
            assert re.fullmatch('[0-9a-f]{64}',digest) and re.fullmatch(r'files/preview-notes/[a-zA-Z0-9-]+\.ncp',name)
            files[name] = digest
        return files
    before_android = android_files()
    stamp = dict(line.split('  ',1)[::-1] for line in (ROOT/'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    assert JAVA in stamp
    for relative,digest in stamp.items(): assert sha(ROOT/relative) == digest, ('stale probe build',relative)
    main_apk = ROOT/'dist/NoteCore-0.1.0-preview.apk'
    test_apk = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install','-r',str(test_apk))
    installed = [adb('shell','pm','path',package).removeprefix('package:') for package in (BUNDLE,BUNDLE+'.test')]
    assert all(re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk',path) for path in installed)
    for path,artifact in zip(installed,(main_apk,test_apk)):
        raw = subprocess.run([args.adb,'-s',args.serial,'exec-out','cat',path],capture_output=True,check=True,timeout=30).stdout
        assert hashlib.sha256(raw).hexdigest() == sha(artifact), 'Installed APK does not match retained artifact'
    android_root = 'cache/sqlite-capability-'+str(uuid.uuid4())
    def android(stage):
        return run([args.adb,'-s',args.serial,'shell','run-as',BUNDLE,'/system/bin/env',
                    'CLASSPATH='+':'.join(installed),'/system/bin/app_process','/system/bin',
                    'dev.notecore.app.probes.SqliteCrashProbe',android_root,stage])
    android_log = ROOT/'build/dev038-android-sqlite.log'
    with android_log.open('w',encoding='utf-8') as log: android_observed = matrix(android,log)
    assert android_files() == before_android, 'Canonical Android files changed'

    devices = json.loads(run(['xcrun','simctl','list','devices','available','--json'],check=True).stdout)
    runtime,device = next((runtime,device) for runtime,items in devices['devices'].items() for device in items if device['udid']==DEVICE)
    assert device['name'] == 'NoteCorePreview' and device['state'] == 'Booted'
    container = Path(run(['xcrun','simctl','get_app_container',DEVICE,BUNDLE,'data'],check=True).stdout.strip())
    def apple_files(): return {str(p.relative_to(container)):sha(p) for p in container.rglob('*.ncp') if p.is_file()}
    before_apple = apple_files()
    sdk = Path(run(['xcrun','--sdk','iphonesimulator','--show-sdk-path'],check=True).stdout.strip())
    binary = build/'SqliteCrashProbe'
    compile_command = ['xcrun','--sdk','iphonesimulator','swiftc','-target','arm64-apple-ios16.0-simulator',str(ROOT/SWIFT),'-lsqlite3','-o',str(binary)]
    compile_result = run(compile_command)
    compile_log = ROOT/'build/dev038-apple-sqlite-build.log'
    compile_log.write_text(compile_result.stdout+compile_result.stderr,encoding='utf-8')
    assert compile_result.returncode == 0, compile_result.stderr
    build_version = run(['xcrun','vtool','-show-build',str(binary)],check=True).stdout
    assert 'IOSSIMULATOR' in build_version, 'macOS executable is not iOS evidence'
    apple_root = ROOT/'build'/('sqlite-capability-'+str(uuid.uuid4()))
    def apple(stage): return run(['xcrun','simctl','spawn',DEVICE,str(binary),str(apple_root),stage])
    apple_log = ROOT/'build/dev038-apple-sqlite.log'
    with apple_log.open('w',encoding='utf-8') as log: apple_observed = matrix(apple,log)
    assert apple_files() == before_apple, 'Canonical Apple files changed'
    baseline = ROOT/'build/dev038-original-file-hashes.json'
    baseline.write_text(json.dumps({'android_before_and_after':before_android,'apple_before_and_after':before_apple},indent=2)+'\n',encoding='utf-8')
    sdk_android = Path(args.adb).resolve().parent.parent
    contracts = [sdk_android/'sources/android-36/android/database/sqlite/SQLiteDatabase.java',
                 sdk_android/'sources/android-36/android/database/DefaultDatabaseErrorHandler.java',sdk/'usr/include/sqlite3.h']
    evidence = set(stamp) | {JAVA,SWIFT,'tools/verify_sqlite_capabilities.py',
        str(binary.relative_to(ROOT)),str(android_log.relative_to(ROOT)),str(apple_log.relative_to(ROOT)),
        str(compile_log.relative_to(ROOT)),str(baseline.relative_to(ROOT))}
    report = {'schema':'sqlite-platform-capabilities-1','status':'passed',
        'android':{'serial':args.serial,'api':int(adb('shell','getprop','ro.build.version.sdk')),
                   'fingerprint':adb('shell','getprop','ro.build.fingerprint'),
                   'runtime':'real ART app_process using platform SQLiteDatabase, target App UID, isolated cache only',
                   'observations':android_observed,'original_file_count':len(before_android),'original_files_unchanged':True},
        'apple':{'device':DEVICE,'runtime':runtime,'target':'arm64 iOS Simulator, minimum 16.0, SDK '+sdk.name,
                 'binary_build_version':build_version,'observations':apple_observed,
                 'original_file_count':len(before_apple),'original_files_unchanged':True,
                 'original_files_scope':'All existing .ncp files under the app data container, including prior test fixtures if present; not a count of user-authored notes.'},
        'source_and_artifact_sha256':{relative:sha(ROOT/relative) for relative in sorted(evidence)},
        'sdk_contract_sha256':{str(path):sha(path) for path in contracts},
        'system_runtime_source_equivalence_verified':False,
        'decision':'Adopt native SQLite transaction/unique opId/CAS plus explicit WAL/FULL as a candidate persistence port foundation. Keep read-only version preflight and non-destructive corruption handling. No App production storage replacement or durable-receipt format freeze before C1.',
        'scope':'Synthetic state/operation atomicity, actual writer SIGKILL before commit/after commit before receipt/after receipt, replay/conflict/stale rejection, transaction and DDL/version rollback, SQLite max_page_count FULL, read-only unknown schema/corruption refusal. Platform-specific probes are test-only and are not shared-data or App owner integration.',
        'remaining_gates':['production canonical schema/port and resource staging/commit/GC protocol',
                           'Android API26-27 configuration and broader OS/vendor/physical matrix',
                           'real disk full/IO faults/power failure/fsync semantics and real devices',
                           'App durable receipt/UI integration, long stroke checkpoint and bounded queue backpressure',
                           'concurrent writer/locking and App single-writer enforcement; current probes use one writer at a time',
                           'production migration protocol/backup/restore/conformance and C1 review'],
        'references':['https://www.sqlite.org/atomiccommit.html','https://www.sqlite.org/pragma.html#pragma_synchronous',
                      'https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase.OpenParams.Builder',
                      'https://developer.android.com/reference/android/database/DefaultDatabaseErrorHandler'],
        'process_kill_verified':True,'physical_device_or_power_loss_verified':False,'real_device_disk_full_verified':False,
        'ordinary_app_storage_integration_verified':False,'c1_gate_complete':False}
    (ROOT/'reports/sqlite-platform-capabilities.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':'passed','android_sqlite':android_observed[0]['sqlite_version'],
        'apple_sqlite':apple_observed[0]['sqlite_version'],'stages_per_platform':len(android_observed),
        'original_android_files':len(before_android),'original_apple_files':len(before_apple),
        'production_storage_integrated':False},ensure_ascii=False))


if __name__ == '__main__': main()
