#!/usr/bin/env python3
"""Inventory the source-bound preview ObjC surface; never certify exception or ABI safety."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADER = 'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework/Headers/NoteCanvasPrototype.h'
BINARY = 'shared-core/build/bin/iosSimulatorArm64/debugFramework/NoteCanvasPrototype.framework/NoteCanvasPrototype'
# Expected declaration channels for reviewed examples, NOT an exhaustive list of throwing paths.
CHANNELS = {
    'NCPMemoryDocumentAuthority': {'init(initial:budget:ownerCheck:)': True, 'commit(command:)': False, 'snapshot()': False},
    'NCPPagedPreviewNotebook': {'init(initial:sessionNonce:budget:ownerCheck:)': True, 'rename(value:)': False, 'pointX(index:point:)': False},
    'NCPPagedPreviewCodec': {'decode(text:)': True, 'encode(root:)': True, 'header(text:)': True},
    'NCPPagedPreviewNotebookCompanion': {'create(documentId:title:sessionNonce:)': True},
    'NCPDocumentRootCompanion': {'compose(title:scene:pages:)': False},
    'NCPSceneSnapshotCompanion': {'restored(id:revision:objects:)': False},
    'NCPPageDescriptor': {'init(id:layerId:width:height:paper:contentRevision:)': False},
    'NCPPreviewSaveLedger': {'init(maxDocuments:byteBudget:)': False, 'nextWrite()': False, 'complete(attempt:success:)': False},
    'NCPAppleBrushJobExperiment': {'execute(cancelled:)': False},
    'NCPAppleBrushOwnerExperiment': {'release(reader:unknown:)': False, 'close()': False},
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inspect_header(text):
    result = []
    # Only compiler declaration blocks; exclude forward declarations and ObjC categories.
    for kind, name, tail, body in re.findall(r'^@(interface|protocol) (NCP\w+)([^;\n]*)\n(.*?)^@end', text, re.M | re.S):
        if tail.lstrip().startswith('('):
            continue
        methods = []
        for line in body.splitlines():
            if not line.startswith(('- (', '+ (')) or 'unavailable' in line:
                continue
            match = re.search(r'swift_name\("([^"]+)"\)', line)
            if match:
                methods.append({'swift_name': match.group(1), 'declares_nserror': 'NSError * _Nullable *' in line})
        result.append({'kind': kind, 'objc_name': name, 'methods': methods})
    assert len({item['objc_name'] for item in result}) == len(result), 'Duplicate ObjC declaration'
    assert result, 'Empty generated header'
    return result


def check_channels(declarations):
    by_name = {item['objc_name']: item for item in declarations}
    checked = []
    for name, methods in CHANNELS.items():
        assert name in by_name, ('Missing reviewed type', name)
        for method, expected in methods.items():
            actual = [item for item in by_name[name]['methods'] if item['swift_name'] == method]
            assert len(actual) == 1 and actual[0]['declares_nserror'] == expected, ('Changed reviewed declaration', name, method)
            checked.append({'objc_name': name, 'swift_name': method, 'declares_nserror': expected})
    return checked


def current_inputs(root):
    build = json.loads((root/'reports/ios-preview-build.json').read_text(encoding='utf-8'))
    assert build['status'] == 'passed' and build['build_verified'] is True
    bound = build['source_and_build_input_sha256']
    required = {str(p.relative_to(root)) for pattern in ('canvas-core/src/commonMain/**/*.kt',
        'canvas-core/src/iosMain/**/*.kt', 'shared-core/src/commonMain/**/*.kt', 'ios-app/NoteCorePreview/*.swift')
        for p in root.glob(pattern)} | {BINARY, 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
        'canvas-core/build.gradle.kts', 'shared-core/build.gradle.kts'}
    assert required <= set(bound), ('Incomplete framework build binding', sorted(required - set(bound)))
    for name, digest in bound.items():
        assert sha(root/name) == digest, ('Stale retained build input', name)
    result = dict(bound)
    android = json.loads((root/'reports/preview-build.json').read_text(encoding='utf-8'))
    assert android['status'] == 'passed' and android['android']['build_verified'] is True
    android_bound = android['source_and_build_input_sha256']
    android_required = {str(p.relative_to(root)) for pattern in ('android-app/src/main/**/*.kt',
        'canvas-core/src/androidMain/**/*.kt', 'canvas-core/src/jvmAndAndroidMain/**/*.kt') for p in root.glob(pattern)}
    assert android_required <= set(android_bound), ('Incomplete Android build binding', sorted(android_required - set(android_bound)))
    for name, digest in android_bound.items():
        assert sha(root/name) == digest, ('Stale retained Android build input', name)
    result.update(android_bound)
    artifact = android['android']['artifact']
    assert sha(root/artifact) == android['android']['sha256'], 'Stale Android artifact'
    result[artifact] = android['android']['sha256']
    return result


def main():
    bindings = current_inputs(ROOT)
    header = (ROOT/HEADER).read_text(encoding='utf-8')
    declarations = inspect_header(header)
    checked = check_channels(declarations)
    for name in (HEADER, 'reports/ios-preview-build.json', 'reports/preview-build.json', 'tools/check_architecture.py', 'tools/audit_public_boundary.py'):
        bindings[name] = sha(ROOT/name)
    report = {
        'schema': 'public-boundary-inventory-1', 'status': 'passed',
        'source_and_artifact_sha256': bindings,
        'objc_declaration_count': len(declarations),
        'objc_method_count': sum(len(item['methods']) for item in declarations),
        'reviewed_method_count': len(checked), 'reviewed_declaration_channels': checked,
        'objc_declarations': [{'kind': item['kind'], 'objc_name': item['objc_name'],
            'methods': [method['swift_name'] for method in item['methods']],
            'nserror_methods': [method['swift_name'] for method in item['methods'] if method['declares_nserror']]}
            for item in declarations],
        'physical_modules': {'shared-core': ['canvas-core'], 'canvas-core': []},
        'dependency_scope': 'Current build files separately checked by check_architecture.py; this map is the reviewed topology, not a transitive binary dependency scan.',
        'scope': 'Inventory of retained source-bound iOS Simulator compiler header, including compiler support/generated methods. NSError declaration is structural evidence only: no claim that every thrown exception or callback is caught. Methods with no NSError may return typed failures or trap on precondition/owner/index violations. No API surface is approved by this count.',
        'decision': 'Retain the two physical core modules and unfrozen preview facade. Reject direct promotion of exported reference authorities, resource pool internals or experiment shims into a stable SDK. Freeze only a reviewed capability/typed-failure/owner boundary after conformance and C1; hide implementation types as a separately validated ABI change.',
        'all_exported_apis_safe_for_arbitrary_swift_input': False,
        'swift_abi_or_semver_frozen': False, 'complete_failure_conformance_verified': False,
        'new_native_execution_performed': False, 'physical_device_verified': False, 'c1_gate_complete': False,
    }
    (ROOT/'reports/public-boundary-inventory.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('status', 'objc_declaration_count', 'objc_method_count', 'reviewed_method_count', 'c1_gate_complete')}))


if __name__ == '__main__': main()
