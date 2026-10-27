#!/usr/bin/env python3
"""Read-only local source capability inventory; no import, conversion or resource publication."""
import argparse
import hashlib
import json
import struct
import subprocess
import tempfile
import importlib.metadata
from pathlib import Path
from pypdf import PdfReader
from PIL import Image, ImageOps
ROOT=Path(__file__).resolve().parents[1]

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

def font_info(path):
    with path.open('rb') as stream:
        header=stream.read(12);assert len(header)==12 and header[:4] in (b'\x00\x01\x00\x00',b'OTTO')
        count=struct.unpack('>H',header[4:6])[0];assert 0<count<=256
        records=stream.read(count*16);assert len(records)==count*16
        tables={}
        for index in range(count):
            tag,checksum,offset,length=struct.unpack('>4sIII',records[index*16:(index+1)*16])
            assert tag not in tables and offset+length<=path.stat().st_size
            tables[tag]=(offset,length)
        assert all(tag in tables for tag in (b'head',b'cmap',b'OS/2'))
        def field(tag,offset):
            start,length=tables[tag];assert offset+2<=length
            stream.seek(start+offset);return struct.unpack('>H',stream.read(2))[0]
        units=field(b'head',18);assert 16<=units<=16384
        fs_type=field(b'OS/2',8)
        return {'sfnt':header[:4].hex(),'tables':count,'units_per_em':units,'os2_fs_type':fs_type,
                'embedding_license_confirmed':False,'glyph_coverage_verified':False,
                'note':'fsType is a technical flag, not license authorization; shaping/fallback/layout remain unverified.'}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--pdf',type=Path,required=True)
    parser.add_argument('--image',type=Path,nargs='+',required=True);parser.add_argument('--font',type=Path,required=True)
    parser.add_argument('--pdftoppm',type=Path,required=True);args=parser.parse_args()
    sources=[args.pdf,*args.image,args.font];assert all(p.is_file() and p.stat().st_size<=64*1024*1024 for p in sources)
    before={str(p.resolve()):sha(p) for p in sources}
    build=(ROOT/'build').resolve();assert build.is_relative_to(ROOT);build.mkdir(exist_ok=True)
    output=Path(tempfile.mkdtemp(prefix='source-assets-',dir=build))
    reader=PdfReader(args.pdf,strict=True);assert not reader.is_encrypted and 0<len(reader.pages)<=500
    pages=[]
    for page in reader.pages:
        fonts=[]
        resources=page.get('/Resources',{}).get_object()
        for value in resources.get('/Font',{}).get_object().values() if '/Font' in resources else []:
            font=value.get_object();fonts.append(str(font.get('/Subtype','unknown')))
        pages.append({'media_box':[float(v) for v in page.mediabox],'crop_box':[float(v) for v in page.cropbox],
                      'rotation':page.rotation,'user_unit':float(page.get('/UserUnit',1)),'font_subtypes':sorted(set(fonts))})
    renders=[]
    for number in sorted({1,len(reader.pages)}):
        prefix=output/('pdf-page-'+str(number))
        subprocess.run([str(args.pdftoppm),'-f',str(number),'-l',str(number),'-scale-to','1200','-singlefile','-png',str(args.pdf),str(prefix)],check=True,capture_output=True,timeout=45)
        image=prefix.with_suffix('.png')
        with Image.open(image) as decoded:
            decoded.load();assert max(decoded.size)<=1200
            renders.append({'page':number,'size':list(decoded.size),'path':image.relative_to(ROOT).as_posix(),'sha256':sha(image)})
    images=[]
    for path in args.image:
        with Image.open(path) as image:
            assert image.width*image.height<=32_000_000;image.load()
            orientation=image.getexif().get(274,1)
            with ImageOps.exif_transpose(image) as oriented:
                images.append({'source_sha256':sha(path),'format':image.format,'encoded_size':list(image.size),
                    'display_size':list(oriented.size),'mode':image.mode,'exif_orientation':orientation,
                    'alpha_extrema':list(image.getchannel('A').getextrema()) if 'A' in image.getbands() else None})
    report={'status':'passed','host_versions':{name:importlib.metadata.version(name) for name in ('pypdf','Pillow')},'sources':[{'path':p.resolve().relative_to(ROOT.parent).as_posix(),'bytes':p.stat().st_size,'sha256':sha(p)} for p in sources],
        'pdf':{'page_count':len(pages),'pages':pages,'renders':renders,'full_visual_review':False},'images':images,'font':font_info(args.font),
        'source_payloads_unchanged':before=={str(p.resolve()):sha(p) for p in sources},
        'app_import_implemented':False,'cross_platform_render_verified':False,'production_schema_frozen':False,
        'scope':'Read-only real local assets, host parser/decoder and first/last PDF page render only. No source assets copied or published; no product acceptance.',
        'source_and_artifact_sha256':{'tools/inspect_source_assets.py':sha(Path(__file__))}}
    assert report['source_payloads_unchanged']
    (ROOT/'reports/source-asset-inspection.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
    print(json.dumps({'status':'passed','pdf_pages':len(pages),'images':len(images),'renders':renders},ensure_ascii=False))
if __name__=='__main__':main()
