#!/usr/bin/env python3
"""Experimental replay of the unchanged retained probe on a prepared API32 AVD.

Only actual emitted receipts establish runtime evidence. The original NoteCorePreview
AVD stays stopped and every disk file is hashed before/after; no original App run is
claimed. This tool does not download images or create devices.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path('/Users/lixiaofan/Library/Android/sdk')
RAW = ROOT / 'reports/android-atomic-promote-api32-20261031'
SERIAL = 'emulator-5582'
AVD = 'NoteCoreAtomic32'


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    RAW.mkdir(exist_ok=False)  # A failed attempt must be inspected, never overwritten.
    work = Path('/private/tmp/notecore-api32-single-sdk')
    work.mkdir(exist_ok=True)
    receipt_path = ROOT / 'reports/android-atomic-promote.json'
    prior = json.loads(receipt_path.read_text(encoding='utf-8'))
    assert prior['status'] == 'passed'
    archive = ROOT / 'reports/android-atomic-promote-20261031' / prior['scope'] / 'executables.zip'
    assert sha(archive) == prior['source_and_evidence_sha256'][archive.relative_to(ROOT).as_posix()]
    source_inputs = {p.relative_to(ROOT).as_posix(): sha(p) for p in (Path(__file__), receipt_path, archive)}
    save(RAW / 'source-at-start.json', source_inputs)
    with zipfile.ZipFile(archive) as zipped:
        apk = work / 'retained-probe.apk'
        apk.write_bytes(zipped.read('probe.apk'))
    records = []

    def run(args, label, allowed=(0,), binary=False):
        result = subprocess.run([str(a) for a in args], cwd=ROOT, capture_output=True, timeout=180)
        records.append({'label': label, 'argv': [str(a) for a in args], 'returncode': result.returncode,
                        'stdout_sha256': hashlib.sha256(result.stdout).hexdigest(),
                        'stderr_sha256': hashlib.sha256(result.stderr).hexdigest()})
        if not binary:
            (RAW / (label + '.stdout.log')).write_bytes(result.stdout)
            (RAW / (label + '.stderr.log')).write_bytes(result.stderr)
        save(RAW / 'execution.json', records)
        assert result.returncode in allowed, (label, result.returncode, result.stderr[:1000])
        return result.stdout if binary else result.stdout.decode('utf-8').strip()

    def adb(*args, label, serial=SERIAL, **kwargs):
        return run([SDK / 'platform-tools/adb', '-s', serial, *args], label, **kwargs)

    def original(label):
        folder = ROOT / '.android-avd/NoteCorePreview.avd'
        processes = subprocess.check_output(['ps', '-axo', 'pid=,command='], text=True)
        assert not any('qemu-system' in line and ('NoteCorePreview' in line or str(folder) in line)
                       for line in processes.splitlines()), 'Original AVD must remain stopped'
        files = {p.relative_to(folder).as_posix(): {'bytes': p.stat().st_size, 'sha256': sha(p)}
                 for p in sorted(folder.rglob('*')) if p.is_file()}
        assert files and any('userdata' in name for name in files)
        return {'avd': 'NoteCorePreview', 'offline_file_hashes': files,
                'scope': 'All existing original AVD disk bytes, no fresh original App/66-note runtime claim'}

    before = original('before'); save(RAW / 'before.json', before)
    assert adb('shell', 'getprop', 'sys.boot_completed', label='boot') == '1'
    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name', label='avd') == AVD
    assert adb('shell', 'getprop', 'ro.build.version.sdk', label='api') == '32'
    assert adb('shell', 'getprop', 'ro.product.cpu.abi', label='abi') == 'arm64-v8a'
    assert not adb('shell', 'pm', 'list', 'packages', 'dev.notecore.preview', label='no-preview-in-isolated-avd')
    environment = {name: adb('shell', *args, label=name) for name, args in [
        ('fingerprint', ['getprop', 'ro.build.fingerprint']), ('kernel', ['uname', '-r']),
        ('selinux', ['getenforce']), ('page_size', ['getconf', 'PAGE_SIZE'])]}
    assert environment['selinux'] == 'Enforcing'
    badging = run([SDK / 'build-tools/36.0.0/aapt2', 'dump', 'badging', apk], 'retained-apk-badging')
    package = re.search(r"^package: name='(dev\.notecore\.probes\.atomicpromote\.p[a-f0-9]+)'", badging).group(1)
    assert not adb('shell', 'pm', 'list', 'packages', package, label='probe-absent')
    scope = 'resource-atomic-' + str(uuid.uuid4())

    def instrument(mode):
        return adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'scope', scope, '-e', 'mode', mode,
                   package + '/dev.notecore.probes.AtomicPromoteProbe', label='instrument-' + mode)

    def result(raw):
        values = [json.loads(line.removeprefix('INSTRUMENTATION_RESULT: atomic_promote=')) for line in raw.splitlines()
                  if line.startswith('INSTRUMENTATION_RESULT: atomic_promote=')]
        assert len(values) == 1 and 'INSTRUMENTATION_CODE: -1' in raw, raw
        assert re.match(r'u:r:untrusted_app(?:_\d+)?:', values[0]['selinux_context'])
        return values[0]

    adb('install', apk, label='install-retained-probe')
    recovery = None
    try:
        cases = result(instrument('cases'))
        if cases['status'] == 'passed':
            assert 'Process crashed' in instrument('crash')
            marker = json.loads(adb('exec-out', 'run-as', package, 'cat', 'cache/' + scope + '/crash-marker.json', label='crash-marker'))
            assert marker['errno'] == 0
            assert not adb('shell', 'pidof', package, label='probe-stopped', allowed=(0, 1))
            adb('logcat', '-d', '--pid=' + str(marker['pid']), '-s', 'Process:I', '*:S', label='own-kill-log')
            recovery = result(instrument('recover'))
            assert recovery['status'] == 'passed' and recovery['before_death'] == marker
            assert len({cases['pid'], marker['pid'], recovery['pid']}) == 3
        else:
            assert cases['status'] == 'unsupported' and cases['errno'] != 0
    finally:
        adb('uninstall', package, label='remove-probe')
        assert not adb('shell', 'pm', 'list', 'packages', package, label='probe-removed')
    after = original('after'); save(RAW / 'after.json', after); assert before == after
    assert all(sha(ROOT / path) == digest for path, digest in source_inputs.items()), 'Replay inputs changed during execution'
    report = {'schema': 'android-atomic-promote-api32-1', 'status': cases['status'], 'scope': scope,
              'environment': environment, 'cases': cases, 'recovery': recovery,
              'retained_api36_report_sha256': sha(receipt_path), 'retained_executables_sha256': sha(archive),
              'identical_apk_sha256': sha(apk), 'original_avd_all_bytes_unchanged': True,
              'fresh_original_app_runtime_verified': False, 'probe_removed': True,
              'decision': 'Adopt only observed API32/App-domain same-volume no-replace capability.' if recovery else
                          'Reject native candidate on this runtime; keep errno and stage, no fallback.',
              'limits': ['Identical prior APK, no new JNI build or production port.',
                         'No API26, manufacturer, physical storage/heap, cross-volume, directory fsync or power-loss evidence.',
                         'Same-App thread race and SIGKILL-after-success only, not cross-process coordination or interrupted syscall.',
                         'No production resource/DB/task/GC integration, C1/M-1/DEV-041 approval or requirement promotion.'],
              'c1_complete': False, 'whole_requirement_accepted': False,
              'source_and_evidence_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in
                  [Path(__file__), receipt_path, archive, *RAW.rglob('*')] if p.is_file()}}
    save(ROOT / 'reports/android-atomic-promote-api32.json', report)
    print(json.dumps({'status': cases['status'], 'environment': environment, 'cases': cases, 'recovery': recovery}, ensure_ascii=False))


if __name__ == '__main__':
    main()
