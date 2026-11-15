#!/usr/bin/env python3
"""Terminate the original worker process at observable IO boundaries in synthetic cache files."""
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
    isolated = parser.add_mutually_exclusive_group()
    isolated.add_argument('--isolated-api26', action='store_true', help='Use an empty NoteCoreAtomicCut26 emulator and the current APKs')
    isolated.add_argument('--isolated-api32', action='store_true', help='Use an empty NoteCoreAtomicCut32 emulator and the current APKs')
    args = parser.parse_args()
    isolated_api = 26 if args.isolated_api26 else 32 if args.isolated_api32 else None
    avd, serial = {26: ('NoteCoreAtomicCut26', 'emulator-5582'),
                   32: ('NoteCoreAtomicCut32', 'emulator-5586'),
                   None: ('NoteCorePreview', 'emulator-5554')}[isolated_api]
    assert args.serial == serial
    driver_sha = sha(Path(__file__).read_bytes())
    folder = ROOT / (f'build/android-atomic-cut-api{isolated_api}-{uuid.uuid4().hex}' if isolated_api else 'build/android-atomic-cut')
    folder.mkdir(parents=True, exist_ok=True)
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

    avd_property = 'ro.kernel.qemu.avd_name' if isolated_api == 26 else 'ro.boot.qemu.avd_name'
    assert adb('shell', 'getprop', avd_property).decode().strip() == avd
    fingerprint = adb('shell', 'getprop', 'ro.build.fingerprint').decode().strip()
    sdk_api = int(adb('shell', 'getprop', 'ro.build.version.sdk').decode().strip())
    original_binding_files = []
    if isolated_api:
        assert sdk_api == isolated_api
        installed_packages = adb('shell', 'pm', 'list', 'packages').decode().splitlines()
        assert not any(line in installed_packages for line in ('package:' + BUNDLE, 'package:' + BUNDLE + '.test'))
        apk = ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk'
        source_paths = subprocess.check_output(
            ['git', 'ls-files', 'canvas-core/src', 'shared-core/src', 'android-app/src', '*gradle.kts', 'gradle.properties'],
            cwd=ROOT, text=True).splitlines()
        bindings = {name: sha((ROOT / name).read_bytes()) for name in source_paths}
        before = {}
    else:
        before = original_files(); assert len(before) == 66
        assert before == {Path(k).name: v for k, v in json.loads((ROOT / 'build/android-input-host/original-files.json').read_text(encoding='utf-8')).items()}
        for name, digest in before.items():
            assert sha((ROOT / 'build/pending-input-budget/android-originals' / name).read_bytes()) == digest
        build_path = ROOT / 'reports/preview-build.json'
        stamp_path = ROOT / 'reports/android-brush-build-inputs.sha256'
        original_binding_files = [build_path, stamp_path]
        build = json.loads(build_path.read_text(encoding='utf-8')); assert build['status'] == 'passed'
        stamp = dict(line.split('  ', 1)[::-1] for line in stamp_path.read_text(encoding='utf-8').splitlines())
        bindings = {**build['source_and_build_input_sha256'], **stamp}
        apk = ROOT / build['android']['artifact']; assert sha(apk.read_bytes()) == build['android']['sha256']
        for name, digest in bindings.items(): assert sha((ROOT / name).read_bytes()) == digest, name
    assert 'android-app/src/androidTest/java/dev/notecore/app/AtomicCutHostChecks.kt' in bindings
    test_apk = ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install', '-r', str(apk)); adb('install', '-r', str(test_apk))
    for package, local in [(BUNDLE, apk), (BUNDLE + '.test', test_apk)]:
        remote = adb('shell', 'pm', 'path', package).decode().strip().removeprefix('package:')
        assert re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk', remote)
        assert sha(adb('exec-out', 'cat', remote)) == sha(local.read_bytes())
    if isolated_api:
        adb('shell', 'run-as', BUNDLE, 'mkdir', '-p', 'files/preview-notes')
        assert original_files() == before
    observations = []; evidence = []
    boundaries = ['SYNCED_BEFORE_REPLACE', 'VERIFIED_BEFORE_RETURN']
    if isolated_api:
        boundaries.insert(0, 'PREFIX_WRITTEN_BEFORE_SYNC')
    scenarios = [(boundary, False) for boundary in boundaries]
    if isolated_api:
        scenarios += [(boundary, True) for boundary in boundaries]
    for boundary, first_write in scenarios:
        identity = str(uuid.uuid4()); cache = 'cache/atomic-cut-' + identity
        stage = folder / (('first_write_' if first_write else '') + boundary.lower())
        stage.mkdir(exist_ok=True)

        def fixture(name):
            return adb('exec-out', 'run-as', BUNDLE, 'cat', cache + '/' + name)

        def base_file():
            names = adb('shell', 'run-as', BUNDLE, 'ls', cache).decode().splitlines()
            return fixture('atomic-cut.ncp') if 'atomic-cut.ncp' in names else None

        first_write_option = ['-e', 'notecore_atomic_first_write', 'true'] if first_write else []
        command = [*prefix, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_atomic_identity', identity,
                   '-e', 'notecore_atomic_boundary', boundary, *first_write_option, '-e', 'notecore_atomic_cut', 'prepare',
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
            assert ready.get('first_write', False) == first_write
            victim = pid(); assert victim == ready['pid'] and victim is not None and process.poll() is None
            old = None if first_write else fixture('expected-old.txt')
            pending = fixture('expected-pending.txt')
            base = base_file()
            assert pending.startswith(b'NCRECOVERY')
            assert old is None or (old.startswith(b'NCPREVIEW') and old != pending)
            if boundary != 'VERIFIED_BEFORE_RETURN':
                expected_temporary = pending if boundary == 'SYNCED_BEFORE_REPLACE' else pending[:len(pending)//2]
                temporary = fixture('atomic-cut.ncp.new'); assert base == old and temporary == expected_temporary
                (stage / 'temporary-before-kill.ncp').write_bytes(temporary)
            else:
                assert base == pending
                assert adb('shell', 'run-as', BUNDLE, 'ls', cache).decode().splitlines().count('atomic-cut.ncp.new') == 0
            for name, content in [('old.ncp', old), ('pending.ncp', pending), ('base-before-kill.ncp', base)]:
                if content is not None:
                    (stage / name).write_bytes(content)
            # Kill only the exact app PID whose original owner/worker marker was observed live.
            assert pid() == victim and process.poll() is None
            adb('shell', 'am', 'force-stop', BUNDLE)
            assert pid() is None; process.wait(timeout=15)
        assert b'atomic_cut_status=passed' not in prepare_log.read_bytes()
        assert base_file() == base
        if boundary != 'VERIFIED_BEFORE_RETURN': assert fixture('atomic-cut.ncp.new') == expected_temporary
        reopened = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'notecore_atomic_identity', identity,
                       '-e', 'notecore_atomic_boundary', boundary, *first_write_option, '-e', 'notecore_atomic_cut', 'reopen',
                       BUNDLE + '.test/dev.notecore.app.ViewportInstrumentation').decode()
        reopen_log = stage / 'reopen.log'; reopen_log.write_text(reopened, encoding='utf-8')
        assert 'atomic_cut_status=passed' in reopened and 'INSTRUMENTATION_CODE: -1' in reopened, reopened
        result = json.loads(re.search(r'atomic_cut_observations=(\{.*\})', reopened).group(1))
        expect_pending = boundary == 'VERIFIED_BEFORE_RETURN'
        expect_absent = first_write and not expect_pending
        assert result['previous_pid'] == victim and result['pid'] != victim and result['boundary'] == boundary
        assert result['recovered_pending'] == expect_pending and result['committed_strokes'] == int(expect_pending)
        assert result['revision'] == (None if expect_absent else int(expect_pending))
        assert result.get('document_absent', False) == expect_absent
        assert result['temporary_file_absent'] and result['original_owner_retired']
        observation = {'first_write': first_write, 'ready': ready, 'reopen': result, 'actual_process_terminated': True,
                       'base_before_kill_sha256': sha(base) if base is not None else None,
                       'old_sha256': sha(old) if old is not None else None, 'pending_sha256': sha(pending)}
        if expect_pending:
            observation['outcome'] = 'Complete pending recovered before owner ACK and explicitly committed once.'
        elif expect_absent:
            observation['outcome'] = 'Catalog stayed empty and removed the unpublished staging file.'
        else:
            observation['outcome'] = 'Previous file preserved and unpublished staging file removed.'
        record = stage / 'observation.json'; record.write_text(json.dumps(observation, indent=2) + '\n', encoding='utf-8')
        observations.append(observation); evidence.extend(p for p in stage.iterdir() if p.is_file())
    assert original_files() == before
    adb('shell', 'am', 'force-stop', BUNDLE)
    assert 'Status: ok' in adb('shell', 'am', 'start', '-W', '-n', BUNDLE + '/dev.notecore.app.MainActivity').decode()
    assert original_files() == before and pid() is not None
    originals = folder / 'original-files.json'; originals.write_text(json.dumps(before, indent=2) + '\n', encoding='utf-8')
    sdk_contract = Path(args.adb).resolve().parent.parent / 'sources/android-36/android/util/AtomicFile.java'
    paths = [*evidence, originals, Path(__file__), apk, test_apk, *original_binding_files]
    assert sha(Path(__file__).read_bytes()) == driver_sha, 'Driver changed during execution'
    for name, digest in bindings.items(): assert sha((ROOT / name).read_bytes()) == digest, name
    for path in paths: bindings[path.relative_to(ROOT).as_posix()] = sha(path.read_bytes())
    report = {'schema': 'android-atomic-cut-1', 'status': 'passed', 'observations': observations,
              'original_files_preserved': len(before), 'ordinary_process_restored': True, 'ordinary_pid': pid(),
              'android_api': sdk_api, 'android_fingerprint': fingerprint,
              'apk_sha256': sha(apk.read_bytes()), 'source_and_artifact_sha256': dict(sorted(bindings.items())),
              'sdk_contract_sha256': {} if isolated_api else {str(sdk_contract): sha(sdk_contract.read_bytes())},
              'decision': 'Stage in the same directory, sync, atomically move and read back on the original worker before owner ACK. Before the move the only catalog-visible version is any previously published file. After verified replacement a fresh process can recover the exact pending and commit once.',
              'scope': 'Real force-stops at observed original worker boundaries using unique synthetic cache files and fresh instrumentation processes. The prefix case uses an observed split write. Scope covers process death in the observed emulator.',
              'c1_gate_complete': False, 'production_sqlite_authorized': False}
    destination = folder / 'result.json' if isolated_api else ROOT / 'reports/android-atomic-cut.json'
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Evidence: {destination}')
    print(json.dumps({k: v for k, v in report.items() if k not in ('source_and_artifact_sha256', 'sdk_contract_sha256')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
