#!/usr/bin/env python3
"""Derive two deterministic metadata-only variants of the existing PDF fixture."""
import hashlib
import json
from pathlib import Path

from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, DictionaryObject, FloatObject, NameObject, NumberObject

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    source = ROOT / 'android-app/src/androidTest/assets/pdf/crop-rotations.pdf'
    expected = '8c29b75c713114288652cbd9afe54a65fc82865543e8d5fd81d3bf8a737aa9ee'
    assert sha(source) == expected
    output = source.parent
    unit = PdfWriter(clone_from=source)
    unit.pdf_header = '%PDF-1.7'
    for page in unit.pages:
        page[NameObject('/UserUnit')] = FloatObject(2)
    with (output / 'user-unit-2.pdf').open('wb') as stream:
        unit.write(stream)
    inherited = PdfWriter(clone_from=source)
    tree = inherited._root_object['/Pages'].get_object()
    children = list(tree['/Kids'])
    parents = []
    for child in children:
        leaf = child.get_object()
        parent = DictionaryObject({NameObject('/Type'): NameObject('/Pages'),
            NameObject('/Parent'): tree.indirect_reference,
            NameObject('/Kids'): ArrayObject([child]), NameObject('/Count'): NumberObject(1)})
        for key in ('/MediaBox', '/CropBox', '/Rotate'):
            parent[NameObject(key)] = leaf.pop(NameObject(key))
        reference = inherited._add_object(parent)
        leaf[NameObject('/Parent')] = reference
        parents.append(reference)
    tree[NameObject('/Kids')] = ArrayObject(parents)
    with (output / 'inherited-geometry.pdf').open('wb') as stream:
        inherited.write(stream)
    # Inspect raw leaf dictionaries before pypdf's pages accessor materializes inherited attributes.
    raw = PdfReader(output / 'inherited-geometry.pdf')
    for i, parent_ref in enumerate(raw.trailer['/Root']['/Pages']['/Kids']):
        parent = parent_ref.get_object(); leaf = parent['/Kids'][0].get_object()
        assert all(key not in leaf for key in ('/MediaBox', '/CropBox', '/Rotate'))
        assert list(parent['/CropBox']) == [40, 50, 340, 230] and parent['/Rotate'] == i * 90
    original = PdfReader(source)
    for name in ('user-unit-2.pdf', 'inherited-geometry.pdf'):
        reader = PdfReader(output / name)
        assert len(reader.pages) == 4
        for i, page in enumerate(reader.pages):
            assert page.get_contents().get_data() == original.pages[i].get_contents().get_data()
            assert list(page.cropbox) == [40, 50, 340, 230] and page.rotation == i * 90
            assert page.get('/UserUnit', 1) == (2 if name.startswith('user-unit') else 1)
    assert sha(source) == expected
    manifest = {'source_sha256': expected, 'content_streams_unchanged': True,
                'leaf_user_unit': 2, 'inherited_leaf_keys_absent': ['/MediaBox', '/CropBox', '/Rotate'],
                'fixture_sha256': {name: sha(output / name) for name in ('user-unit-2.pdf', 'inherited-geometry.pdf')},
                'generator_sha256': sha(Path(__file__))}
    (output / 'metadata-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(manifest))


if __name__ == '__main__':
    main()
