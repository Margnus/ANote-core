"""Collect actual simulator PDF metadata/rendering; not a pixel or product acceptance gate."""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/apple-pdf'
NAMES = ('crop-rotations.pdf', 'inherited-geometry.pdf', 'user-unit-2.pdf')
SOURCE = 'tools/probes/PdfCapabilityProbe.swift'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert platform.system() == 'Darwin' and platform.machine() == 'arm64'
    OUTPUT.mkdir(parents=True, exist_ok=True)
    inputs = [SOURCE, 'ci/collect_apple_pdf.py', '.github/workflows/apple-pdf.yml',
              'fixtures/pdf/recipe.json', 'android-app/src/androidTest/assets/pdf/metadata-manifest.json']
    inputs += ['android-app/src/androidTest/assets/pdf/' + name for name in NAMES]
    before = {p: sha(ROOT / p) for p in inputs}
    report = {'status': 'running', 'stage': 'environment', 'source_sha256': before,
              'pixel_verification_complete': False, 'app_integration': False, 'c1_complete': False}

    def capture(*args, timeout=120):
        return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding='utf-8',
                              timeout=timeout, check=True).stdout

    def run(label, args, timeout):
        print(label, flush=True)
        path = OUTPUT / (label + '.log')
        with path.open('w', encoding='utf-8', newline='\n') as log:
            subprocess.run(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=timeout)
        return path.read_text(encoding='utf-8')

    try:
        report.update(commit=capture('git', 'rev-parse', 'HEAD').strip(), run_id=os.environ['GITHUB_RUN_ID'],
                      xcode=capture('xcodebuild', '-version').strip(), os=capture('sw_vers').strip())
        assert report['commit'] == os.environ['GITHUB_SHA']
        report['stage'] = 'compile'
        binary = OUTPUT / 'PdfCapabilityProbe'
        run('compile', ['xcrun', '--sdk', 'iphonesimulator', 'swiftc', '-target',
            'arm64-apple-ios17.0-simulator', SOURCE, '-framework', 'CoreGraphics', '-framework', 'ImageIO',
            '-framework', 'PDFKit', '-framework', 'UniformTypeIdentifiers', '-o', str(binary)], 180)
        report['binary_sha256'] = sha(binary)
        report['binary_build_version'] = capture('xcrun', 'vtool', '-show-build', str(binary))
        assert 'IOSSIMULATOR' in report['binary_build_version']
        report['stage'] = 'boot'
        devices = json.loads(capture('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))['devices']
        choices = [(rt, d) for rt, group in devices.items() if '.iOS-' in rt for d in group
                   if d.get('isAvailable') and d.get('deviceTypeIdentifier')]
        runtime, template = next((pair for pair in choices if 'iPad' in pair[1]['name']), choices[0])
        name = 'NoteCorePDF-' + uuid.uuid4().hex[:12]
        device = capture('xcrun', 'simctl', 'create', name, template['deviceTypeIdentifier'], runtime).strip()
        report['device'] = {'id': device, 'name': name, 'runtime': runtime, 'type': template['deviceTypeIdentifier']}
        capture('xcrun', 'simctl', 'boot', device)
        run('boot', ['xcrun', 'simctl', 'bootstatus', device, '-b'], 240)
        root = ROOT / ('build/pdf-capability-' + uuid.uuid4().hex)
        root.mkdir()
        for name in NAMES:
            shutil.copyfile(ROOT / 'android-app/src/androidTest/assets/pdf' / name, root / name)
        report['stage'] = 'native_observations'
        text = run('metadata', ['xcrun', 'simctl', 'spawn', device, str(binary), str(root), '--metadata'], 120)
        lines = [line.removeprefix('NOTECORE_PDF_METADATA ') for line in text.splitlines()
                 if line.startswith('NOTECORE_PDF_METADATA ')]
        assert len(lines) == 1
        report['observations'] = json.loads(lines[0])
        for name in NAMES:
            assert sha(root / name) == before['android-app/src/androidTest/assets/pdf/' + name]
        for path in root.glob('*.png'):
            shutil.copyfile(path, OUTPUT / path.name)
        assert len(list(OUTPUT.glob('*.png'))) == 16
        assert before == {p: sha(ROOT / p) for p in inputs}
        report.update(status='collected_pending_pixel_verification', stage='complete')
    except BaseException as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        report['evidence_sha256'] = {p.name: sha(p) for p in OUTPUT.iterdir()
                                     if p.is_file() and p.name not in ('report.json', 'PdfCapabilityProbe')}
        (OUTPUT / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
