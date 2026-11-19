"""Verify the pending-preview migration in a disposable iOS App sandbox."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import sqlite3
import subprocess
import tempfile
import time
import uuid

from sqlite_checkpoint_fixture import SCHEMA
from verify_apple_sqlite_joint_checkpoint import wait_for_exit
from pending_recovery_profiles import load_pending_profile
from verify_sqlite_pending_recovery import ROOT, sha

APP = ROOT / 'tools/probes/SqlitePendingCheckpointApp.swift'
SUPPORT = ROOT / 'tools/probes/SqliteProbeSupport.swift'
ANDROID = ROOT / 'reports/dev041-android-pending-recovery-20261118.json'


def verify(args, work):
    output = args.output

    def run(label, *command, timeout=60, codes=(0,)):
        result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, timeout=timeout)
        (output / (label + '.stdout.log')).write_bytes(result.stdout)
        (output / (label + '.stderr.log')).write_bytes(result.stderr)
        if result.returncode not in codes:
            raise RuntimeError((label, result.returncode, result.stderr.decode(errors='replace')))
        return result.stdout

    pin, _, fixtures, source, inputs = load_pending_profile(args.current_build)
    if args.current_build:
        android_path = ROOT / 'reports/dev041-android-highlighter-migration-20261119.json'
        framework = args.current_build / 'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework'
    else:
        android_path = ANDROID
        framework = args.framework.resolve()
    android = json.loads(android_path.read_bytes())
    assert android['status'] == 'passed' and all(android.get(key) == value for key, value in source.items())
    original, canonical = (fixtures[key] for key in ('prepare.ncp', 'confirmed-final.ncp'))
    assert hashlib.sha256(original).hexdigest() == android['source_archive_sha256']
    assert hashlib.sha256(canonical).hexdigest() == android['canonical_sha256']
    target = {'framework_sha256': sha(framework / 'NoteCanvasPrototype'),
              'header_sha256': sha(framework / 'Headers/NoteCanvasPrototype.h')}
    if not args.current_build:
        expected = pin['apple']['targets']['iosSimulatorArm64']
        assert all(expected[key] == value for key, value in target.items())

    suffix = uuid.uuid4().hex
    bundle = 'dev.notecore.probes.sqlitependingapple.p' + suffix
    app = work / 'PendingCheckpoint.app'
    app.mkdir()
    binary = app / 'PendingCheckpoint'
    (app / 'Info.plist').write_bytes(plistlib.dumps({
        'CFBundleIdentifier': bundle, 'CFBundlePackageType': 'APPL', 'CFBundleName': binary.name,
        'CFBundleExecutable': binary.name, 'CFBundleShortVersionString': '1.0', 'CFBundleVersion': '1',
        'MinimumOSVersion': '17.0', 'CFBundleSupportedPlatforms': ['iPhoneSimulator'], 'UIDeviceFamily': [1, 2],
        'UILaunchScreen': {}, 'UIApplicationSceneManifest': {'UIApplicationSupportsMultipleScenes': False,
            'UISceneConfigurations': {'UIWindowSceneSessionRoleApplication': [{
                'UISceneConfigurationName': 'PendingCheckpoint', 'UISceneDelegateClassName': 'Dev041PendingSceneDelegate',
            }]}},
    }))
    run('compile', 'xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target', 'arm64-apple-ios17.0-simulator',
        '-module-cache-path', work / 'module-cache', '-F', framework.parent, SUPPORT, APP, '-lsqlite3', '-o', binary)
    run('sign', 'codesign', '--force', '--sign', '-', app)
    link = run('link', 'xcrun', 'vtool', '-show-build', binary).decode()
    assert 'minos 17.0' in link and 'IOSSIMULATOR' in link
    simctl = ('xcrun', 'simctl')
    before = json.loads(run('devices-before', *simctl, 'list', 'devices', '-j'))
    runtimes = json.loads(run('runtimes', *simctl, 'list', 'runtimes', '-j'))
    runtime, = [item for item in runtimes['runtimes'] if item['identifier'] == args.runtime and item['isAvailable']]
    device = run('create', *simctl, 'create', 'NoteCore-Pending-' + suffix, args.device_type,
                 args.runtime).decode().strip()
    uuid.UUID(device)
    observations = {}

    def validate_rows(folder, committed):
        # Host inspection runs only on a scratch copy taken after the app process has exited.
        with tempfile.TemporaryDirectory(dir=work) as temporary:
            scratch = Path(temporary)
            for path in folder.glob('pending.sqlite*'):
                if path.name in {'pending.sqlite', 'pending.sqlite-wal'}:
                    shutil.copyfile(path, scratch / path.name)
            with closing(sqlite3.connect((scratch / 'pending.sqlite').as_uri() + '?mode=rw', uri=True)) as db:
                assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
                assert db.execute('PRAGMA foreign_key_check').fetchall() == []
                documents = db.execute('SELECT id,revision,checkpoint FROM document').fetchall()
                archived = db.execute('SELECT document_id,original FROM legacy_source').fetchall()
                operations = db.execute('SELECT * FROM operation').fetchall()
                if not committed:
                    assert documents == archived == operations == []
                    return
                (identity, revision, content), = documents
                assert identity and revision == android['checkpoint_revision'] and content == canonical
                assert archived == [(identity, original)]
                assert operations == [('recover-legacy', identity, None, revision, hashlib.sha256(canonical).digest(),
                                       hashlib.sha256(original).digest())]

    try:
        run('boot', *simctl, 'boot', device)
        run('bootstatus', *simctl, 'bootstatus', device, '-b', timeout=180)
        run('install', *simctl, 'install', device, app)
        installed = Path(run('installed-app', *simctl, 'get_app_container', device, bundle, 'app').decode().strip())
        assert sha(installed / binary.name) == sha(binary)
        home = Path(run('data-container', *simctl, 'get_app_container', device, bundle, 'data').decode().strip())
        folder = home / 'Library/Caches/dev041-pending'
        folder.mkdir(parents=True)
        for name, contents in fixtures.items():
            (folder / name).write_bytes(contents)
        (folder / 'schema.json').write_text(json.dumps(SCHEMA), encoding='utf-8')
        marker = folder / 'result.json'
        committed = False
        for key, expected in android['observations'].items():
            stage = expected['stage']
            status = expected.get('status', 'PROCESS_CUT')
            marker.unlink(missing_ok=True)
            launch = run(key, *simctl, 'launch', f'--stdout={output / (key + ".app-stdout.log")}',
                         f'--stderr={output / (key + ".app-stderr.log")}', device, bundle,
                         '--dev041-pending', stage).decode().strip()
            match = re.fullmatch(re.escape(bundle) + r': (\d+)', launch)
            assert match, launch
            pid = int(match[1])
            deadline = time.monotonic() + 30
            while not marker.exists() and time.monotonic() < deadline:
                time.sleep(.1)
            assert marker.is_file(), f'No App receipt for {key}; see {output / (key + ".app-stderr.log")}'
            event = json.loads(marker.read_bytes())
            assert event['pid'] == pid and event['stage'] == stage and event['status'] == status, event
            assert event['bundle'] == bundle and Path(event.pop('sandbox_home')).resolve() == home.resolve()
            assert pid not in {item['pid'] for item in observations.values()}
            if status != 'PROCESS_CUT':
                run(key + '-terminate', *simctl, 'terminate', device, bundle)
            wait_for_exit(pid)
            committed |= stage == 'after-commit'
            validate_rows(folder, committed)
            assert all((folder / name).read_bytes() == contents for name, contents in fixtures.items())
            observations[key] = event
    finally:
        try:
            run('shutdown', *simctl, 'shutdown', device, codes=(0, 1, 149))
        finally:
            run('delete', *simctl, 'delete', device)
    after = json.loads(run('devices-after', *simctl, 'list', 'devices', '-j'))
    assert not any(item['udid'] == device for group in after['devices'].values() for item in group)

    return {
        'status': 'passed', 'scope': 'UIKit App sandbox and native SQLite on iOS simulator; process kills',
        **source, 'runtime': {
            'identifier': args.runtime, 'version': runtime['version'], 'build': runtime['buildversion']},
        'observations': observations, 'matches_android_cut_and_receipt_matrix': True,
        'source_archive_sha256': android['source_archive_sha256'], 'canonical_sha256': android['canonical_sha256'],
        'checkpoint_revision': android['checkpoint_revision'], 'source_unchanged': True,
        'app_binary_sha256': sha(binary), **target,
        'deployment_target': '17.0', 'ios17_runtime_verified': runtime['version'].split('.')[0] == '17',
        'simulator_deleted': True, 'existing_device_inventory_unchanged': before == after,
        'input_sha256': {path.relative_to(ROOT).as_posix(): sha(path) for path in (
            Path(__file__).resolve(), APP, SUPPORT, android_path, *inputs,
            ROOT / 'tools/sqlite_checkpoint_fixture.py', ROOT / 'tools/verify_apple_sqlite_joint_checkpoint.py')},
        'raw_sha256': {path.name: sha(path) for path in sorted(output.iterdir()) if path.is_file()},
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    decoder = parser.add_mutually_exclusive_group(required=True)
    decoder.add_argument('--framework', type=Path, help='Use the fixed C1 framework')
    decoder.add_argument('--current-build', type=lambda path: Path(path).resolve(),
                         help='Use the framework built from the verified current preview commit')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runtime', default='com.apple.CoreSimulator.SimRuntime.iOS-27-0')
    parser.add_argument('--device-type', default='com.apple.CoreSimulator.SimDeviceType.iPhone-18-Pro')
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='dev041-apple-pending-', dir=ROOT / 'build') as directory:
        report = verify(args, Path(directory))
    (args.output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
