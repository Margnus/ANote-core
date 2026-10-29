#!/usr/bin/env python3
"""Run read-only fixture queries on existing API36 ART and iOS Simulator system SQLite."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path('/Users/lixiaofan/Library/Android/sdk')
JDK = Path('/Users/lixiaofan/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home')
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
BUNDLE = 'dev.notecore.preview'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    scope = 'library-paging-'+str(uuid.uuid4())
    case = ROOT/'build'/scope
    case.mkdir()
    retained = ROOT/'reports/library-paging-platform-20261029'/scope
    retained.mkdir(parents=True)
    executions = []
    def run(args, label, binary=False):
        result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True,
                                timeout=120, env={**os.environ, 'JAVA_HOME': str(JDK)})
        record = {'label': label, 'argv': [str(a) for a in args], 'returncode': result.returncode,
                  'stdout_sha256': hashlib.sha256(result.stdout).hexdigest(),
                  'stderr_sha256': hashlib.sha256(result.stderr).hexdigest()}
        executions.append(record)
        if not binary:
            (retained/(label+'.log')).write_bytes(result.stdout+result.stderr)
        (retained/'execution.json').write_text(json.dumps(executions, ensure_ascii=False, indent=2)+'\n',
                                              encoding='utf-8', newline='\n')
        if result.returncode:
            raise RuntimeError((label, result.returncode, result.stderr.decode(errors='replace')[:2000]))
        return result.stdout if binary else result.stdout.decode('utf-8').strip()
    def adb(*args, label='adb', binary=False):
        return run([SDK/'platform-tools/adb', '-s', 'emulator-5554', *args], label, binary)
    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name', label='avd') == 'NoteCorePreview'
    assert adb('shell', 'getprop', 'ro.build.version.sdk', label='api') == '36'
    def android_snapshot(label):
        text = adb('shell', "run-as dev.notecore.preview sh -c 'sha256sum files/preview-notes/*.ncp'", label=label+'-notes')
        files = {}
        for line in text.splitlines():
            digest, name = line.split(None, 1)
            assert re.fullmatch('[0-9a-f]{64}', digest) and re.fullmatch(r'files/preview-notes/[A-Za-z0-9-]+\.ncp', name)
            files[Path(name).name] = digest
        remote = adb('shell', 'pm', 'path', BUNDLE, label=label+'-package').removeprefix('package:')
        assert re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~:-]+/base.apk', remote)
        apk = hashlib.sha256(adb('exec-out', 'cat', remote, label=label+'-apk', binary=True)).hexdigest()
        return {'files': files, 'apk_sha256': apk}
    before_android = android_snapshot('before-android')
    assert len(before_android['files']) == 66
    assert before_android['files'] == {
        Path(k).name: v for k, v in json.loads((ROOT/'reports/android-late-capture-20261029/original-files.json').read_text(encoding='utf-8')).items()}
    assert before_android['apk_sha256'] == json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))['android']['sha256']
    devices = json.loads(run(['xcrun', 'simctl', 'list', 'devices', '-j'], 'apple-devices'))['devices']
    assert any(d['udid'] == DEVICE and d['state'] == 'Booted' and d['name'] == 'NoteCorePreview'
               for group in devices.values() for d in group)
    data = Path(run(['xcrun', 'simctl', 'get_app_container', DEVICE, BUNDLE, 'data'], 'apple-data'))
    app = Path(run(['xcrun', 'simctl', 'get_app_container', DEVICE, BUNDLE, 'app'], 'apple-app'))
    def apple_snapshot():
        return {'files': {p.name: sha(p) for p in (data/'Documents/PreviewNotes').glob('*.ncp')},
                'payload': {p.relative_to(app).as_posix(): sha(p) for p in app.rglob('*') if p.is_file()}}
    before_apple = apple_snapshot()
    assert before_apple['files'] == json.loads((ROOT/'reports/apple-system-files-cancel-20261029/files-after.json').read_text(encoding='utf-8'))
    assert before_apple['payload'] == json.loads((ROOT/'reports/apple-system-files-cancel-20261029/payload-after.json').read_text(encoding='utf-8'))
    (retained/'before.json').write_text(json.dumps({'android': before_android, 'apple': before_apple}, indent=2)+'\n', encoding='utf-8', newline='\n')
    fixture = case/'fixture'
    run([sys.executable, ROOT/'tools/prepare_library_paging_platform.py', fixture], 'prepare')
    cases = json.loads((fixture/'cases.json').read_text(encoding='utf-8'))['cases']
    expected = {'cases': len(cases), 'queries': sum(c['kind'] == 'query' for c in cases),
                'query_rows': sum(c['rows'] for c in cases if c['kind'] == 'query')}
    classes = case/'classes'
    # Compile against Android's own Java stubs; JDK bootstrap APIs are not Android capabilities.
    run([JDK/'bin/javac', '-source', '8', '-target', '8', '-bootclasspath', SDK/'platforms/android-36/android.jar',
         '-cp', SDK/'platforms/android-36/android.jar', '-d', classes,
         ROOT/'tools/probes/LibraryPagingProbe.java'], 'javac')
    jar = case/'probe.jar'
    # The SDK launcher invokes PATH java and ignores JAVA_HOME on this host; bind the actual JDK.
    run([JDK/'bin/java', '-Xmx512m', '-cp', SDK/'build-tools/36.0.0/lib/d8.jar', 'com.android.tools.r8.D8',
         '--min-api', '26', '--lib', SDK/'platforms/android-36/android.jar',
         '--output', jar, *sorted(classes.rglob('*.class'))], 'd8')
    swift = case/'LibraryPagingProbe'
    sdk = Path(run(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], 'apple-sdk'))
    run(['xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios16.0-simulator',
         '-module-cache-path', case/'module-cache', ROOT/'tools/probes/LibraryPagingProbe.swift',
         '-lsqlite3', '-o', swift], 'swiftc')
    # Both runtimes receive the exact same closed/checkpointed source database and typed cases.
    stage = '/data/local/tmp/notecore-'+scope
    remote = 'cache/'+scope
    adb('shell', 'mkdir', stage, label='stage-mkdir')
    for path in [fixture/'fixture.sqlite', fixture/'cases.json', jar]:
        adb('push', str(path), stage+'/'+path.name, label='push-'+path.name)
    adb('shell', 'run-as', BUNDLE, 'mkdir', remote, label='cache-mkdir')
    for name in ('fixture.sqlite', 'cases.json', 'probe.jar'):
        adb('shell', 'run-as', BUNDLE, 'cp', stage+'/'+name, remote+'/'+name, label='copy-'+name)
    # API36 rejects writable dynamically loaded dex. Preserve the platform restriction.
    adb('shell', 'run-as', BUNDLE, 'chmod', '444', remote+'/probe.jar', label='readonly-dex')
    raw = adb('shell', 'run-as', BUNDLE, '/system/bin/env', 'CLASSPATH='+remote+'/probe.jar',
              '/system/bin/app_process', '/system/bin', 'dev.notecore.probes.LibraryPagingProbe', remote, label='android-run')
    apple_folder = data/'Library/Caches'/scope
    shutil.copytree(fixture, apple_folder)
    apple_raw = run(['xcrun', 'simctl', 'spawn', DEVICE, swift, apple_folder], 'apple-run')
    results = {}
    for platform, stdout in [('android', raw), ('apple', apple_raw)]:
        lines = [line.removeprefix('NOTECORE_PAGING ') for line in stdout.splitlines() if line.startswith('NOTECORE_PAGING ')]
        assert len(lines) == 1, (platform, stdout)
        result = json.loads(lines[0]); assert result['status'] == 'passed' and result['read_only']
        assert all(result[k] == v for k, v in expected.items()), (platform, result, expected)
        assert result['plans'] and all('USING COVERING INDEX page_' in p and 'TEMP B-TREE' not in p for p in result['plans'])
        results[platform] = result
    for name in ('fixture.sqlite', 'cases.json'):
        assert hashlib.sha256(adb('exec-out', 'run-as', BUNDLE, 'cat', remote+'/'+name, label='after-'+name, binary=True)).hexdigest() == sha(fixture/name)
        assert sha(apple_folder/name) == sha(fixture/name)
    after_android = android_snapshot('after-android')
    after_apple = apple_snapshot()
    assert after_android == before_android
    assert after_apple == before_apple
    (retained/'after.json').write_text(json.dumps({'android': after_android, 'apple': after_apple}, indent=2)+'\n', encoding='utf-8', newline='\n')
    archive = retained/'fixture.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(fixture.iterdir()):
            zipped.write(path, path.name)
        zipped.write(jar, 'executables/probe.jar')
        zipped.write(swift, 'executables/LibraryPagingProbe')
    inputs = [ROOT/p for p in ('tools/library_paging_probe.py', 'tools/prepare_library_paging_platform.py',
        'tools/verify_library_paging.py', 'tools/verify_library_paging_platform.py',
        'tools/probes/LibraryPagingProbe.java', 'tools/probes/LibraryPagingProbe.swift', 'reports/library-paging.json')]
    report = {'schema': 'library-paging-platform-1', 'status': 'passed', 'date': '2026-10-29',
        'environment': {'android_serial': 'emulator-5554', 'api': 36, 'avd': 'NoteCorePreview',
                        'apple_device': DEVICE, 'apple_runtime': next(runtime for runtime, group in devices.items()
                            if any(d['udid'] == DEVICE for d in group))},
        'scope': 'Exact bounded read-only SQL and typed-value replay on actual ART/API36 and iOS27 Simulator system SQLite; not platform pagination client/UI or concurrent revision restart conformance.',
        'expected': expected, 'platforms': results,
        'fixture_files_sha256': {p.name: sha(p) for p in fixture.iterdir()},
        'executables_sha256': {'android_dex_jar': sha(jar), 'apple_arm64_simulator': sha(swift)},
        'sdk_fingerprints': {'android_jar': sha(SDK/'platforms/android-36/android.jar'),
                             'apple_sqlite_header': sha(sdk/'usr/include/sqlite3.h')},
        'preservation': {'android_original_files': 66, 'apple_original_files': 16,
                         'android_installed_apk_and_apple_complete_payload_unchanged': True,
                         'original_file_hashes_unchanged': True, 'fixture_inputs_unchanged_both_platforms': True},
        'decision': 'Adopt tested parameter encodings, composite seeks and covering query shapes as compatible with these actual system engines. Keep host-only dynamic cursor validation/concurrency evidence separate.',
        'limitations': ['No App port, production DDL/migration or durable metadata write integration.',
                       'Android public API offers no SQLite authorizer; metadata-only SQL is enforced by probe input checks and covering plans. Apple uses the native authorizer.',
                       'No platform concurrent mutation/typed cursor-client replay, lifecycle/async cover or physical performance/heap evidence.',
                       'API26/32 and manufacturer matrix not run; iOS simulator is not physical iPad.',
                       'C1/M-1/DEV-041/production SQLite gates remain; no whole LIB-09 acceptance.'],
        'retained_initial_failures': ['SDK d8 launcher used PATH Java8 despite JAVA_HOME; explicit JDK17 selected.',
            'API36 refused writable dynamically loaded dex before queries; unique probe jar made read-only without disabling platform restrictions.',
            'Probe Files.readString was absent at runtime; replaced with readAllBytes, actual Android bootstrap stubs used for javac, and anonymous CursorFactory avoids missing LambdaMetafactory stubs. Query/oracle unchanged.'],
        'c1_gate_complete': False, 'whole_requirement_accepted': False,
        'source_and_retained_evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in
            [*inputs, *(ROOT/'reports/library-paging-platform-20261029').rglob('*')] if p.is_file()}}
    (ROOT/'reports/library-paging-platform.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': 'passed', 'expected': expected, 'platforms': results}, ensure_ascii=False))


if __name__ == '__main__':
    main()
