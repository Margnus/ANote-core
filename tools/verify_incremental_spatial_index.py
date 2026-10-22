#!/usr/bin/env python3
"""Bind actual JVM/ART/Native delta-index tests; no App integration or benchmark claim."""
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from verification_hash import verification_digest
from pathlib import Path
from verify_android_brush_common import EXPECTED_ART_TEST_COUNT
from verify_apple_brush_common import EXPECTED_NATIVE_TEST_COUNT

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    bindings = {}

    def bind(relative, expected=None):
        actual = digest(ROOT / relative)
        assert expected is None or actual == expected, ('stale evidence', relative)
        bindings[relative] = actual

    name = 'IncrementalSpatialIndexPocTest'
    source = 'canvas-core/src/commonTest/kotlin/dev/notecore/canvas/prototype/' + name + '.kt'
    methods = re.findall(r'@Test\s+fun\s+(\w+)\(', (ROOT/source).read_text(encoding="utf-8"))
    assert len(methods) == len(set(methods)) == 9
    xml_path = 'canvas-core/build/test-results/jvmTest/TEST-dev.notecore.canvas.prototype.' + name + '.xml'
    suite = ET.parse(ROOT/xml_path).getroot()
    assert int(suite.attrib['tests']) == 9 and all(int(suite.attrib[key]) == 0 for key in ('failures', 'errors', 'skipped'))
    bind(xml_path)
    for report_name, fields in (
        ('verification', ('source_sha256', 'verification_input_sha256')),
        ('android-brush-common-tests', ('source_and_build_sha256',)),
        ('apple-brush-common-tests', ('source_and_build_sha256',)),
    ):
        relative = 'reports/' + report_name + '.json'
        data = json.loads((ROOT/relative).read_text(encoding="utf-8"))
        assert data['status'] == 'passed'
        # These test reports bind complete common sources, plus each platform's build inputs.
        for field in fields:
            for path, sha in data[field].items():
                if report_name == "verification":
                    assert verification_digest(ROOT/path) == sha, ("stale normalized input", path)
                    bind(path)
                else:
                    bind(path, sha)
        bind(relative)
        if report_name == 'android-brush-common-tests':
            assert data['test_count'] == EXPECTED_ART_TEST_COUNT and name in data['test_groups']
            bind(data['test_apk'], data['test_apk_sha256'])
            bind('canvas-core/build/android-brush-tests/run.log', data['test_output_sha256'])
        elif report_name == 'apple-brush-common-tests':
            assert data['test_count'] == EXPECTED_NATIVE_TEST_COUNT
            expected = {'dev.notecore.canvas.prototype.' + name + '.' + m for m in methods}
            assert expected <= set(data['checks'])
            bind('canvas-core/build/bin/iosSimulatorArm64/debugTest/test.kexe', data['test_binary_sha256'])
            bind('canvas-core/build/apple-brush-tests/run.log', data['test_output_sha256'])
    bind(Path(__file__).relative_to(ROOT).as_posix())
    report = {
        'schema': 'incremental-spatial-index-poc-1', 'status': 'passed',
        'scope': 'Derived immutable STR base plus bounded replacement/tombstone overlay, complete-or-rejected bbox queries, atomic delta admission and compaction content publication contract. Actual JVM, ART and Kotlin/Native library tests. Not a production spatial adapter or a second Scene authority.',
        'checks': methods, 'new_common_tests_per_platform': 9,
        'source_and_artifact_sha256': bindings,
        'decision': 'Adopt bounded delta masking and complete-or-rejected query contracts as C1 candidates. Grid/STR selection and production limits remain undecided; only original owner/bounded worker/pool may integrate after C1.',
        'limits': {
            'objects_per_instance_ceiling': 100000,
            'overlay_and_batch': 'Explicit caller bounds; tests use overlay 1/8/256 and atomically reject overflow. Maximum temporary overlay records <= maxOverlay + maxBatch.',
            'queries': 'Separate entry-bounds, tree-node and result limits. Excluded leaf handles still cost at most fanout per visited node; bounded overlay scan includes tombstones. No partial hit set escapes.',
            'retention': 'Per-instance base bounds plus nonnull overlay bounds. Owner must budget old+new indexes and concurrent readers/jobs; this is not retained process heap.',
            'compaction': 'Materialization cancellation checkpoints plus before/after packing. STR sort itself is synchronous and not interruptible. Old content remains until a current-stamp result is published.',
            'stamp': 'Owner-supplied stable derived integer handles, sceneId, monotone content revision and sessionGeneration. Undo advances content revision; a reopened/replaced owner advances generation. No platform presentation token is treated as canonical content.',
        },
        'ordinary_app_index_integration_verified': False,
        'physical_device_or_heap_measured': False, 'performance_threshold_selected': False,
        'c1_gate_complete': False,
    }
    (ROOT/'reports/incremental-spatial-index.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'source_and_artifact_sha256'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
