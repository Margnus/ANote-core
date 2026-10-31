#!/usr/bin/env python3
"""Finite native no-replace rename capability on a disposable self-targeted API36 APK."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path('/Users/lixiaofan/Library/Android/sdk')
JDK = Path('/Users/lixiaofan/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home')
NDK = SDK/'ndk/21.4.7075529'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')


def main():
    scope = 'resource-atomic-'+str(uuid.uuid4())
    work = ROOT/'build'/scope; work.mkdir()
    out = ROOT/'reports/android-atomic-promote-20261031'/scope; out.mkdir(parents=True)
    sources = [ROOT/p for p in ('tools/verify_android_atomic_promote.py', 'tools/probes/atomic_promote_probe.c',
                                'tools/probes/AtomicPromoteProbe.java', 'tools/probes/atomic-promote-AndroidManifest.xml')]
    save(out/'source-at-start.json', {p.relative_to(ROOT).as_posix(): sha(p) for p in sources})
    records = []

    def run(args, label, allowed=(0,), binary=False):
        result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True, timeout=180,
                                env={**os.environ, 'JAVA_HOME': str(JDK)})
        records.append({'label': label, 'argv': [str(a) for a in args], 'returncode': result.returncode,
                        'stdout_sha256': hashlib.sha256(result.stdout).hexdigest(),
                        'stderr_sha256': hashlib.sha256(result.stderr).hexdigest()})
        if not binary:
            (out/(label+'.stdout.log')).write_bytes(result.stdout); (out/(label+'.stderr.log')).write_bytes(result.stderr)
        save(out/'execution.json', records)
        assert result.returncode in allowed, (label, result.returncode, result.stderr.decode(errors='replace')[:2000])
        return result.stdout if binary else result.stdout.decode('utf-8').strip()

    def adb(*args, label, **kwargs):
        return run([SDK/'platform-tools/adb', '-s', 'emulator-5554', *args], label, **kwargs)

    assert adb('shell', 'getprop', 'sys.boot_completed', label='boot') == '1'
    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name', label='avd') == 'NoteCorePreview'
    assert adb('shell', 'getprop', 'ro.build.version.sdk', label='api') == '36'
    assert adb('shell', 'getprop', 'ro.product.cpu.abi', label='abi') == 'arm64-v8a'
    environment = {name: adb('shell', *args, label=name) for name, args in
                   [('kernel', ['uname', '-r']), ('selinux', ['getenforce']), ('page_size', ['getconf', 'PAGE_SIZE'])]}
    assert environment['selinux'] == 'Enforcing'

    def original(label):
        text = adb('shell', "run-as dev.notecore.preview sh -c 'sha256sum files/preview-notes/*.ncp'", label=label+'-notes')
        files = {}
        for line in text.splitlines():
            digest, name = line.split(None, 1)
            assert re.fullmatch('[0-9a-f]{64}', digest) and re.fullmatch(r'files/preview-notes/[A-Za-z0-9-]+\.ncp', name)
            files[Path(name).name] = digest
        remote = adb('shell', 'pm', 'path', 'dev.notecore.preview', label=label+'-package').removeprefix('package:')
        assert re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~:-]+/base.apk', remote)
        return {'files': files, 'apk_sha256': hashlib.sha256(adb('exec-out', 'cat', remote, label=label+'-apk', binary=True)).hexdigest(),
                'pid': adb('shell', 'pidof', 'dev.notecore.preview', label=label+'-pid', allowed=(0, 1))}

    initial = original('initial')
    assert initial['files'] == json.loads((ROOT/'reports/merge-20261030/original-files-after.json').read_text(encoding='utf-8'))['android']
    assert len(initial['files']) == 66 and initial['apk_sha256'] == json.loads((ROOT/'reports/preview-build.json').read_text(encoding='utf-8'))['android']['sha256']
    if not initial['pid']:
        assert 'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', 'dev.notecore.preview/dev.notecore.app.MainActivity', label='start-original')
    before = original('before'); assert before['pid'].isdigit()
    save(out/'before.json', before)
    native = NDK/'toolchains/llvm/prebuilt/darwin-x86_64'
    library = work/'libnotecore_atomic_promote_probe.so'
    # The installed r21 compiler is Intel-only and cannot execute on this Mac. Use existing
    # ARM Apple Clang + Kotlin/Native ARM ELF lld with the unchanged Android r21 sysroot/CRT.
    # This is a probe toolchain, not selection of a production JNI build configuration.
    clang = Path(run(['xcrun', '--find', 'clang'], 'clang-path'))
    linker = ROOT/'.konan/dependencies/llvm-19-aarch64-macos-essentials-79/bin/lld'
    sysroot = native/'sysroot'; android_lib = sysroot/'usr/lib/aarch64-linux-android/26'
    builtins = native/'lib64/clang/9.0.9/lib/linux/libclang_rt.builtins-aarch64-android.a'
    object_file = work/'atomic_promote_probe.o'
    run([clang, '--target=aarch64-linux-android26', '--sysroot='+str(sysroot),
         '-isystem', sysroot/'usr/include/aarch64-linux-android', '-fPIC', '-Wall', '-Wextra', '-Werror',
         '-c', ROOT/'tools/probes/atomic_promote_probe.c', '-o', object_file], 'clang')
    run([linker, '-flavor', 'gnu', '-shared', '--build-id=sha1', '-soname', library.name,
         '-z', 'noexecstack', '-z', 'relro', '-z', 'now', '-z', 'max-page-size=16384',
         android_lib/'crtbegin_so.o', object_file, '-L'+str(android_lib), '-lc', builtins,
         android_lib/'crtend_so.o', '-o', library], 'elf-link')
    run(['xcrun', 'llvm-objdump', '-f', '-p', library], 'elf-inspect')
    classes = work/'classes'
    run([JDK/'bin/javac', '-source', '8', '-target', '8', '-bootclasspath', SDK/'platforms/android-36/android.jar',
         '-cp', SDK/'platforms/android-36/android.jar', '-d', classes, ROOT/'tools/probes/AtomicPromoteProbe.java'], 'javac')
    jar = work/'probe.jar'
    run([JDK/'bin/java', '-Xmx512m', '-cp', SDK/'build-tools/36.0.0/lib/d8.jar', 'com.android.tools.r8.D8',
         '--min-api', '26', '--lib', SDK/'platforms/android-36/android.jar', '--output', jar, *sorted(classes.rglob('*.class'))], 'd8')
    package = 'dev.notecore.probes.atomicpromote.p'+scope.removeprefix('resource-atomic-').replace('-', '')
    manifest = work/'AndroidManifest.xml'
    manifest.write_text((ROOT/'tools/probes/atomic-promote-AndroidManifest.xml').read_text(encoding='utf-8')
                        .replace('dev.notecore.probes.atomicpromote', package), encoding='utf-8', newline='\n')
    unsigned = work/'unsigned.apk'
    run([SDK/'build-tools/36.0.0/aapt2', 'link', '-I', SDK/'platforms/android-36/android.jar', '--manifest', manifest, '-o', unsigned], 'aapt2')
    with zipfile.ZipFile(jar) as dex, zipfile.ZipFile(unsigned, 'a') as apk:
        apk.writestr('classes.dex', dex.read('classes.dex')); apk.write(library, 'lib/arm64-v8a/'+library.name)
    key = work/'probe.keystore'
    run([JDK/'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'disposable', '-keypass', 'disposable',
         '-alias', 'probe', '-dname', 'CN=Disposable NoteCore Probe', '-keyalg', 'RSA', '-validity', '2'], 'probe-key')
    signed = work/'probe.apk'
    run([SDK/'build-tools/36.0.0/apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:disposable',
         '--key-pass', 'pass:disposable', '--out', signed, unsigned], 'sign')
    assert not adb('shell', 'pm', 'list', 'packages', package, label='probe-absent')
    adb('install', signed, label='install-probe')

    def instrument(mode):
        return adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'scope', scope, '-e', 'mode', mode,
                   package+'/dev.notecore.probes.AtomicPromoteProbe', label='instrument-'+mode)

    def result(raw):
        values = [json.loads(line.removeprefix('INSTRUMENTATION_RESULT: atomic_promote=')) for line in raw.splitlines()
                  if line.startswith('INSTRUMENTATION_RESULT: atomic_promote=')]
        assert len(values) == 1 and 'INSTRUMENTATION_CODE: -1' in raw, raw
        assert 'u:r:untrusted_app:' in values[0]['selinux_context']
        return values[0]

    cases = result(instrument('cases')); recovery = None
    if cases['status'] == 'passed':
        raw = instrument('crash')
        assert 'Process crashed' in raw, raw
        marker = json.loads(adb('exec-out', 'run-as', package, 'cat', 'cache/'+scope+'/crash-marker.json', label='crash-marker'))
        assert marker['pid'] != int(before['pid']) and marker['errno'] == 0
        assert not adb('shell', 'pidof', package, label='probe-stopped', allowed=(0, 1))
        run([SDK/'platform-tools/adb', '-s', 'emulator-5554', 'logcat', '-d', '--pid='+str(marker['pid']), '-s', 'Process:I', '*:S'], 'own-kill-log')
        recovery = result(instrument('recover'))
        assert recovery['status'] == 'passed' and recovery['before_death'] == marker
        assert len({cases['pid'], marker['pid'], recovery['pid']}) == 3
    else:
        assert cases['status'] == 'unsupported' and cases['errno'] != 0
    after = original('after'); assert before == after
    save(out/'after.json', after)
    adb('uninstall', package, label='remove-probe')
    assert not adb('shell', 'pm', 'list', 'packages', package, label='probe-removed')
    with zipfile.ZipFile(out/'executables.zip', 'w', zipfile.ZIP_DEFLATED) as zipped:
        zipped.write(signed, 'probe.apk'); zipped.write(library, library.name); zipped.write(manifest, 'AndroidManifest.xml')
    source = json.loads((out/'source-at-start.json').read_text(encoding='utf-8'))
    assert all(sha(ROOT/path) == digest for path, digest in source.items())
    headers = [native/'sysroot/usr/include/asm-generic/unistd.h', native/'sysroot/usr/include/linux/fs.h',
               native/'sysroot/usr/include/jni.h', SDK/'platforms/android-36/android.jar', NDK/'source.properties',
               clang, linker, builtins, android_lib/'crtbegin_so.o', android_lib/'crtend_so.o', android_lib/'libc.so']
    report = {'schema': 'android-atomic-promote-1', 'date': '2026-10-31', 'scope': scope,
        'status': 'passed' if cases['status'] == 'passed' else 'unsupported', 'environment': environment,
        'cases': cases, 'recovery': recovery,
        'native_toolchain': 'Existing ARM Apple Clang (Android26 target) + existing ARM LLVM19 ELF lld + installed NDKr21 Android headers/CRT/builtins. No software installed; not a production NDK/compiler matrix.',
        'decision': 'Adopt only tested API36/App-domain same-volume RENAME_NOREPLACE capability; no production JNI port or matrix approval.' if recovery
                    else 'Reject this candidate on tested runtime; preserve errno and original stage. No overwrite/copy fallback.',
        'preservation': {'original_notes': 66, 'hashes_and_apk_and_ordinary_pid_unchanged': True, 'disposable_apk_removed': True},
        'compiler_and_sdk_sha256': {str(p): sha(p) for p in headers},
        'limitations': ['Native syscall capability only, not resource/Document/Operation/task transaction integration or final GC.',
                       'Two simultaneous writers are threads in the actual probe App; no claim of multi-process coordination or arbitrary external writers.',
                       'SIGKILL-after-rename is not interrupted syscall, directory fsync, power loss, physical storage or heap evidence.',
                       'No EXDEV fallback, API26/32/manufacturer/other-filesystem matrix; nonzero errno must refuse.',
                       'No NoteCore App/shared source changes or production native dependency; C1/M-1/DEV-041 and all requirement statuses remain.'],
        'c1_complete': False, 'whole_requirement_accepted': False,
        'retained_initial_failure': 'Installed NDKr21 Intel compiler failed with Bad CPU type before any JNI/API case; raw failure/source-at-start retained separately.',
        'source_and_evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in
            [*sources, *(ROOT/'reports/android-atomic-promote-20261031').rglob('*')] if p.is_file()}}
    save(ROOT/'reports/android-atomic-promote.json', report)
    print(json.dumps({'status': report['status'], 'scope': scope, 'cases': cases, 'recovery': recovery}, ensure_ascii=False))


if __name__ == '__main__':
    main()
