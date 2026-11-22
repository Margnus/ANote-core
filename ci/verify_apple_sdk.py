"""Rebuild the current isolated Apple SDK and run the existing independent failure consumer."""
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

from gradle_download_retry import run_gradle
from apple_runtime import select_runtime

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/apple-sdk-current'
sys.path.insert(0, str(ROOT / 'tools'))
from verify_c1_apple_failure_channels import verify
from audit_sdk_type_closure import audit
from audit_public_boundary import inspect_header


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    paths = set()
    for module in ('canvas-core', 'shared-core'):
        for source in ('commonMain', 'iosMain'):
            paths.update((ROOT / module / 'src' / source).rglob('*.kt'))
    paths.update(ROOT / p for p in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'canvas-core/build.gradle.kts', 'shared-core/build.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties',
        'gradle/wrapper/gradle-wrapper.jar', 'gradlew', 'tools/verify_c1_apple_failure_channels.py',
        'tools/probes/C1AppleFailureConsumer.swift', 'ci/verify_apple_sdk.py', 'ci/gradle_download_retry.py', 'ci/apple_runtime.py', 'ci/test_apple_runtime.py',
        'tools/audit_sdk_type_closure.py', 'tools/audit_public_boundary.py',
        '.github/workflows/apple-sdk.yml', '.github/workflows/ios17.yml', 'ci/prepare_ios17.py'))
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(paths)}


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert platform.system() == 'Darwin' and platform.machine() == 'arm64'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    before = inputs()
    report = {'status': 'running', 'source_sha256': before, 'sdk_candidate': True,
              'c1_complete': False, 'physical_device_verified': False, 'production_sdk_frozen': False}
    try:
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        assert commit == os.environ['GITHUB_SHA']
        report.update(commit=commit, run_id=os.environ['GITHUB_RUN_ID'], stage='sdk-build')
        run_gradle(['./gradlew', '--no-daemon', '--console=plain', '-PenableAppleTargets=true',
            '-PsdkCandidate=true', ':shared-core:linkReleaseFrameworkIosSimulatorArm64',
            ':shared-core:linkReleaseFrameworkIosArm64'], ROOT, OUTPUT / 'sdk-build.log', 1800)
        directory = ROOT / 'build/sdk-candidate/shared-core/bin'
        pin = json.loads((ROOT / 'reports/c1-pinned-sdk-20261117.json').read_text(encoding='utf-8'))
        pin['candidate_source_commit'] = commit
        for target in ('iosSimulatorArm64', 'iosArm64'):
            framework = directory / target / 'releaseFramework/NoteCanvasPrototype.framework'
            (OUTPUT / (target + '.h')).write_bytes((framework / 'Headers/NoteCanvasPrototype.h').read_bytes())
            text = (framework / 'Headers/NoteCanvasPrototype.h').read_text(encoding='utf-8')
            names = {item['objc_name'] for item in inspect_header(text)}
            forbidden = {'NCP' + name for name in ('PreviewSaveLedger', 'BoundedOwnerExecutor',
                'DocumentCloseCoordinator', 'ResourceLeasePool', 'SubmissionQueue', 'BrushRenderOwnerPoc',
                'BrushTileCachePoc', 'WeightedLruCache', 'PreviewStreamReader', 'ApplePreviewTextCodec',
                'PreviewAppRecoveryPreflight')}
            assert not names & forbidden, ('Excluded SDK implementation exported', names & forbidden)
            closure = audit(text, current_candidate=True)
            (OUTPUT / (target + '-type-closure.json')).write_text(json.dumps(closure, indent=2) + '\n', encoding='utf-8', newline='\n')
            pin['apple']['targets'][target] = {'framework_sha256': sha(framework / 'NoteCanvasPrototype'),
                'header_sha256': sha(framework / 'Headers/NoteCanvasPrototype.h')}
        # Reuse only the pinned synthetic fixture identities, never historical artifact hashes/results.
        descriptor = {'candidate_source_commit': commit, 'apple': {
            'targets': pin['apple']['targets'], 'fixture_sha256': pin['apple']['fixture_sha256']}}
        pin_path = OUTPUT / 'current-sdk.json'
        pin_path.write_text(json.dumps(descriptor, indent=2) + '\n', encoding='utf-8', newline='\n')
        devices = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'available', '--json']))['devices']
        runtimes = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'runtimes', '--json']))['runtimes']
        required = os.environ.get('NOTECORE_REQUIRED_IOS_RUNTIME')
        runtime, device = select_runtime(devices, runtimes, required)
        report.update(required_runtime=required, selected_runtime=runtime)
        report['stage'] = 'independent-consumer'
        verify(directory, runtime, device['deviceTypeIdentifier'], OUTPUT / 'consumer', pin_path, True)
        consumer = json.loads((OUTPUT / 'consumer/verification.json').read_text(encoding='utf-8'))
        assert consumer['runtime']['identifier'] == runtime
        report['ios17_runtime_verified'] = consumer['ios17_runtime_verified']
        assert consumer['selected_typed_channels_and_recovery']['tool_commands_verified'] is True
        assert consumer['selected_typed_channels_and_recovery']['point_factory_invalid_values_refused'] is True
        assert inputs() == before, 'Source changed during SDK verification'
        report.update(status='passed', stage='complete')
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        report['artifact_sha256'] = {p.relative_to(OUTPUT).as_posix(): sha(p) for p in OUTPUT.rglob('*')
                                    if p.is_file() and p != OUTPUT / 'report.json'}
        (OUTPUT / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
