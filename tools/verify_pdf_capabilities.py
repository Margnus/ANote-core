#!/usr/bin/env python3
"""Check synthetic PDF geometry/capability evidence on actual Android and iOS Simulator."""
import argparse
import hashlib
import json
import re
import shlex
import shutil
import subprocess
import uuid
from pathlib import Path
from PIL import Image
from verify_sqlite_capabilities import run, sha, DEVICE, BUNDLE

ROOT = Path(__file__).resolve().parents[1]
JAVA = 'android-app/src/androidTest/java/dev/notecore/app/probes/PdfCapabilityProbe.java'
SWIFT = 'tools/probes/PdfCapabilityProbe.swift'
NAMES = ('crop-rotations.pdf','password.pdf','many-pages.pdf','malformed.pdf')
IMAGES = [f'page-{i}.png' for i in range(4)]+['tile.png','tile-gutter.png','password.png','large-0.png','large-499.png']


def parse(result):
    lines = [line.split('NOTECORE_PDF ',1)[1] for line in result.stdout.splitlines() if line.startswith('NOTECORE_PDF ')]
    assert result.returncode == 0 and len(lines)==1, (result.returncode,result.stdout,result.stderr)
    return json.loads(lines[0])


def verify_pixels(folder, observed, recipe):
    for index,expected in enumerate(recipe['expected_pages']):
        actual = observed['pages'][index]
        assert (actual['width'],actual['height']) == (expected['width'],expected['height']), actual
        image = Image.open(folder/f'page-{index}.png').convert('RGBA')
        assert image.size == (expected['width'],expected['height'])
        for xy,color in zip(expected['marker_xy'],recipe['marker_rgb']):
            pixel = image.getpixel(tuple(xy))
            assert max(abs(pixel[i]-color[i]) for i in range(3))<=2 and pixel[3]==255, (index,xy,pixel,color)
    full = Image.open(folder/'page-0.png').convert('RGB')
    plain = Image.open(folder/'tile.png').convert('RGB')
    guarded = Image.open(folder/'tile-gutter.png').convert('RGB').crop((4,4,104,84))
    reference = full.crop((50,40,150,120))
    def delta(image):
        assert image.size == reference.size
        return max(abs(a-b) for a,b in zip(image.tobytes(),reference.tobytes()))
    raw_delta,guarded_delta = delta(plain),delta(guarded)
    assert raw_delta == observed['pages'][0]['tile_max_channel_delta']
    assert guarded_delta == observed['pages'][0]['tile_with_gutter_max_channel_delta'] == 0
    encrypted = Image.open(folder/'password.png').convert('RGB')
    assert encrypted.size == full.size
    for xy,color in zip(recipe['expected_pages'][0]['marker_xy'],recipe['marker_rgb']):
        assert max(abs(a-b) for a,b in zip(encrypted.getpixel(tuple(xy)),color))<=2
    for flag in ('password_missing_refused','password_wrong_refused','password_correct_rendered','malformed_refused','invalid_page_refused'):
        assert observed[flag] is True
    assert observed['large_page_count']==500 and observed['large_render_requests']==[0,499]
    assert observed['in_progress_render_cancellation_verified'] is False
    return {'all_rotation_color_anchors_matched':True,'tile_raw_max_channel_delta':raw_delta,
            'tile_4px_gutter_core_max_channel_delta':guarded_delta,'password_color_anchors_matched':True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial',required=True);parser.add_argument('--adb',required=True);parser.add_argument('--device',required=True)
    args = parser.parse_args()
    assert args.serial=='emulator-5580' and args.device==DEVICE
    recipe = json.loads((ROOT/'fixtures/pdf/recipe.json').read_text(encoding='utf-8'))
    manifest = json.loads((ROOT/'output/pdf/manifest.json').read_text(encoding='utf-8'))
    assert set(manifest['fixture_sha256'])=={'output/pdf/'+name for name in NAMES}
    assert manifest['recipe_sha256']==sha(ROOT/'fixtures/pdf/recipe.json') and manifest['generator_sha256']==sha(ROOT/'tools/generate_pdf_fixtures.py')
    for relative,digest in manifest['fixture_sha256'].items():assert sha(ROOT/relative)==digest
    def adb(*command):
        result = run([args.adb,'-s',args.serial,*command]);assert result.returncode==0,result.stderr
        return result.stdout.strip()
    def raw_adb(*command):return subprocess.run([args.adb,'-s',args.serial,*command],capture_output=True,check=True,timeout=30).stdout
    def android_files():
        output = adb('shell','run-as '+BUNDLE+' sh -c '+shlex.quote('sha256sum files/preview-notes/*.ncp'))
        return dict((name,digest) for digest,name in (line.split(None,1) for line in output.splitlines()))
    android_before = android_files()
    container = Path(run(['xcrun','simctl','get_app_container',DEVICE,BUNDLE,'data'],check=True).stdout.strip())
    def apple_files():return {str(p.relative_to(container)):sha(p) for p in container.rglob('*.ncp') if p.is_file()}
    apple_before = apple_files()
    stamp = dict(line.split('  ',1)[::-1] for line in (ROOT/'reports/android-brush-build-inputs.sha256').read_text(encoding='utf-8').splitlines())
    assert JAVA in stamp
    for relative,digest in stamp.items():assert sha(ROOT/relative)==digest,('stale probe build',relative)
    test_apk = ROOT/'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'
    adb('install','-r',str(test_apk))
    installed = [adb('shell','pm','path',package).removeprefix('package:') for package in (BUNDLE,BUNDLE+'.test')]
    assert all(re.fullmatch(r'/data/app/[a-zA-Z0-9_./=+~:-]+/base.apk',path) for path in installed)
    for remote,artifact in zip(installed,(ROOT/'dist/NoteCore-0.1.0-preview.apk',test_apk)):
        assert hashlib.sha256(raw_adb('exec-out','cat',remote)).hexdigest()==sha(artifact)
    nonce = str(uuid.uuid4());android_root='cache/pdf-capability-'+nonce
    adb('shell','run-as',BUNDLE,'mkdir','-p',android_root)
    for name in NAMES:
        temporary='/data/local/tmp/'+nonce+'-'+name
        adb('push',str(ROOT/'output/pdf'/name),temporary);adb('shell','run-as',BUNDLE,'cp',temporary,android_root+'/'+name)
    android_result = run([args.adb,'-s',args.serial,'shell','run-as',BUNDLE,'/system/bin/env','CLASSPATH='+':'.join(installed),
                          '/system/bin/app_process','/system/bin','dev.notecore.app.probes.PdfCapabilityProbe',android_root])
    android_log=ROOT/'build/dev039-android-pdf.log';android_log.write_text(android_result.stdout+android_result.stderr,encoding='utf-8')
    android_observed=parse(android_result)
    artifacts=ROOT/'build'/('pdf-capability-'+nonce);android_images=artifacts/'android';android_images.mkdir(parents=True)
    for name in IMAGES:(android_images/name).write_bytes(raw_adb('exec-out','run-as',BUNDLE,'cat',android_root+'/'+name))
    for name in NAMES:assert hashlib.sha256(raw_adb('exec-out','run-as',BUNDLE,'cat',android_root+'/'+name)).hexdigest()==sha(ROOT/'output/pdf'/name)
    android_pixels=verify_pixels(android_images,android_observed,recipe)
    assert all(p['native_text_contains_fixture'] for p in android_observed['pages'])
    assert android_observed['closed_page_refused'] and android_observed['closed_document_refused']
    assert android_files()==android_before

    apple_root=artifacts/'apple';apple_root.mkdir()
    for name in NAMES:shutil.copyfile(ROOT/'output/pdf'/name,apple_root/name)
    binary=ROOT/'build/pdf-capability-bin/PdfCapabilityProbe';binary.parent.mkdir(exist_ok=True)
    compiled=run(['xcrun','--sdk','iphonesimulator','swiftc','-target','arm64-apple-ios16.0-simulator',str(ROOT/SWIFT),
        '-framework','CoreGraphics','-framework','ImageIO','-framework','PDFKit','-framework','UniformTypeIdentifiers','-o',str(binary)])
    compile_log=ROOT/'build/dev039-apple-pdf-build.log';compile_log.write_text(compiled.stdout+compiled.stderr,encoding='utf-8')
    assert compiled.returncode==0,compiled.stderr
    build_version=run(['xcrun','vtool','-show-build',str(binary)],check=True).stdout;assert 'IOSSIMULATOR' in build_version
    apple_result=run(['xcrun','simctl','spawn',DEVICE,str(binary),str(apple_root)])
    apple_log=ROOT/'build/dev039-apple-pdf.log';apple_log.write_text(apple_result.stdout+apple_result.stderr,encoding='utf-8')
    apple_observed=parse(apple_result);apple_pixels=verify_pixels(apple_root,apple_observed,recipe)
    assert apple_observed['outline_items']==1 and apple_observed['native_text_contains_fixture']
    for name in NAMES:assert sha(apple_root/name)==sha(ROOT/'output/pdf'/name)
    assert apple_files()==apple_before
    baseline=ROOT/'build/dev039-original-file-hashes.json'
    baseline.write_text(json.dumps({'android_before_and_after':android_before,'apple_before_and_after':apple_before},indent=2)+'\n',encoding='utf-8')
    sdk=Path(run(['xcrun','--sdk','iphonesimulator','--show-sdk-path'],check=True).stdout.strip())
    android_sdk=Path(args.adb).resolve().parent.parent
    contracts=[android_sdk/'sources/android-36/android/graphics/pdf'/name for name in ('PdfRenderer.java','PdfProcessor.java','LoadParams.java')]
    contracts += [sdk/'System/Library/Frameworks/CoreGraphics.framework/Headers'/name for name in ('CGPDFPage.h','CGPDFDocument.h')]
    contracts += [sdk/'System/Library/Frameworks/PDFKit.framework/Headers/PDFDocument.h']
    evidence=set(stamp)|{JAVA,SWIFT,'tools/verify_pdf_capabilities.py','tools/verify_sqlite_capabilities.py','tools/generate_pdf_fixtures.py',
        'fixtures/pdf/recipe.json','output/pdf/manifest.json','build/dev039-fixture-idempotent.json','output/pdf/fixture-contact-sheet.png',
        str(binary.relative_to(ROOT)),str(android_log.relative_to(ROOT)),str(apple_log.relative_to(ROOT)),str(compile_log.relative_to(ROOT)),str(baseline.relative_to(ROOT))}
    evidence|=set(manifest['fixture_sha256'])
    evidence|={str((folder/name).relative_to(ROOT)) for folder in (android_images,apple_root) for name in IMAGES}
    report={'schema':'pdf-platform-capabilities-1','status':'passed',
        'android':{'serial':args.serial,'api':int(adb('shell','getprop','ro.build.version.sdk')),'fingerprint':adb('shell','getprop','ro.build.fingerprint'),
                   'runtime':'real ART app_process/system PdfRenderer, isolated target-App UID/cache; not App owner/UI integration',
                   'observations':android_observed,'pixel_oracle':android_pixels,'original_ncp_files':len(android_before),'original_files_unchanged':True},
        'apple':{'device':DEVICE,'target':'actual arm64 iOS Simulator CoreGraphics/PDFKit, minimum16.0, SDK '+sdk.name,
                 'binary_build_version':build_version,'observations':apple_observed,'pixel_oracle':apple_pixels,
                 'original_ncp_files':len(apple_before),'original_files_unchanged':True},
        'source_and_artifact_sha256':{relative:sha(ROOT/relative) for relative in sorted(evidence)},
        'sdk_contract_sha256':{str(path):sha(path) for path in contracts},
        'decision':'Adopt system renderers as limited candidate ports. Normalize canonical PDF page coordinates to CropBox plus intrinsic rotation, top-left display space; adapt CGContext bitmap direction/tile origin explicitly. Keep guarded tile source/core separation; fixed 4px gutter passes this 1x sample only. Android password/text require version gating, no public document-outline API proven; iOS PDFKit outline/text and CG password/crop verified. Do not claim in-progress native cancellation or production App integration.',
        'scope':'Four synthetic rotations/crop-offset color anchors, native full/tile PNGs and independent marker oracle, guarded-core versus whole-page comparison, test-only RC4-128 password/missing/wrong password, malformed/read-only input, native text/outline capability and selected first/last render requests from a simple 500-page file. No user PDF or production resource/import/task protocol.',
        'large_document_scope':'Only pages 0 and 499 requested for render; does not prove lazy metadata parsing, memory/latency bounds or production prefetch/cancellation.',
        'remaining_gates':['App PDF resource staging/commit and canonical background/annotation integration',
            'Android older API/OS/PDF module/vendor matrix, modern encrypted/forms/large-image/complex content matrix',
            'zoom/filter/rotation/general gutter bounds and color/AA/whole-frame composition',
            'bounded scheduler, generation/late result/cancel between jobs, source/destination lifetime',
            'production progress/cancel/retry/low-space, export and physical performance/heap/C1',
            'untrusted PDF process isolation/minimal permissions and source descriptor lifetime protocol'],
        'references':['https://developer.android.com/reference/android/graphics/pdf/PdfRenderer',
            'https://developer.apple.com/documentation/coregraphics/cgpdfpage/getdrawingtransform(_:rect:rotate:preserveaspectratio:)',
            'https://developer.apple.com/documentation/coregraphics/cgpdfdocument/unlockwithpassword(_:)'],
        'system_runtime_source_equivalence_verified':False,'ordinary_app_pdf_integration_verified':False,
        'in_progress_native_render_cancellation_verified':False,'physical_device_or_heap_measured':False,'c1_gate_complete':False}
    (ROOT/'reports/pdf-platform-capabilities.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'status':'passed','android_pixels':android_pixels,'apple_pixels':apple_pixels,
        'android_original_ncp_files':len(android_before),'apple_original_ncp_files':len(apple_before),
        'ordinary_pdf_integrated':False},ensure_ascii=False))


if __name__=='__main__':main()
