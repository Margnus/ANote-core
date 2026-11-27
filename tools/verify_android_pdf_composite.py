"""Verify actual Android PDF writing, reopening and pre-publication cancellation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

from PIL import Image
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android-app/src/androidTest/assets/pdf/crop-rotations.pdf'
RECIPE = ROOT / 'fixtures/pdf/recipe.json'
PROBE = ROOT / 'tools/probes/AndroidPdfCompositeProbe.java'
SELECTION = (3, None, 0)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_plan(plan_bytes, source_digest, recipe):
    plan = json.loads(plan_bytes)
    assert plan['source_sha256'] == source_digest
    assert plan['source_page_count'] == len(recipe['expected_pages'])
    assert [quality['scale'] for quality in plan['qualities']] == [1, 2]
    for quality in plan['qualities']:
        tasks = quality['tasks']
        assert [task['page_id'] for task in tasks] == ['rotated', 'blank', 'first']
        assert [task['source_index'] for task in tasks] == list(SELECTION)
        assert [task['display_number'] for task in tasks] == [1, 2, 4]
        assert [task['output_number'] for task in tasks] == [1, 2, 3]
        for task in tasks:
            index = task['source_index']
            source = recipe['expected_pages'][index] if index is not None else None
            width, height = (source['width'], source['height']) if source else (200, 120)
            assert (task['page_width'], task['page_height']) == (width, height)
            scale = quality['scale']
            assert (task['pixel_width'], task['pixel_height']) == (width * scale, height * scale)
            assert task['argb_bytes'] == width * height * scale * scale * 4
        assert quality['retained_argb_bytes'] == sum(task['argb_bytes'] for task in tasks)
    return plan


def verify_file(folder, scale, recipe):
    name = f'quality-{scale}.pdf'
    reader = PdfReader(folder / name)
    positions = []
    assert len(reader.pages) == len(SELECTION)
    for index, (page, source_index) in enumerate(zip(reader.pages, SELECTION, strict=True)):
        source = recipe['expected_pages'][source_index] if source_index is not None else None
        width, height = (source['width'], source['height']) if source else (200, 120)
        assert tuple(page.mediabox) == (0, 0, width, height) and page.rotation == 0
        assert not page.extract_text().strip() and not page.get('/Annots')
        raster, = page.images
        assert raster.image.size == (width * scale, height * scale)
        anchors = [([55, 55], [0, 255, 255])]
        anchors += list(zip(source['marker_xy'], recipe['marker_rgb'])) if source else [([16, 16], [255, 255, 255])]
        with Image.open(folder / f'{name}.{index}.png') as reopened:
            image = reopened.convert('RGB')
            assert image.size == (width, height)
            for xy, expected in anchors:
                actual = image.getpixel(tuple(xy))
                assert max(abs(a - b) for a, b in zip(actual, expected)) <= 2, (name, index, xy, actual, expected)
        positions.append({'source_index': source_index, 'page_points': [width, height],
                          'image_pixels': list(raster.image.size), 'matched_pixel_anchors': len(anchors)})
    return positions


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--plan', type=Path, default=ROOT / 'build/pdf-composite-plan.json')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-') or not args.serial.removeprefix('emulator-').isdigit():
        parser.error('--serial must identify a dedicated Android emulator')

    def run(*command, binary=False):
        result = subprocess.run(command, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError((result.args, result.returncode, result.stdout, result.stderr))
        return result.stdout if binary else result.stdout.decode('utf-8').strip()

    suffix = '.exe' if os.name == 'nt' else ''

    def adb(*command, binary=False):
        return run(args.sdk / f'platform-tools/adb{suffix}', '-s', args.serial, *command, binary=binary)

    runtime = {name: adb('shell', 'getprop', prop) for name, prop in {
        'api': 'ro.build.version.sdk', 'fingerprint': 'ro.build.fingerprint', 'abi': 'ro.product.cpu.abi',
    }.items()}
    runtime['avd'] = adb('emu', 'avd', 'name').splitlines()[0]
    if runtime['avd'] != 'NoteCorePdfExport35' or runtime['api'] != '35':
        raise ValueError(f'Expected the dedicated API 35 export emulator, got {runtime}')

    folder = args.output
    folder.mkdir(parents=True, exist_ok=False)
    classes = folder / 'classes'
    dex = folder / 'probe.zip'
    sdk_jar = args.sdk / 'platforms/android-35/android.jar'
    d8_jar = args.sdk / 'build-tools/35.0.0/lib/d8.jar'
    run(args.jdk / f'bin/javac{suffix}', '--release', '8', '-cp', sdk_jar, '-d', classes, PROBE)
    run(args.jdk / f'bin/java{suffix}', '-cp', d8_jar, 'com.android.tools.r8.D8', '--min-api', '35',
        '--lib', sdk_jar, '--output', dex, *sorted(classes.rglob('*.class')))

    inputs = {'source.pdf': SOURCE, 'composite-plan.json': args.plan, dex.name: dex}
    input_digests = {name: sha(path.read_bytes()) for name, path in inputs.items()}
    recipe = json.loads(RECIPE.read_bytes())
    plan = verify_plan(args.plan.read_bytes(), input_digests['source.pdf'], recipe)
    names = [name for scale in (1, 2) for name in
             [f'quality-{scale}.pdf', *(f'quality-{scale}.pdf.{index}.png' for index in range(len(SELECTION)))] ]
    remote = '/data/local/tmp/notecore-pdf-export-' + uuid.uuid4().hex
    adb('shell', 'mkdir', remote)
    try:
        for name, path in inputs.items():
            adb('push', str(path), f'{remote}/{name}')
        native = adb('shell', 'env', f'CLASSPATH={remote}/{dex.name}', 'app_process', '/system/bin',
                     'dev.notecore.probes.AndroidPdfCompositeProbe', remote)
        (folder / 'native.json').write_text(native + '\n', encoding='utf-8')
        observed = json.loads(native)
        for name, digest in input_digests.items():
            assert sha(adb('exec-out', 'cat', f'{remote}/{name}', binary=True)) == digest
        for name in names:
            (folder / name).write_bytes(adb('exec-out', 'cat', f'{remote}/{name}', binary=True))
    finally:
        adb('shell', 'rm', '-f', *(f'{remote}/{name}' for name in (*inputs, *names)))
        adb('shell', 'rmdir', remote)

    assert observed['cancelled_before_publish'] is True
    assert observed['plan_sha256'] == input_digests['composite-plan.json']
    assert sha(SOURCE.read_bytes()) == input_digests['source.pdf']
    result = {}
    for quality in plan['qualities']:
        scale = quality['scale']
        rows = verify_file(folder, scale, recipe)
        native = observed[f'quality_{scale}']
        ids = [task['page_id'] for task in quality['tasks']]
        assert native['page_ids'] == ids
        assert native['pages'] == [row['page_points'] for row in rows]
        packed_bytes = sum(row['image_pixels'][0] * row['image_pixels'][1] * 4 for row in rows)
        assert native['retained_bitmap_bytes'] == packed_bytes == quality['retained_argb_bytes']
        result[str(scale)] = {'pages': rows, 'page_ids': ids, 'retained_bitmap_bytes': packed_bytes}
    report = {'status': 'passed', 'runtime': runtime, 'input_sha256': input_digests,
              'source_sha256': {str(path.relative_to(ROOT).as_posix()): sha(path.read_bytes())
                                for path in (PROBE, Path(__file__).resolve(), RECIPE)},
              'output_sha256': {name: sha((folder / name).read_bytes()) for name in (*names, 'native.json')},
              'qualities': result, 'cancelled_before_publish': True,
              'scope': 'Android 35 isolated native raster PDF export, no App or aggregate native heap claim'}
    (folder / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': report['status'], 'runtime': runtime, 'qualities': result}))


if __name__ == '__main__':
    main()
