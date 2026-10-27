#!/usr/bin/env python3
"""Terminate the real original worker process at two IO boundaries using synthetic cache files."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = 'dev.notecore.preview'


def sha(value):
    return hashlib.sha256(value).hexdigest()


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--serial', required=True); parser.add_argument('--adb', required=True)
    args = parser.parse_args(); assert args.serial == 'emulator-5554'
    driver_sha = sha(Path(__file__).read_bytes())
    folder = ROOT / 'build/android-atomic-cut'; folder.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb, '-s', args.serial]

    def adb(*command):
        return subprocess.run([*prefix, *command], check=True, capture_output=True, timeout=120).stdout

    def pid():
        result = subprocess.run([*prefix, 'shell', 'pidof', BUNDLE], capture_output=True, timeout=10)
        assert result.returncode in (0, 1)
        value = result.stdout.decode().strip(); assert not value or re.fullmatch(r'\d+', value)
        return int(value) if value else None

    def original_files():
        names = adb('shell', 'run-as', BUNDLE, 'ls', 'files/preview-notes').decode().splitlines()
        return {name: sha(adb('exec-out', 'run-as', BUNDLE, 'cat', 'files/preview-notes/' + name))
                for name in names if re.fullmatch(r'[A-Za-z0-9-]+\.ncp', name)}

    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name').decode().strip() == 'NoteCorePreview'
    before = original_files(); assert len(before) == 66
    assert before == {Path(k).name: v for k, v in json.loads((ROOT / 'build/android-input-host/original-files.json').read_text(encoding='utf-8')).items()}
    for name, digest in before.items():
        assert sha((ROOT / 'build/pending-input-budget/android-originals' / name).read_bytes()) == digest
    build = json.loads((ROOT / 'reports/preview-build.json').read_text(encoding='utf-8')); assert build['status'] == 'passed'
    stamp = dict(line.split('  ', 1)[::-1] for line in (ROOT / 'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    bindings = {**build['source_and_build_input_sha256'], **stamp}
    assert 'android-app/src/androidTest/java/dev/notecore/app/AtomicCutHostChecks.kt' in bindings
    for name, digest in bindings.items(): assert sha((ROOT / name).read_bytes()) == digest, name
    apk = ROOT / build['android']['artifact']; assert sha(apk.read_bytes()) == build['android']['sha256']
    test_apk = ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install', '-r', str(apk)); adb('install', '-r', str(test_apk))
    for package, local in [(BUNDLE, apk), (BUNDLE + '.test', test_apk)]:
        remote = adb('shell', 'pm', 'path', package).decode().strip().removeprefix('package:')
        assert re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk', remote)
        assert sha(adb('exec-out', 'cat', remote)) == sha(local.read_bytes())
    observations = []; evidence = []
    for boundary in ['SYNCED_BEFORE_REPLACE', 'VERIFIED_BEFORE_RETURN']:
        identity = str(uuid.uuid4()); cache = 'cache/atomic-cut-' + identity
        stage = folder / boundary.lower(); stage.mkdir(exist_ok=True)

        def fixture(name):
            return adb('exec-out', 'run-as', BUNDLE, 'cat', cache + '/' + name)

        command = [*prefix, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_atomic_identity', identity,
                   '-e', 'notecore_atomic_boundary', boundary, '-e', 'notecore_atomic_cut', 'prepare',
                   BUNDLE + '.test/dev.notecore.app.ViewportInstrumentation']
        prepare_log = stage / 'prepare.log'
        with prepare_log.open('wb') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 20; ready = None
            while ready is None and time.monotonic() < deadline:
                if process.poll() is not None: raise AssertionError(prepare_log.read_text(encoding='utf-8', errors='replace'))
                try: ready = json.loads(fixture('ready.json'))
                except (subprocess.CalledProcessError, json.JSONDecodeError): time.sleep(.1)
            assert ready is not None, 'No live worker boundary observed; do not restart or infer completion'
            assert ready['boundary'] == boundary and ready['phase'] == 'SAVING' and ready['callbacks'] == 0 and ready['has_pending']
            victim = pid(); assert victim == ready['pid'] and victim is not None and process.poll() is None
            old = fixture('expected-old.txt'); pending = fixture('expected-pending.txt'); base = fixture('atomic-cut.ncp')
            assert old.startswith(b'NCPREVIEW') and pending.startswith(b'NCRECOVERY') and old != pending
            if boundary == 'SYNCED_BEFORE_REPLACE':
                temporary = fixture('atomic-cut.ncp.new'); assert base == old and temporary == pending
                (stage / 'temporary-before-kill.ncp').write_bytes(temporary)
            else:
                assert base == pending
                assert adb('shell', 'run-as', BUNDLE, 'ls', cache).decode().splitlines().count('atomic-cut.ncp.new') == 0
            for name, content in [('old.ncp', old), ('pending.ncp', pending), ('base-before-kill.ncp', base)]:
                (stage / name).write_bytes(content)
            # Kill only the exact app PID whose original owner/worker marker was observed live.
            assert pid() == victim and process.poll() is None
            adb('shell', 'am', 'force-stop', BUNDLE)
            assert pid() is None; process.wait(timeout=15)
        assert b'atomic_cut_status=passed' not in prepare_log.read_bytes()
        assert fixture('atomic-cut.ncp') == base
        if boundary == 'SYNCED_BEFORE_REPLACE': assert fixture('atomic-cut.ncp.new') == pending
        reopened = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_atomic_identity', identity,
                       '-e', 'notecore_atomic_boundary', boundary, '-e', 'notecore_atomic_cut', 'reopen',
                       BUNDLE + '.test/dev.notecore.app.ViewportInstrumentation').decode()
        reopen_log = stage / 'reopen.log'; reopen_log.write_text(reopened, encoding='utf-8')
        assert 'atomic_cut_status=passed' in reopened and 'INSTRUMENTATION_CODE: -1' in reopened, reopened
        result = json.loads(re.search(r'atomic_cut_observations=(\{.*\})', reopened).group(1))
        expect_pending = boundary == 'VERIFIED_BEFORE_RETURN'
        assert result['previous_pid'] == victim and result['pid'] != victim and result['boundary'] == boundary
        assert result['recovered_pending'] == expect_pending and result['committed_strokes'] == int(expect_pending)
        assert result['revision'] == int(expect_pending) and result['temporary_file_absent'] and result['original_owner_retired']
        observation = {'ready': ready, 'reopen': result, 'actual_process_terminated': True,
                       'base_before_kill_sha256': sha(base), 'old_sha256': sha(old), 'pending_sha256': sha(pending),
                       'outcome': 'previous file preserved; unconfirmed pending was not committed' if not expect_pending else 'pending file recovered before any prior owner ACK; explicit commit occurs exactly once'}
        record = stage / 'observation.json'; record.write_text(json.dumps(observation, indent=2) + '\n', encoding='utf-8')
        observations.append(observation); evidence.extend(p for p in stage.iterdir() if p.is_file())
    assert original_files() == before
    adb('shell', 'am', 'force-stop', BUNDLE)
    assert 'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', BUNDLE + '/dev.notecore.app.MainActivity').decode()
    assert original_files() == before and pid() is not None
    originals = folder / 'original-files.json'; originals.write_text(json.dumps(before, indent=2) + '\n', encoding='utf-8')
    sdk_contract = Path(args.adb).resolve().parent.parent / 'sources/android-36/android/util/AtomicFile.java'
    paths = [*evidence, originals, Path(__file__), apk, test_apk, ROOT / 'reports/preview-build.json',
             ROOT / 'reports/android-brush-build-inputs.sha256']
    assert sha(Path(__file__).read_bytes()) == driver_sha, 'Driver changed during execution'
    for name, digest in bindings.items(): assert sha((ROOT / name).read_bytes()) == digest, name
    for path in paths: bindings[path.relative_to(ROOT).as_posix()] = sha(path.read_bytes())
    report = {'schema': 'android-atomic-cut-1', 'status': 'passed', 'observations': observations,
              'original_files_preserved': 66, 'ordinary_process_restored': True, 'ordinary_pid': pid(),
              'apk_sha256': sha(apk.read_bytes()), 'source_and_artifact_sha256': dict(sorted(bindings.items())),
              'sdk_contract_sha256': {str(sdk_contract): sha(sdk_contract.read_bytes())},
              'decision': 'Keep the original serialized AtomicFile replacement/readback and owner ACK boundary. Process death before replacement preserves the old file and may lose the unconfirmed pending; after verified replacement a fresh process recovers the exact pending and commits once. Never infer owner confirmation from admission or a ready marker.',
              'scope': 'Two real force-stops at observed worker boundaries using unique synthetic cache files and fresh instrumentation processes. No abrupt power/storage failure, directory fsync guarantee, Apple run, physical M-1/heap/stylus, or production DURABLE receipt.',
              'c1_gate_complete': False, 'production_sqlite_authorized': False}
    (ROOT / 'reports/android-atomic-cut.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('source_and_artifact_sha256', 'sdk_contract_sha256')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
