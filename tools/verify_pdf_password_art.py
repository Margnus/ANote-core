"""Run the original system-PDF probe on the authorized persistent API35 AVD, without App replacement."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import uuid
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'dev.notecore.preview'
ASSETS = ROOT / 'android-app/src/androidTest/assets/pdf'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    out = ROOT / args.output
    out.mkdir(parents=True, exist_ok=False)

    def adb(*command):
        return subprocess.run([args.adb, '-s', 'emulator-5590', *command], cwd=ROOT,
                              capture_output=True, check=True, timeout=180).stdout

    assert adb('shell', 'getprop', 'ro.boot.qemu.avd_name').strip() == b'NoteCoreCurrent35'
    assert adb('shell', 'getprop', 'ro.build.version.sdk').strip() == b'35'

    def notes():
        data = adb('exec-out', 'run-as', PACKAGE, 'tar', 'cf', '-', 'files/preview-notes')
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            return {p.name: sha(archive.extractfile(p).read()) for p in archive.getmembers() if p.isfile()}

    def installed(package):
        path = adb('shell', 'pm', 'path', package).decode().strip()
        assert path.startswith('package:') and '\n' not in path
        return path.removeprefix('package:')

    paths = ['android-app/src/androidTest/java/dev/notecore/app/probes/PdfCapabilityProbe.java',
             'android-app/src/androidTest/assets/pdf/crop-rotations.pdf',
             'android-app/src/androidTest/assets/pdf/password-aes256.pdf',
             'android-app/src/androidTest/assets/pdf/password-manifest.json', 'fixtures/pdf/recipe.json',
             'tools/verify_pdf_password_art.py', 'tools/generate_pdf_password_fixture.py',
             'android-app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties']
    source = {p: sha((ROOT / p).read_bytes()) for p in paths}
    before = notes()
    preview_hash = sha(adb('exec-out', 'cat', installed(PACKAGE)))
    apk = ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    apk_hash = sha(apk.read_bytes())
    (out / 'install.log').write_bytes(adb('install', '-r', str(apk)))
    remote = installed(PACKAGE + '.test')
    assert sha(adb('exec-out', 'cat', remote)) == apk_hash
    nonce = uuid.uuid4().hex
    cache = 'cache/pdf-capability-' + nonce
    adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', cache)
    for name in ('crop-rotations.pdf', 'password-aes256.pdf'):
        temp = '/data/local/tmp/' + nonce + '-' + name
        adb('push', str(ASSETS / name), temp)
        adb('shell', 'run-as', PACKAGE, 'cp', temp, cache + '/' + name)
    raw = adb('shell', 'run-as', PACKAGE, '/system/bin/env', 'CLASSPATH=' + remote,
              '/system/bin/app_process', '/system/bin', 'dev.notecore.app.probes.PdfCapabilityProbe', cache, '--password')
    (out / 'run.log').write_bytes(raw)
    lines = [line.removeprefix('NOTECORE_PDF_PASSWORD ') for line in raw.decode().splitlines()
             if line.startswith('NOTECORE_PDF_PASSWORD ')]
    assert len(lines) == 1
    observed = json.loads(lines[0])
    assert observed['missing_refused'] and observed['wrong_refused'] and observed['correct_rendered']
    recipe = json.loads((ROOT / 'fixtures/pdf/recipe.json').read_text(encoding='utf-8'))
    assert len(observed['pages']) == len(recipe['expected_pages']) == 4
    for index, page in enumerate(observed['pages']):
        expected = recipe['expected_pages'][index]
        assert page['index'] == index and page['pixels_equal']
        for kind in ('plain', 'aes256'):
            name = f'password-{kind}-{index}.png'
            (out / name).write_bytes(adb('exec-out', 'run-as', PACKAGE, 'cat', cache + '/' + name))
            image = Image.open(out / name).convert('RGBA')
            assert image.size == (expected['width'], expected['height'])
            for xy, rgb in zip(expected['marker_xy'], recipe['marker_rgb']):
                pixel = image.getpixel(tuple(xy))
                assert pixel[3] == 255 and max(abs(pixel[i] - rgb[i]) for i in range(3)) <= 2
        assert Image.open(out / f'password-plain-{index}.png').tobytes() == Image.open(out / f'password-aes256-{index}.png').tobytes()
    for name in ('crop-rotations.pdf', 'password-aes256.pdf'):
        assert sha(adb('exec-out', 'run-as', PACKAGE, 'cat', cache + '/' + name)) == sha((ASSETS / name).read_bytes())
    after = notes()
    assert before == after and preview_hash == sha(adb('exec-out', 'cat', installed(PACKAGE)))
    assert source == {p: sha((ROOT / p).read_bytes()) for p in paths}
    report = {'status': 'passed', 'platform': 'Actual API35 ART system PdfRenderer, original app_process probe',
              'source_sha256': source, 'observations': observed, 'test_apk_sha256': apk_hash,
              'installed_test_apk_verified': True, 'preview_apk_sha256_unchanged': preview_hash,
              'notes_before': before, 'notes_after': after,
              'independent_color_anchors_checked': 32, 'source_files_unchanged': True,
              'evidence_sha256': {p.name: sha(p.read_bytes()) for p in out.iterdir()},
              'scope': 'One synthetic AES-256 R6 PDF: missing/wrong password refusal, correct four-page rendering identical to same-engine plaintext and independent color anchors. No password UI/persistence, App PDF or production resource/task acceptance.',
              'physical_verified': False, 'c1_complete': False, 'requirement_accepted': False}
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': report['status'], 'pages': 4, 'anchors': 32, 'notes_preserved': len(before)}))


if __name__ == '__main__':
    main()
