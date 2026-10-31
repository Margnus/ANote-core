#!/usr/bin/env python3
"""DRAFT: replay the retained API36 probe unchanged on a prepared API32 AVD.

Syntax checked only; this script has not produced API32 runtime evidence.
It requires NoteCoreAtomic32/emulator-5582 and the preserved API36 original App.
Environment preparation was interrupted; this tool does not download or create it.
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
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    RAW.mkdir(exist_ok=True)
    work = ROOT / 'build/android-api32-environment-20261031'
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
        notes = adb('shell', "run-as dev.notecore.preview sh -c 'sha256sum files/preview-notes/*.ncp'",
                    label=label + '-notes', serial='emulator-5554')
        hashes = {}
        for line in notes.splitlines():
            digest, path = line.split(None, 1)
            assert re.fullmatch('[a-f0-9]{64}', digest)
            assert re.fullmatch(r'files/preview-notes/[A-Za-z0-9-]+\.ncp', path)
            hashes[Path(path).name] = digest
        remote = adb('shell', 'pm', 'path', 'dev.notecore.preview', label=label + '-package',
                     serial='emulator-5554').removeprefix('package:')
        assert re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~:-]+/base.apk', remote)
        return {'notes': hashes, 'apk_sha256': hashlib.sha256(adb('exec-out', 'cat', remote,
                label=label + '-apk', serial='emulator-5554', binary=True)).hexdigest(),
                'pid': adb('shell', 'pidof', 'dev.notecore.preview', label=label + '-pid',
                           serial='emulator-5554', allowed=(0, 1))}

    before = original('before'); save(RAW / 'before.json', before)
    expected = json.loads((ROOT / 'reports/merge-20261030/original-files-after.json').read_text(encoding='utf-8'))['android']
    assert before['notes'] == expected and len(expected) == 66
    assert before['apk_sha256'] == json.loads((ROOT / 'reports/preview-build.json').read_text(encoding='utf-8'))['android']['sha256']
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
              'identical_apk_sha256': sha(apk), 'original_66_apk_pid_unchanged': True, 'probe_removed': True,
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
