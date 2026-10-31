#!/usr/bin/env python3
"""Check this finite consumer regression's retained bytes and actual XCTest results."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RAW = Path(__file__).resolve().parent
read = lambda p: json.loads(p.read_text(encoding='utf-8'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
receipt = read(ROOT / 'reports/sketch-host-ios-readonly.json')
for group in ('source_sha256', 'retained_sha256'):
    for path, digest in receipt[group].items():
        assert sha(ROOT / path) == digest, path

def cases(nodes):
    for node in nodes:
        if node.get('nodeType') == 'Test Case':
            yield node['name'], node['result']
        yield from cases(node.get('children', []))

before = dict(cases(read(RAW / 'before/tests.json')['testNodes']))
after = dict(cases(read(RAW / 'after/tests.json')['testNodes']))
assert before.keys() == after.keys() and len(after) == 7
assert {name for name, result in before.items() if result == 'Failed'} == {
    'testReadOnlyInvalidToolAndRejectedBatchKeepAcceptedPrefix()',
    'testExplicitCancelAndRetirementKeepCompletedInk()',
}
assert all(result == 'Passed' for result in after.values())
for phase, passed, failed in [('before', 5, 2), ('after', 7, 0)]:
    summary = read(RAW / phase / 'summary.json')
    assert summary['passedTests'] == passed and summary['failedTests'] == failed
    assert summary['skippedTests'] == summary['expectedFailures'] == 0
    assert summary['totalTestCount'] == 7
    assert read(RAW / phase / 'notecore-before.json') == read(RAW / phase / 'notecore-after.json')
assert read(RAW / 'before/notecore-before.json') == read(RAW / 'after/notecore-after.json')
assert len(read(RAW / 'after/notecore-after.json')['notes']) == 16
old = read(RAW / 'before/inputs.json')
assert sha(RAW / 'SketchSession-before.swift') == old['samples/sketch-host-ios/Sources/SketchSession.swift']
new = read(RAW / 'after/evidence.json')
assert new['ordinary_sample_restored_no_xctest'] and new['original_payload_unchanged']
assert sha(RAW / 'ordinary.app.zip') == receipt['ordinary_artifact_sha256'] == new['artifact_sha256']
with zipfile.ZipFile(RAW / 'ordinary.app.zip') as archive:
    assert not any('.xctest' in name for name in archive.namelist())
    payload = {name.removeprefix('SketchHost.app/'): hashlib.sha256(archive.read(name)).hexdigest()
               for name in archive.namelist() if not name.endswith('/')}
for relative, digest in payload.items():
    key = 'samples/sketch-host-ios/build/ordinary/Build/Products/Debug-iphonesimulator/SketchHost.app/' + relative
    assert new['bindings'][key] == digest, relative
with zipfile.ZipFile(RAW / 'test-executable.zip') as archive:
    key = 'samples/sketch-host-ios/build/host-tests/Build/Products/Debug-iphonesimulator/SketchHost.app/PlugIns/SketchHostTests.xctest/SketchHostTests'
    assert hashlib.sha256(archive.read('SketchHostTests')).hexdigest() == new['bindings'][key]
assert not receipt['ordinary_ui_verified'] and not receipt['c1_gate_complete']
print('Verified 67 test-time source bindings, retained failure/pass results, ordinary payload and original 16 notes.')
