#!/usr/bin/env python3
"""Generate synthetic native-PDF capability fixtures using the bundled PDF runtime."""
import hashlib
import io
import json
from pathlib import Path
from reportlab.pdfgen.canvas import Canvas
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, ByteStringObject, RectangleObject

ROOT = Path(__file__).resolve().parents[1]


def main():
    recipe = json.loads((ROOT/'fixtures/pdf/recipe.json').read_text(encoding='utf-8'))
    output = ROOT/'output/pdf'; output.mkdir(parents=True, exist_ok=True)
    buffer = io.BytesIO()
    canvas = Canvas(buffer, pagesize=(400,300), invariant=1, pageCompression=0)
    for rotation in recipe['rotations']:
        canvas.setFillColorRGB(.86,.89,.93); canvas.rect(0,0,400,300,fill=1,stroke=0)
        canvas.setFillColorRGB(1,1,1); canvas.rect(40,50,300,180,fill=1,stroke=0)
        for color,(x,y) in zip(recipe['marker_rgb'],[(48,58),(316,58),(48,206),(316,206)]):
            canvas.setFillColorRGB(*(channel/255 for channel in color)); canvas.rect(x,y,16,16,fill=1,stroke=0)
        canvas.setFillColorRGB(.10,.16,.24);canvas.setFont('Helvetica-Bold',16)
        canvas.drawString(80,157,'NoteCore PDF fixture')
        canvas.setFont('Helvetica',11);canvas.drawString(80,135,'CropBox 300 x 180 | Rotate '+str(rotation))
        canvas.drawString(80,116,'Read-only source - four color anchors')
        canvas.showPage()
    canvas.save()
    source = PdfReader(buffer)
    writer = PdfWriter()
    for page,rotation in zip(source.pages,recipe['rotations']):
        page.cropbox = RectangleObject(recipe['crop_box']); page.rotate(rotation); writer.add_page(page)
    writer.add_outline_item('Fixture first page',0)
    writer.add_metadata({'/Title':'NoteCore crop and rotation fixture','/Creator':'NoteCore test generator',
                         '/CreationDate':'D:20000101000000Z','/ModDate':'D:20000101000000Z'})
    writer._ID = ArrayObject([ByteStringObject(b'NoteCoreFixture1'),ByteStringObject(b'NoteCoreFixture1')])
    plain = output/'crop-rotations.pdf'
    with plain.open('wb') as stream: writer.write(stream)
    encrypted = PdfWriter(clone_from=plain)
    encrypted._ID = ArrayObject([ByteStringObject(b'NoteCoreFixture2'),ByteStringObject(b'NoteCoreFixture2')])
    encrypted.encrypt(recipe['password_fixture'],owner_password='notecore-test-owner',algorithm='RC4-128')
    with (output/'password.pdf').open('wb') as stream: encrypted.write(stream)
    many = Canvas(str(output/'many-pages.pdf'),pagesize=(320,240),invariant=1,pageCompression=1)
    for index in range(recipe['large_document_pages']):
        many.setFont('Helvetica',16);many.drawString(30,180,'Synthetic page '+str(index+1));many.showPage()
    many.save()
    (output/'malformed.pdf').write_bytes(b'%PDF-1.7\nThis is an intentionally invalid synthetic fixture.\n')
    reader = PdfReader(plain)
    assert len(reader.pages)==4 and len(reader.outline)==1
    for page,expected in zip(reader.pages,recipe['expected_pages']):
        assert list(page.mediabox)==recipe['media_box'] and list(page.cropbox)==recipe['crop_box']
        assert page.rotation==expected['rotation'] and 'NoteCore PDF fixture' in page.extract_text()
    locked = PdfReader(output/'password.pdf');assert locked.is_encrypted
    assert locked.decrypt('wrong-fixture-password')==0 and locked.decrypt(recipe['password_fixture'])!=0
    assert len(locked.pages)==4 and len(PdfReader(output/'many-pages.pdf').pages)==recipe['large_document_pages']
    files = [output/name for name in ('crop-rotations.pdf','password.pdf','many-pages.pdf','malformed.pdf')]
    manifest = {'schema':recipe['schema'],'fixture_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
                'recipe_sha256':hashlib.sha256((ROOT/'fixtures/pdf/recipe.json').read_bytes()).hexdigest(),
                'generator_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'scope':recipe['scope'],'malformed_fixture_is_intentionally_invalid':True}
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(manifest))


if __name__ == '__main__': main()
