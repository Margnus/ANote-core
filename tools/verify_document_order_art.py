#!/usr/bin/env python3
"""Run the affected original document suites on the authorized independent API35 AVD."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
SUITES = ('ObjectOrderRulesTest', 'ObjectOrderAuthorityTest', 'DocumentHistoryTest',
          'DocumentAuthorityTest', 'RetiredEditingStateTest')
PACKAGE = 'dev.notecore.document.poc.test'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    args = parser.parse_args()
    def adb(*command):
        return subprocess.run([args.adb, '-s', 'emulator-5590', *command], cwd=ROOT,
                              capture_output=True, check=True, timeout=120).stdout
    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name').strip() == b'NoteCoreCurrent35'
    assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == b'35'
    def notes():
        data = adb('exec-out', 'run-as', 'dev.notecore.preview', 'tar', 'cf', '-', 'files/preview-notes')
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            return {entry.name: sha(archive.extractfile(entry).read()) for entry in archive.getmembers() if entry.isfile()}
    before = notes()
    paths = set()
    for module in ('canvas-core', 'shared-core'):
        for source_set in ('commonMain', 'commonTest', 'jvmAndAndroidMain', 'androidMain', 'androidInstrumentedTest'):
            paths.update((ROOT/module/'src'/source_set).rglob('*.kt'))
    paths.update(ROOT/p for p in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'canvas-core/build.gradle.kts', 'shared-core/build.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties',
        'gradle/wrapper/gradle-wrapper.jar', 'tools/verify_document_order_art.py'))
    source = {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in sorted(paths)}
    with (ROOT/'build/document-order-art-build.log').open('w', encoding='utf-8') as log:
        subprocess.run([str(ROOT/'gradlew.bat'), '-PenableAndroidTargets=true',
            ':shared-core:assembleDebugAndroidTest', '--console=plain'], cwd=ROOT,
            stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
    metadata = ROOT/'shared-core/build/outputs/apk/androidTest/debug/output-metadata.json'
    info = json.loads(metadata.read_text(encoding='utf-8'))
    assert info['applicationId'] == PACKAGE and len(info['elements']) == 1
    apk = metadata.parent/info['elements'][0]['outputFile']
    apk_hash = sha(apk.read_bytes())
    out = ROOT/'reports/object-order-art'
    out.mkdir(exist_ok=True)
    (out/'install.log').write_bytes(adb('install', '-r', str(apk)))
    remote = adb('shell', 'pm', 'path', PACKAGE).decode().strip()
    assert remote.startswith('package:') and '\n' not in remote
    assert sha(adb('exec-out', 'cat', remote[len('package:'):])) == apk_hash
    raw = adb('shell', 'am', 'instrument', '-w', '-r', PACKAGE+'/dev.notecore.document.DocumentOrderInstrumentation')
    (out/'run.log').write_bytes(raw)
    after = notes()
    counts = {name: len(re.findall(r'@Test\s+fun\s+\w+\s*\(',
        (ROOT/'shared-core/src/commonTest/kotlin/dev/notecore/document'/f'{name}.kt').read_text(encoding='utf-8'))) for name in SUITES}
    output = raw.decode('utf-8')
    assert 'document_status=passed' in output and f'document_tests={sum(counts.values())}' in output and 'document_ignored=0' in output, output
    assert before == after and len(before) == 5
    assert source == {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in sorted(paths)}
    assert apk_hash == sha(apk.read_bytes())
    build_log = ROOT/'build/document-order-art-build.log'
    (out/'build.log').write_bytes(build_log.read_bytes())
    report = {'status': 'passed', 'platform': 'API35 AOSP x86_64 ART independent library-test APK',
        'avd': 'NoteCoreCurrent35', 'serial': 'emulator-5590', 'suites': counts,
        'source_sha256': source, 'source_binding': 'raw local build inputs, checked unchanged across install/run; no Git EOL equivalence claim',
        'apk_sha256': apk_hash, 'installed_apk_sha256_verified': True,
        'preview_notes_before': before, 'preview_notes_after': after,
        'evidence_sha256': {p.name: sha(p.read_bytes()) for p in out.iterdir() if p.name != 'report.json'},
        'scope': 'Original affected common model suites on ART; no preview UI integration, production storage, physical performance or C1 acceptance',
        'physical_m1_passed': False, 'c1_passed': False, 'requirement_accepted': False}
    (out/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': report['status'], 'suites': counts, 'total': sum(counts.values()), 'notes_preserved': len(before)}))


if __name__ == '__main__':
    main()
