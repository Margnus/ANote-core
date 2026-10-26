#!/usr/bin/env python3
"""Bind reviewed synchronous consumer traversal to real core/Swift tests, not arbitrary-index safety."""
import hashlib
import json
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]
CONSUMERS = {
    'android-app/src/main/java/dev/notecore/app/InkCanvasView.kt': ['for (s in 0 until notebook.strokeCount)', 'val stroke = notebook.stroke(s); val samples = stroke.samples; val n = samples.size', 'for (p in 1 until n)'],
    'ios-app/NoteCorePreview/NativeInkCanvas.swift': ['cached = (0..<note.strokeCount).map', 'let stroke = note.stroke(index: index), samples = stroke.samples', 'for point in 1..<samples.size'],
    'samples/sketch-host/src/main/java/dev/notecore/sample/sketch/SketchView.kt': ['for (i in 0 until note.strokeCount)', 'val stroke = note.stroke(i); val samples = stroke.samples', 'for (p in 1 until samples.size)'],
    'samples/sketch-host-ios/Sources/SketchCanvas.swift': ['cached = (0..<Int(note.strokeCount)).map', 'let stroke = note.stroke(index: Int32(i))', 'for i in 1..<Int(samples.size)'],
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    bindings = {}; reports = {}
    for name, fields in {
        'verification': ('source_sha256', 'verification_input_sha256'),
        'shared-native-contracts': ('source_and_artifact_sha256',),
        'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256', 'input_sdk_contract_sha256'),
        'public-boundary-inventory': ('source_and_artifact_sha256',),
    }.items():
        path = ROOT / 'reports' / (name + '.json'); report = read(path)
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                actual = verification_digest(ROOT / relative) if name == 'verification' else sha(ROOT / relative)
                assert actual == digest, (name, relative)
        bindings[path.relative_to(ROOT).as_posix()] = sha(path); reports[name] = report
    assert 'testCapturedStrokeTraversalRemainsImmutableAfterPageUndoAndRetirement()' in reports['ios-host-tests']['checks']
    reviewed = []
    for relative, snippets in CONSUMERS.items():
        path = ROOT / relative; source = path.read_text(encoding='utf-8')
        for snippet in snippets:
            assert snippet in source, (relative, snippet)
        reviewed.append({'path': relative, 'reviewed_loop_anchors': snippets,
                         'scope': 'Manual review of current synchronous draw/count/object/sample traversal; anchors detect drift, not a control-flow proof or fresh independent-host execution.'})
        bindings[relative] = sha(path)
    restore_path = ROOT / 'reports/ios-preview-restored.json'; restore = read(restore_path)
    assert restore['status'] == 'passed' and restore['ordinary_package_restored_after_host_tests'] and restore['no_xctest_plugin']
    for relative, digest in restore['input_sha256'].items(): assert sha(ROOT / relative) == digest
    before = read(ROOT / 'build/read-traversal/ios-before.json'); after = read(ROOT / 'build/read-traversal/ios-after.json')
    assert before == after == read(ROOT / 'build/budget-creation/ios-after.json') and len(after) == 14
    for name, digest in after.items():
        assert sha(ROOT / 'build/pending-input-budget/ios-originals' / name) == digest
    for relative in ['tools/audit_read_traversal.py', 'docs/只读遍历支持边界.md', 'docs/最小预览消费者契约.md',
                     'shared-core/src/commonMain/kotlin/dev/notecore/document/PagedPreviewNotebook.kt',
                     'canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Stroke.kt',
                     'shared-core/src/commonTest/kotlin/dev/notecore/document/PreviewReadTraversalTest.kt',
                     'build/read-traversal/ios-before.json', 'build/read-traversal/ios-after.json', 'reports/ios-preview-restored.json']:
        bindings[relative] = sha(ROOT / relative)
    result = {
        'schema': 'read-traversal-1', 'status': 'passed', 'reviewed_consumers': reviewed,
        'decision': 'Retain trusted count-bounded synchronous owner traversal for the four current consumers. Immutable captured stroke/sample references remain readable after page/undo/retirement. Raw indexes and wrong-owner Notebook calls retain programmer preconditions; do not add per-point wrappers or another content authority without a real external-index consumer.',
        'jvm_tests': reports['verification']['jvm_contract_tests']['tests'], 'shared_native_tests': reports['shared-native-contracts']['test_count'],
        'swift_host_tests': reports['ios-host-tests']['test_count'], 'apple_original_files_preserved': 14,
        'runtime_scope': 'Three new public common tests and one actual Swift host test. JVM/Native catch index precondition exceptions and verify original root/input retention; Swift uses valid count-bounded indexes only. No arbitrary invalid-index Swift recovery claim.',
        'limits': ['Static loop anchors are not exhaustive control-flow or future consumer safety proof',
                   'No new ordinary Android/Apple package build, independent sample-host run or OS UI interaction',
                   'Immutable-reference lifetime is caller retention responsibility, not a GC/native memory release receipt',
                   'No mutable owner transfer, stable ABI, physical M-1/heap/stylus or production durability claim'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/read-traversal.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ('source_and_artifact_sha256', 'reviewed_consumers')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
