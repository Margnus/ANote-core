#!/usr/bin/env python3
"""Read-only, source-backed legacy inventory. No personal notes, DB or AAR execution."""
import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRAW = 'drawpanellibv2/src/main/java/com/jideos/drawpanel/draw/'
DB = 'jnotesv2cn/biz_base_db/src/main/'
FILES = [DRAW + name for name in [
    'PressurePoint.kt', 'DrawRecord.kt', 'Pen.kt', 'convert/StorePressurePoint.kt',
    'convert/StoreDrawRecord.kt', 'convert/PointBitV1.kt', 'convert/DrawRecordBitV1.kt',
    'convert/ParsePointV1.kt', 'convert/ParseStrokeV1.kt', 'convert/StrokeConvertManager.kt',
]] + ['drawpanellibv2/src/main/java/com/jideos/nb_boundary/view/DefaultCoordinateStrategy.kt'] + [DB + name for name in ['proto/DrawRecordProtobuf.proto', 'proto/StrokesProtobuf.proto',
                             'java/com/jideos/biz_base_db/Converter.kt', 'java/com/jideos/biz_base_db/bean/Stroke.kt']]

ANCHORS = [
    ('audio-is-not-input-clock', DRAW+'PressurePoint.kt', 'var mSoundPosition = 0'),
    ('angle-not-in-point-proto', DRAW+'PressurePoint.kt', 'var angle = 0f'),
    ('alpha-constructor-default', DRAW+'PressurePoint.kt', 'var alpha = 1f'),
    ('alpha-proto-absent-default', DB+'java/com/jideos/biz_base_db/Converter.kt', 'point.alpha = if (proto.hasAlpha()) proto.alpha else 0f'),
    ('layer-outside-record-proto', DB+'java/com/jideos/biz_base_db/bean/Stroke.kt', 'var layerId: Int = 0'),
    ('source-record-layer', DRAW+'DrawRecord.kt', 'var layerId:Int = 0'),
    ('packed-pressure-truncation', DRAW+'convert/PointBitV1.kt', 'return s.shl(7) or (p * 100).toInt()'),
    ('packed-record-width', DRAW+'convert/DrawRecordBitV1.kt', '(width * 10).toInt().shl(12)'),
    ('packed-graph-sentinel', DRAW+'convert/DrawRecordBitV1.kt', 'val i6 = t.shr(11) and 1'),
    ('compact-point-loses-pencil', DRAW+'convert/ParsePointV1.kt', 'tp.m = PointBitV1.createM(action, lastType, nextType, mPointFlag)'),
    ('explicit-storage-version', DRAW+'convert/StrokeConvertManager.kt', 'const val CURRENT_STROKE_VERSION = 1'),
    ('legacy-unversioned-prefix', DRAW+'convert/StrokeConvertManager.kt', 'Triple(0, 0, data)'),
]

def fields(text):
    # Restricted source inventory, not a Kotlin AST. Stop before Parcel decoding and methods.
    text = text.split('constructor(parcel:', 1)[0]
    result = []
    previous = 0
    for match in re.finditer(r'\b(var|val)\s+(\w+)\s*(?::\s*([^=,\n]+))?(?:\s*=\s*([^\n]+))?', text):
        name = match.group(2)
        if name in {'DEFAULT', 'CREATOR', 'serialVersionUID'} or re.search(r'const\s+$', text[max(0, match.start()-10):match.start()]):
            previous = match.end()
            continue
        prefix = text[previous:match.start()]
        alias = re.search(r'@SerializedName\("([^"]+)"[^\n]*', prefix)
        result.append({'name': name, 'mutable': match.group(1) == 'var',
                       'declared_type': (match.group(3) or '').strip(),
                       'initializer_expression': (match.group(4) or '').strip().rstrip(','),
                       'json_key': alias.group(1) if alias else name,
                       'alias_annotation': alias.group(0) if alias else None,
                       'serialize_false': bool(re.search(r'@Expose\(serialize\s*=\s*false', prefix)),
                       'transient': '@Transient' in prefix})
        previous = match.end()
    return result

def snapshot(workspace):
    data = {name: (workspace/name).read_bytes() for name in FILES}
    texts = {name: value.decode() for name, value in data.items()}
    inventory = []
    for name in FILES:
        inventory.append({'path': name, 'sha256': hashlib.sha256(data[name]).hexdigest(),
                          'lines': len(texts[name].splitlines())})
    anchors = []
    for identifier, name, anchor in ANCHORS:
        lines = texts[name].splitlines()
        line = next(i+1 for i, text in enumerate(lines) if anchor in text)
        anchors.append({'id': identifier, 'path': name, 'line': line, 'anchor': anchor})
    models = {name: fields(texts[DRAW+name+'.kt']) for name in ['PressurePoint', 'DrawRecord']}
    models.update({name: fields(texts[DRAW+'convert/'+name+'.kt']) for name in ['StorePressurePoint', 'StoreDrawRecord']})
    messages = {}
    for name in [DB+'proto/DrawRecordProtobuf.proto', DB+'proto/StrokesProtobuf.proto']:
        for message, body in re.findall(r'message\s+(\w+)\s*\{([^}]*)\}', texts[name], re.S):
            messages[message] = [{'presence': presence, 'type': kind, 'name': field, 'tag': int(tag)}
                                 for presence, kind, field, tag in re.findall(r'(optional|repeated)\s+(\w+)\s+(\w+)\s*=\s*(\d+);', body)]
    constants = {name: int(value) for name, value in re.findall(r'const val (\w+)\s*=\s*(-?\d+)\b', texts[DRAW+'Pen.kt'])}
    return {'schema_version': 'legacy-inventory-1-draft', 'scope': 'selected local source declarations; no AST, decoder or AAR equivalence',
            'source_files': inventory, 'anchors': anchors, 'models': models, 'proto_messages': messages,
            'pen_constants': constants, 'aar_equivalence_verified': False, 'personal_notes_read': False}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace', type=Path, default=ROOT.parent)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    doc = snapshot(args.workspace)
    path = ROOT/'fixtures/legacy/source-schema.json'
    if args.write:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=2)+'\n')
    elif json.loads(path.read_text()) != doc:
        raise SystemExit('Legacy source inventory drifted; review the mapping before regenerating evidence.')
    print(json.dumps({'status': 'passed', 'source_files': len(doc['source_files']),
                      'anchors': len(doc['anchors']), 'aar_equivalence_verified': False}))

if __name__ == '__main__': main()
