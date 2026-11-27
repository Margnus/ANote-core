"""Compare Android's public page-link API against a document outline and a positive control."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

import pypdf
from pypdf import PdfReader, PdfWriter
from pypdf.annotations import Link
from pypdf.generic import ArrayObject, DictionaryObject, NameObject, NumberObject

from generate_pdf_outline_fixture import OUTPUT, RECIPE, read_outline


PROBE = Path(__file__).resolve().parent / 'probes/AndroidPdfOutlineProbe.java'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-') or not args.serial.removeprefix('emulator-').isdigit():
        parser.error('--serial must identify a dedicated Android emulator')

    def run(*command, binary=False):
        result = subprocess.run(command, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError((result.args, result.returncode, result.stdout, result.stderr))
        return result.stdout if binary else result.stdout.decode('utf-8').strip()

    suffix = '.exe' if os.name == 'nt' else ''
    sdk_jar = args.sdk / 'platforms/android-35/android.jar'
    d8_jar = args.sdk / 'build-tools/35.0.0/lib/d8.jar'

    def adb(*command, binary=False):
        return run(args.sdk / f'platform-tools/adb{suffix}', '-s', args.serial, *command, binary=binary)

    runtime = {field: adb('shell', 'getprop', key) for field, key in {
        'api': 'ro.build.version.sdk', 'fingerprint': 'ro.build.fingerprint',
        'abi': 'ro.product.cpu.abi',
    }.items()}
    runtime['avd'] = adb('emu', 'avd', 'name').splitlines()[0]
    if runtime['avd'] != 'NoteCorePdfOutline35' or int(runtime['api']) != 35:
        raise ValueError(f'Expected dedicated NoteCorePdfOutline35 AVD at API 35, got {runtime}')

    args.output.mkdir(parents=True, exist_ok=False)
    control = args.output / 'outline-with-page-link.pdf'
    original = PdfReader(OUTPUT)
    expected_outline = json.loads(RECIPE.read_text(encoding='utf-8'))
    assert read_outline(original) == expected_outline
    assert all(not page.get('/Annots') for page in original.pages)
    writer = PdfWriter(clone_from=original)
    link = writer.add_annotation(0, Link(rect=(70, 90, 100, 120), target_page_index=2))
    del link['/Dest']
    link[NameObject('/A')] = DictionaryObject({
        NameObject('/S'): NameObject('/GoTo'),
        NameObject('/D'): ArrayObject([writer.pages[2].indirect_reference, NameObject('/XYZ'),
                                     NumberObject(70), NumberObject(120), NumberObject(1)]),
    })
    writer.write(control)
    fixture = PdfReader(control)
    assert read_outline(fixture) == expected_outline
    assert [len(page.get('/Annots', [])) for page in fixture.pages] == [1, 0, 0, 0]
    assert fixture.pages[0]['/Annots'][0].get_object()['/A']['/D'][0] == fixture.pages[2].indirect_reference
    for before, after in zip(original.pages, fixture.pages, strict=True):
        assert before.get_contents().get_data() == after.get_contents().get_data()
        assert before.rotation == after.rotation and list(before.cropbox) == list(after.cropbox)

    classes = args.output / 'classes'
    dex = args.output / 'probe.zip'
    run(args.jdk / f'bin/javac{suffix}', '--release', '8', '-cp', sdk_jar, '-d', classes, PROBE)
    run(args.jdk / f'bin/java{suffix}', '-cp', d8_jar, 'com.android.tools.r8.D8', '--min-api', '35',
        '--lib', sdk_jar, '--output', dex, *sorted(classes.rglob('*.class')))

    def sha(data):
        return hashlib.sha256(data).hexdigest()

    inputs = {'outline-only.pdf': OUTPUT, control.name: control, dex.name: dex}
    digests = {name: sha(path.read_bytes()) for name, path in inputs.items()}
    remote = '/data/local/tmp/notecore-pdf-outline-' + uuid.uuid4().hex
    adb('shell', 'mkdir', remote)
    try:
        for name, path in inputs.items():
            adb('push', str(path), f'{remote}/{name}')
        observed = json.loads(adb('shell', 'env', f'CLASSPATH={remote}/{dex.name}', 'app_process',
                                  '/system/bin', 'dev.notecore.probes.AndroidPdfOutlineProbe', remote))
        for name, digest in digests.items():
            assert sha(adb('exec-out', 'cat', f'{remote}/{name}', binary=True)) == digest
    finally:
        adb('shell', 'rm', '-f', *(f'{remote}/{name}' for name in inputs))
        adb('shell', 'rmdir', remote)

    assert observed == {'outline_only': [[], [], [], []], 'outline_with_page_link': [[2], [], [], []]}, observed
    result = {'runtime': runtime, 'pypdf_version': pypdf.__version__, 'input_sha256': digests,
              'probe_source_sha256': sha(PROBE.read_bytes()), 'runner_source_sha256': sha(Path(__file__).read_bytes()),
              'expected_document_outline': expected_outline, 'page_link_destinations': observed,
              'scope': 'Android 35 PdfRenderer.Page.getGotoLinks and same-outline positive control'}
    (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
