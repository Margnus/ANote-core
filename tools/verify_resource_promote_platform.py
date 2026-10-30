#!/usr/bin/env python3
"""Finite cache-only no-clobber link investigation on existing mobile runtimes."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path('/Users/lixiaofan/Library/Android/sdk')
JDK = Path('/Users/lixiaofan/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home')
DEVICE = '7F2A0226-A39E-4A86-BB26-B77DB5E3C1B0'
BUNDLE = 'dev.notecore.preview'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')


def main():
    scope = 'resource-promote-'+str(uuid.uuid4())
    case = ROOT/'build'/scope
    retained = ROOT/'reports/resource-promote-platform-20261029'/scope
    case.mkdir(); retained.mkdir(parents=True)
    executions = []
    save(retained/'source-at-start.json', {p.relative_to(ROOT).as_posix(): sha(p) for p in
        [Path(__file__), *sorted((ROOT/'tools/probes').glob('ResourcePromote*')),
         ROOT/'tools/probes/resource-promote-AndroidManifest.xml']})

    def run(args, label, binary=False, killed=False):
        result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True,
                                timeout=120, env={**os.environ, 'JAVA_HOME': str(JDK)})
        executions.append({'label': label, 'argv': [str(a) for a in args], 'returncode': result.returncode,
                           'stdout_sha256': hashlib.sha256(result.stdout).hexdigest(),
                           'stderr_sha256': hashlib.sha256(result.stderr).hexdigest()})
        if not binary:
            (retained/(label+'.stdout.log')).write_bytes(result.stdout)
            (retained/(label+'.stderr.log')).write_bytes(result.stderr)
        save(retained/'execution.json', executions)
        if killed:
            assert result.returncode != 0, (label, 'SIGKILL must terminate')
        elif result.returncode:
            raise RuntimeError((label, result.returncode, result.stderr.decode(errors='replace')[:2000]))
        return result.stdout if binary else result.stdout.decode('utf-8').strip()

    def adb(*args, label, binary=False, killed=False):
        return run([SDK/'platform-tools/adb', '-s', 'emulator-5554', *args], label, binary, killed)

    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name', label='avd') == 'NoteCorePreview'
    assert adb('shell', 'getprop', 'ro.build.version.sdk', label='api') == '36'

    def android_snapshot(label):
        raw = adb('shell', "run-as dev.notecore.preview sh -c 'sha256sum files/preview-notes/*.ncp'", label=label+'-notes')
        files = {}
        for line in raw.splitlines():
            digest, name = line.split(None, 1)
            assert re.fullmatch('[0-9a-f]{64}', digest) and re.fullmatch(r'files/preview-notes/[A-Za-z0-9-]+\.ncp', name)
            files[Path(name).name] = digest
        remote = adb('shell', 'pm', 'path', BUNDLE, label=label+'-package').removeprefix('package:')
        assert re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~:-]+/base.apk', remote)
        return {'files': files, 'apk_sha256': hashlib.sha256(adb('exec-out', 'cat', remote, label=label+'-apk', binary=True)).hexdigest(),
                'ordinary_pid': adb('shell', 'pidof', BUNDLE, label=label+'-pid')}

    devices = json.loads(run(['xcrun', 'simctl', 'list', 'devices', '-j'], 'apple-devices'))['devices']
    runtime = next(runtime for runtime, group in devices.items()
                   if any(d['udid'] == DEVICE and d['state'] == 'Booted' and d['name'] == 'NoteCorePreview' for d in group))
    data = Path(run(['xcrun', 'simctl', 'get_app_container', DEVICE, BUNDLE, 'data'], 'apple-data'))
    app = Path(run(['xcrun', 'simctl', 'get_app_container', DEVICE, BUNDLE, 'app'], 'apple-app'))

    def apple_snapshot(label):
        # launchctl list is a process observation, not a launch or UI action.
        jobs = run(['xcrun', 'simctl', 'spawn', DEVICE, 'launchctl', 'list'], label+'-jobs')
        lines = [line for line in jobs.splitlines() if 'UIKitApplication:'+BUNDLE+'[' in line]
        assert len(lines) == 1
        pid = lines[0].split()[0]; assert pid.isdigit()
        return {'files': {p.name: sha(p) for p in (data/'Documents/PreviewNotes').glob('*.ncp')},
                'payload': {p.relative_to(app).as_posix(): sha(p) for p in app.rglob('*') if p.is_file()},
                'ordinary_pid': pid}

    before = {'android': android_snapshot('before-android'), 'apple': apple_snapshot('before-apple')}
    assert len(before['android']['files']) == 66 and len(before['apple']['files']) == 16
    assert before['android']['files'] == {Path(k).name: v for k, v in json.loads((ROOT/'reports/android-late-capture-20261029/original-files.json').read_text(encoding='utf-8')).items()}
    assert before['android']['apk_sha256'] == json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))['android']['sha256']
    for field, filename in [('files', 'files-final.json'), ('payload', 'payload-final.json')]:
        assert before['apple'][field] == json.loads((ROOT/'reports/apple-export-cancel-feedback-20261029'/filename).read_text(encoding='utf-8'))
    assert not any('XCTest' in p or '.xctest/' in p for p in before['apple']['payload'])
    save(retained/'before.json', before)
    classes = case/'classes'
    run([JDK/'bin/javac', '-source', '8', '-target', '8', '-bootclasspath', SDK/'platforms/android-36/android.jar',
         '-cp', SDK/'platforms/android-36/android.jar', '-d', classes,
         ROOT/'tools/probes/ResourcePromoteProbe.java', ROOT/'tools/probes/ResourcePromoteInstrumentation.java'], 'javac')
    jar = case/'probe.jar'
    run([JDK/'bin/java', '-Xmx512m', '-cp', SDK/'build-tools/36.0.0/lib/d8.jar', 'com.android.tools.r8.D8',
         '--min-api', '26', '--lib', SDK/'platforms/android-36/android.jar', '--output', jar,
         *sorted(classes.rglob('*.class'))], 'd8')
    # Separate disposable APK, self-targeted instrumentation, actual App SELinux domain.
    # It cannot access NoteCore data and does not replace/restart the ordinary package.
    probe_package = 'dev.notecore.probes.resourcepromote.p'+scope.removeprefix('resource-promote-').replace('-', '')
    manifest = case/'AndroidManifest.xml'
    manifest.write_text((ROOT/'tools/probes/resource-promote-AndroidManifest.xml').read_text(encoding='utf-8')
                        .replace('dev.notecore.probes.resourcepromote', probe_package), encoding='utf-8', newline='\n')
    unsigned = case/'unsigned.apk'
    run([SDK/'build-tools/36.0.0/aapt2', 'link', '-I', SDK/'platforms/android-36/android.jar',
         '--manifest', manifest, '-o', unsigned], 'aapt2')
    with zipfile.ZipFile(jar) as dex, zipfile.ZipFile(unsigned, 'a') as apk:
        apk.writestr('classes.dex', dex.read('classes.dex'))
    key = case/'probe.keystore'
    run([JDK/'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'disposable', '-keypass', 'disposable',
         '-alias', 'probe', '-dname', 'CN=Disposable NoteCore Probe', '-keyalg', 'RSA', '-validity', '2'], 'probe-key')
    signed = case/'probe.apk'
    run([SDK/'build-tools/36.0.0/apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:disposable',
         '--key-pass', 'pass:disposable', '--out', signed, unsigned], 'sign-probe')
    swift = case/'ResourcePromoteProbe'
    sdk = Path(run(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], 'apple-sdk'))
    run(['xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios16.0-simulator',
         '-module-cache-path', case/'module-cache', ROOT/'tools/probes/ResourcePromoteProbe.swift', '-o', swift], 'swiftc')
    (case/'scope.txt').write_text(scope, encoding='utf-8', newline='\n')
    stage = '/data/local/tmp/notecore-'+scope
    remote = 'cache/'+scope
    adb('shell', 'mkdir', stage, label='stage-mkdir')
    adb('shell', 'run-as', BUNDLE, 'mkdir', remote, label='cache-mkdir')
    for path in [jar, case/'scope.txt']:
        adb('push', path, stage+'/'+path.name, label='push-'+path.name)
        adb('shell', 'run-as', BUNDLE, 'cp', stage+'/'+path.name, remote+'/'+path.name, label='copy-'+path.name)
    adb('shell', 'run-as', BUNDLE, 'chmod', '444', remote+'/probe.jar', label='readonly-dex')
    apple_folder = data/'Library/Caches'/scope
    apple_folder.mkdir(); shutil.copyfile(case/'scope.txt', apple_folder/'scope.txt')
    results = {}
    payload = bytes((i*31+7) % 251 for i in range(65539))
    digest = hashlib.sha256(payload).hexdigest()
    for platform in ['android', 'apple']:
        modes = {}
        for mode in ['cases', 'crash', 'recover']:
            if platform == 'android':
                raw = adb('shell', 'run-as', BUNDLE, '/system/bin/env', 'CLASSPATH='+remote+'/probe.jar',
                          '/system/bin/app_process', '/system/bin', 'dev.notecore.probes.ResourcePromoteProbe',
                          remote, mode, label=platform+'-'+mode, killed=mode == 'crash')
            else:
                raw = run(['xcrun', 'simctl', 'spawn', DEVICE, swift, apple_folder, mode], platform+'-'+mode, killed=mode == 'crash')
            rows = [json.loads(line.removeprefix('NOTECORE_PROMOTE ')) for line in raw.splitlines() if line.startswith('NOTECORE_PROMOTE ')]
            assert len(rows) == 1, (platform, mode, raw)
            item = rows[0]
            allowed = {'access_denied', 'passed'} if platform == 'android' and mode == 'cases' else {'kill_boundary' if mode == 'crash' else 'passed'}
            assert item['mode'] == mode and item['status'] in allowed
            assert str(item['pid']) != before[platform]['ordinary_pid']
            modes[mode] = item
            if platform == 'android' and item['status'] == 'access_denied':
                assert mode == 'cases' and item['stage']['sha256'] == digest and not item['target_exists']
                break
        if platform == 'android' and modes['cases']['status'] == 'access_denied':
            results[platform] = {'standalone': modes}
            assert 'u:r:runas_app:' in modes['cases']['selinux_context']
            assert not adb('shell', 'pm', 'list', 'packages', probe_package, label='probe-package-absent')
            adb('install', signed, label='install-disposable-probe')
            raw = adb('shell', 'am', 'instrument', '-w', '-r',
                      probe_package+'/dev.notecore.probes.ResourcePromoteInstrumentation', label='android-app-domain')
            lines = [line.removeprefix('INSTRUMENTATION_RESULT: notecore_promote=') for line in raw.splitlines()
                     if line.startswith('INSTRUMENTATION_RESULT: notecore_promote=')]
            assert len(lines) == 1 and 'INSTRUMENTATION_CODE: -1' in raw, raw
            app_result = json.loads(lines[0])
            assert 'u:r:untrusted_app' in app_result['selinux_context']
            assert app_result['status'] == 'access_denied', app_result
            assert app_result['stage']['sha256'] == digest and not app_result['target_exists']
            results[platform]['actual_app_domain'] = app_result
            results[platform]['decision'] = 'Reject hard-link candidate in tested API36 App cache: fresh link denied; collision/crash scenarios not reachable.'
            adb('uninstall', probe_package, label='remove-disposable-probe')
            assert not adb('shell', 'pm', 'list', 'packages', probe_package, label='probe-package-removed')
            continue
        assert len({r['pid'] for r in modes.values()}) == 3
        assert modes['cases']['stage_unlink_keeps_target']
        assert modes['crash']['linked_before_stage_cleanup'] == modes['recover']['after_process_death']
        for pair in [modes['cases']['fresh'], modes['crash']['linked_before_stage_cleanup']]:
            assert pair['stage'] == pair['target'] and pair['stage']['bytes'] == len(payload) and pair['stage']['sha256'] == digest
        for name in ['identical', 'corrupt']:
            item = modes['cases'][name]
            assert item['error'] and item['stage']['sha256'] == digest
            assert item['stage']['inode'] != item['target']['inode']
            assert item['target']['sha256'] == (digest if name == 'identical' else hashlib.sha256(bytes([1, 2, 3])).hexdigest())
        results[platform] = modes
    after = {'android': android_snapshot('after-android'), 'apple': apple_snapshot('after-apple')}
    assert before == after
    save(retained/'after.json', after)
    with zipfile.ZipFile(retained/'executables.zip', 'w', zipfile.ZIP_DEFLATED) as zipped:
        zipped.write(jar, 'probe.jar'); zipped.write(swift, 'ResourcePromoteProbe'); zipped.write(case/'scope.txt', 'scope.txt')
        zipped.write(signed, 'disposable-probe.apk'); zipped.write(manifest, 'AndroidManifest.xml')
    inputs = [ROOT/p for p in ('tools/verify_resource_promote_platform.py', 'tools/probes/ResourcePromoteProbe.java',
                              'tools/probes/ResourcePromoteProbe.swift', 'tools/probes/ResourcePromoteInstrumentation.java',
                              'tools/probes/resource-promote-AndroidManifest.xml')]
    report = {'schema': 'resource-promote-platform-1', 'status': 'completed_with_android_rejection', 'date': '2026-10-29',
              'scope': scope, 'environment': {'android_api': 36, 'android_avd': 'NoteCorePreview', 'apple_runtime': runtime, 'apple_device': DEVICE},
              'platforms': results, 'synthetic_payload': {'bytes': len(payload), 'sha256': digest},
              'preservation': {'android_original_files': 66, 'apple_original_files': 16,
                               'all_original_hashes_and_installed_payloads_unchanged': True, 'ordinary_processes_unchanged': True},
              'decision': 'Reject hard-link promotion on tested Android API36 real App and run-as cache domains. Adopt only the tested Apple simulator same-volume no-clobber candidate; a common production port is unresolved.',
              'limitations': ['No production resource port, database transaction, reader pin, cancellation/GC or import integration.',
                             'Synchronized stage and SIGKILL recovery are not directory synchronization, power-loss or physical storage durability evidence.',
                             'Cache-only same-volume files; no cross-volume, cloud/provider, arbitrary filesystem, API26/32 or physical iPad/manufacturer matrix.',
                             'Synthetic opaque bytes do not validate PDF/image/font parsing; C1/M-1/DEV-041 gates unchanged.'],
              'sdk_sha256': {'android_jar': sha(SDK/'platforms/android-36/android.jar'), 'apple_darwin_header': sha(sdk/'usr/include/sys/stat.h')},
              'c1_gate_complete': False, 'whole_requirement_accepted': False,
              'retained_initial_failures': ['Swift probe used isEmpty() instead of isEmpty; compile failed before any link operation.',
                                          'Initial standalone Android fresh link raised AccessDeniedException; actual App domain was then independently checked without relaxing policy.'],
              'source_and_retained_evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in
                    [*inputs, *(ROOT/'reports/resource-promote-platform-20261029').rglob('*')] if p.is_file()}}
    save(ROOT/'reports/resource-promote-platform.json', report)
    print(json.dumps({'status': report['status'], 'scope': scope, 'platforms': results}, ensure_ascii=False))


if __name__ == '__main__':
    main()
