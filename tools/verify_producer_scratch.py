#!/usr/bin/env python3
"""Bind original Swift producer regression and explicit source responsibility model."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    bindings = {}
    for name, fields in {
        'ios-host-tests': ('source_and_framework_sha256', 'test_build_sha256'),
        'ios-preview-build': ('source_and_build_input_sha256',),
        'ios-preview-restored': ('input_sha256',),
    }.items():
        path = ROOT / 'reports' / (name + '.json')
        report = read(path)
        assert report['status'] == 'passed'
        for field in fields:
            for relative, digest in report[field].items():
                assert sha(ROOT / relative) == digest, (name, relative)
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    host = read(ROOT / 'reports/ios-host-tests.json')
    required = 'testMaximumProducerBatchPreservesAllScalarsAndRejectsOversizeBeforeRecovery()'
    assert required in host['checks']
    restored = read(ROOT / 'reports/ios-preview-restored.json')
    assert restored['no_xctest_plugin'] and restored['ordinary_package_restored_after_host_tests'] and not restored['data_deleted']
    baseline = ROOT / 'build/pending-input-budget/ios-after.json'
    after = ROOT / 'build/producer-scratch/ios-after.json'
    originals = read(baseline)
    assert originals == read(after) and len(originals) == 14
    for name, digest in originals.items():
        assert sha(ROOT / 'build/pending-input-budget/ios-originals' / name) == digest
    for path in [Path(__file__), baseline, after,
                 ROOT / 'docs/输入与派生暂存责任模型.md',
                 ROOT / 'ios-app/NoteCorePreview/NativeInkCanvas.swift',
                 ROOT / 'ios-app/NoteCorePreviewTests/PagedHostTests.swift',
                 ROOT / 'shared-core/src/iosMain/kotlin/dev/notecore/document/ApplePreviewInput.kt',
                 ROOT / 'android-app/src/main/java/dev/notecore/app/InkCanvasView.kt',
                 ROOT / 'shared-core/src/commonMain/kotlin/dev/notecore/document/PagedPreviewNotebook.kt',
                 ROOT / 'shared-core/src/commonMain/kotlin/dev/notecore/document/DocumentRoot.kt',
                 ROOT / 'canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/Scene.kt']:
        bindings[path.relative_to(ROOT).as_posix()] = sha(path)
    source = (ROOT / 'ios-app/NoteCorePreview/NativeInkCanvas.swift').read_text(encoding='utf-8')
    producer = source[source.index('    private func scalarData<T>'):source.index('    private func rejectInput()')]
    assert '.map' not in producer and 'copyMemory' in producer
    report = {
        'schema': 'producer-scratch-1', 'status': 'passed',
        'scope': 'Actual original Swift/UIKit synthetic callback consumer regression and current-source responsibility model; no physical heap bound',
        'swift_host_tests': host['test_count'], 'required_test': required,
        'maximum_batch_points': 1024, 'scalar_payload_bytes_per_batch_copy': 28672,
        'original_ios_files_unchanged': len(originals),
        'decision': 'Fill four Data buffers directly from existing RealSample values; remove four mapped scalar staging arrays, keep original bridge/session/authority.',
        'limits': ['Scalar payload bytes exclude container headers, capacity rounding, Foundation bridging and UIKit event storage',
                   'No measured heap reduction or complete-chain peak admission',
                   'No new Android or shared Native run, ordinary GUI, real stylus, unconfirmed IO or power-loss evidence'],
        'c1_gate_complete': False, 'production_sqlite_authorized': False,
        'source_and_artifact_sha256': dict(sorted(bindings.items())),
    }
    (ROOT / 'reports/producer-scratch.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
