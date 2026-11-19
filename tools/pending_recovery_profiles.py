"""Inputs shared by the Android and Apple DEV-041 sandbox migration runners."""
import hashlib
import json
from pathlib import Path
import subprocess

from verify_sqlite_pending_recovery import ROOT, PIN, highlighter_fixture, sha

FIXED_HOST = ROOT / 'reports/dev041-pending-recovery-20261118.json'
CURRENT_HOST = ROOT / 'reports/dev041-highlighter-migration-20261119.json'


def load_pending_profile(current_build):
    pin = json.loads(PIN.read_bytes())
    host_path = CURRENT_HOST if current_build else FIXED_HOST
    host = json.loads(host_path.read_bytes())
    assert host['status'] == 'passed'
    paths = {Path(name).name: ROOT / name for name in pin['apple']['fixture_sha256']}
    for path in paths.values():
        assert sha(path) == pin['apple']['fixture_sha256'][path.relative_to(ROOT).as_posix()], path
    fixtures = {name: path.read_bytes() for name, path in paths.items()}
    inputs = [Path(__file__).resolve(), PIN, host_path, *paths.values()]
    if current_build:
        def git(*args):
            return subprocess.check_output(['git', '-C', str(current_build), *args], text=True).strip()

        preview_commit = host['preview_source_commit']
        assert git('rev-parse', 'HEAD') == preview_commit
        assert not git('status', '--porcelain', '--', 'canvas-core/src', 'shared-core/src', 'canvas-core/build.gradle.kts',
                       'shared-core/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties')
        names = ('prepare.ncp', 'confirmed-final.ncp')
        fixtures.update(zip(names, highlighter_fixture(*(fixtures[name] for name in names))))
        assert {name: hashlib.sha256(fixtures[name]).hexdigest() for name in names} == host['derived_fixture_sha256']
        source = {'preview_source_commit': preview_commit}
        inputs.append(ROOT / 'tools/verify_sqlite_pending_recovery.py')
    else:
        assert host['candidate_source_commit'] == pin['candidate_source_commit']
        source = {'candidate_source_commit': pin['candidate_source_commit']}
    return pin, host, fixtures, source, inputs
