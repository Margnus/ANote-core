"""Current Android candidate build and original external-consumer checks on isolated API 26 ART."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from gradle_download_retry import run_gradle

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/android-sdk-ci'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    paths = set()
    for module in ('canvas-core', 'shared-core'):
        for source in ('commonMain', 'androidMain', 'jvmMain', 'jvmAndAndroidMain'):
            paths.update((ROOT / module / 'src' / source).rglob('*.kt'))
    paths.update(ROOT / name for name in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'canvas-core/build.gradle.kts', 'shared-core/build.gradle.kts', 'gradlew',
        'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
        'ci/verify_android_sdk.py', 'ci/gradle_download_retry.py', '.github/workflows/android-sdk.yml',
        'tools/verify_android_sdk_candidate.py', 'tools/probes/C1AndroidFailureConsumer.java'))
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(paths)}


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    before = inputs()
    report = {'status': 'running', 'source_sha256': before, 'sdk_candidate': True,
              'c1_complete': False, 'physical_device_verified': False, 'production_sdk_frozen': False}
    try:
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        assert commit == os.environ['GITHUB_SHA']
        report.update(commit=commit, run_id=os.environ['GITHUB_RUN_ID'], stage='publish-isolated-candidate')
        run_gradle(['./gradlew', '--no-daemon', '--console=plain', '-PenableAndroidTargets=true',
            '-PsdkCandidate=true', ':canvas-core:publishAllPublicationsToSdkCandidateRepository',
            ':shared-core:publishAllPublicationsToSdkCandidateRepository'], ROOT, OUTPUT / 'build.log', 1200)
        image = Path(os.environ['ANDROID_HOME']) / 'system-images/android-26/default/x86_64'
        assert (image / 'system.img').is_file(), 'API26 image is missing'
        report['stage'] = 'independent-consumers-api26'
        with (OUTPUT / 'verify.log').open('w', encoding='utf-8', newline='\n') as log:
            subprocess.run([sys.executable, str(ROOT / 'tools/verify_android_sdk_candidate.py'),
                '--image', str(image), '--port', '5588', '--output', str(OUTPUT / 'consumer'),
                '--report', str(OUTPUT / 'candidate.json')], cwd=ROOT, stdout=log,
                stderr=subprocess.STDOUT, timeout=1200, check=True)
        candidate = json.loads((OUTPUT / 'candidate.json').read_text(encoding='utf-8'))
        assert candidate['status'] == 'passed' and candidate['art']['minimum_api26_verified']
        assert candidate['candidate_source_commit'] == commit and inputs() == before
        published = OUTPUT / 'published'
        published.mkdir()
        for aar, expected in candidate['android_aars_sha256'].items():
            matches = list((ROOT / 'build/sdk-candidate-repository').rglob(aar))
            assert len(matches) == 1 and sha(matches[0]) == expected
            shutil.copyfile(matches[0], published / aar)
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
