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
    'NCPDocumentBudgetCompanion': {'validated(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)': False},
    'NCPPreviewRecoveryCodec': {'decode(text:)': True, 'read(text:)': False},
    'NCPMemoryDocumentAuthority': {'init(initial:budget:ownerCheck:)': True, 'commit(command:)': False, 'snapshot()': False},
    'NCPPagedPreviewNotebook': {'init(initial:sessionNonce:budget:ownerCheck:)': True, 'rename(value:)': False,
        'stroke(index:)': False, 'page(index:)': False, 'pointCount(index:)': False, 'pointX(index:point:)': False},
    'NCPStrokeSamples': {'x(index:)': False, 'y(index:)': False, 'pressure(index:)': False, 'timeMicros(index:)': False},
    'NCPPagedPreviewCodec': {'decode(text:)': True, 'encode(root:)': True, 'header(text:)': True},
    'NCPPagedPreviewNotebookCompanion': {'create(documentId:title:sessionNonce:)': True,
        'open(initial:sessionNonce:budget:ownerCheck:)': False,
        'createValidated(documentId:title:sessionNonce:width:height:paper:budget:ownerCheck:)': False},
    'NCPDocumentRootCompanion': {'compose(title:scene:pages:)': False},
    'NCPSceneSnapshotCompanion': {'restored(id:revision:objects:)': False},
    'NCPPageDescriptor': {'init(id:layerId:width:height:paper:contentRevision:)': False},
    'NCPPreviewSaveLedger': {'init(maxDocuments:byteBudget:ownerCheck:)': False,
        'admitNextWrite(availableWorkBytes:maximumWorkBytes:backendAccepting:)': False,
        'nextWrite()': False, 'complete(attempt:success:)': False},
    'NCPAppleBrushJobExperiment': {'execute(cancelled:)': False},
    'NCPAppleBrushOwnerExperiment': {'release(reader:unknown:)': False, 'close()': False},
}


# Property accessors have no NSError parameter channel. Owner/index/precondition safety is separate.
PROPERTY_CHANNELS = {
    'NCPPreviewFailureKind': {'readOnly': (True, False)},
    'NCPDocumentBudgetCreation': {'budget': (False, True), 'failure': (False, True)},
    'NCPPagedPreviewNotebook': {'activeInputOperationId': (False, True), 'inputReservedByteCharge': (False, False),
                               'hasPendingStroke': (False, False), 'lastFailure': (False, True)},
    'NCPStrokeSamples': {'size': (False, False)},
}

# The selected consumer path is distinct from legacy examples above. These are structural
# requirements, not a promise that owner callbacks, indexes or every raw value are recoverable.
# Each method maps to (declares NSError, nullable return), including nullable NSError returns.
CANDIDATE_METHODS = {
    'NCPDocumentBudgetCompanion': {
        'validated(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)': (False, False)},
    'NCPPagedPreviewNotebookCompanion': {
        'createValidated(documentId:title:sessionNonce:width:height:paper:budget:ownerCheck:)': (False, False),
        'open(initial:sessionNonce:budget:ownerCheck:)': (False, False),
        'openRecovery(snapshot:sessionNonce:budget:ownerCheck:)': (False, False)},
    'NCPPreviewDocumentReader': {'read(text:)': (False, False), 'accept(root:)': (False, False)},
    'NCPPreviewRecoveryCodec': {'read(text:)': (False, False)},
    'NCPApplePreviewInput': {'offer(notebook:phase:eventTimeMicros:xs:ys:pressures:times:)': (False, False)},
    'NCPPagedPreviewNotebook': {
        'edit(request:)': (False, False), 'prepareRealBatch(phase:count:)': (False, True),
        'setReadOnly(value:)': (False, False), 'cancelStroke()': (False, False),
        'retryPendingStroke()': (False, False), 'pendingStroke()': (False, True),
        'page(index:)': (False, False), 'stroke(index:)': (False, False),
        'pointCount(index:)': (False, False), 'pointX(index:point:)': (False, False),
        'pointY(index:point:)': (False, False), 'pointPressure(index:point:)': (False, False),
        'capture()': (False, False), 'captureRecovery()': (False, False), 'snapshot()': (False, False),
        'retire()': (False, False), 'retireAfterRecovery(snapshot:)': (False, False)},
    'NCPPagedPreviewSnapshot': {'encode()': (True, True), 'sameContent(other:)': (False, False)},
}
CANDIDATE_PROPERTIES = {
    'NCPDocumentBudgetCreation': {'budget': (False, True), 'failure': (False, True)},
    'NCPPreviewDocumentRead': {'root': (False, True), 'failure': (False, True)},
    'NCPPreviewRecoveryRead': {'snapshot': (False, True), 'failure': (False, True)},
    'NCPPreviewNotebookOpen': {'notebook': (False, True), 'failure': (False, True), 'documentReason': (False, True)},
    'NCPPreviewEditResult': {'accepted': (False, False), 'failure': (False, True), 'rootRevision': (False, False)},
    'NCPPreviewInputResult': {'disposition': (False, False), 'acceptedSamples': (False, False),
        'activeSamples': (False, False), 'admitted': (False, False), 'failure': (False, True), 'inputReason': (False, True)},
    'NCPPreviewSessionFailure': {'kind': (False, False), 'documentReason': (False, True)},
    'NCPPagedPreviewSnapshot': {'root': (False, False), 'pending': (False, True), 'version': (False, False),
        'retainedByteCharge': (False, False), 'encodingWorkByteCharge': (False, False)},
    'NCPPagedPreviewNotebook': {'isReadOnly': (False, False), 'hasActiveStroke': (False, False),
        'hasPendingStroke': (False, False), 'lastFailure': (False, True), 'activeInputOperationId': (False, True),
        'inputReservedByteCharge': (False, False), 'currentPage': (False, False), 'pageCount': (False, False),
        'strokeCount': (False, False)},
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
        properties = []
        for line in body.splitlines():
            if line.startswith('@property '):
                match = re.search(r'swift_name\("([^\"]+)"\)', line)
                if match:
                    properties.append({'swift_name': match.group(1), 'class_property': 'class,' in line or '(class)' in line,
                                       'nullable': '_Nullable' in line, 'readonly': 'readonly' in line,
                                       'declares_nserror': 'NSError * _Nullable *' in line})
            if not line.startswith(('- (', '+ (')) or 'unavailable' in line:
                continue
            match = re.search(r'swift_name\("([^"]+)"\)', line)
            if match:
                return_type = re.match(r'^[-+] \(([^)]+)\)', line).group(1)
                methods.append({'swift_name': match.group(1), 'declares_nserror': 'NSError * _Nullable *' in line,
                                'nullable_return': '_Nullable' in return_type})
        result.append({'kind': kind, 'objc_name': name, 'methods': methods, 'properties': properties})
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


def check_property_channels(declarations, expected=PROPERTY_CHANNELS):
    by_name = {item['objc_name']: item for item in declarations}
    checked = []
    for name, members in expected.items():
        assert name in by_name, ('Missing property type', name)
        for member, (class_property, nullable) in members.items():
            matches = [p for p in by_name[name]['properties'] if p['swift_name'] == member]
            assert len(matches) == 1, ('Missing/duplicate reviewed property', name, member)
            value = matches[0]
            assert value['class_property'] == class_property and value['nullable'] == nullable, ('Changed reviewed property shape', name, member)
            assert value['readonly'] and not value['declares_nserror'], ('Changed reviewed property qualifiers', name, member)
            checked.append({'objc_name': name, **value})
    return checked


def check_candidate_channels(declarations):
    by_name = {item['objc_name']: item for item in declarations}
    notebook = by_name.get('NCPPagedPreviewNotebook')
    assert notebook is not None, ('Missing candidate type', 'NCPPagedPreviewNotebook')
    assert not any(method['swift_name'].startswith('offerRealBatch(') for method in notebook['methods']), (
        'Raw Kotlin array entry exported from NCPPagedPreviewNotebook')
    checked = []
    for name, members in CANDIDATE_METHODS.items():
        assert name in by_name, ('Missing candidate type', name)
        for member, (nserror, nullable) in members.items():
            values = [m for m in by_name[name]['methods'] if m['swift_name'] == member]
            assert len(values) == 1, ('Missing/duplicate candidate method', name, member)
            value = values[0]
            assert value['declares_nserror'] == nserror, ('Changed candidate NSError channel', name, member)
            assert value['nullable_return'] == nullable, ('Changed candidate nullable return', name, member)
            checked.append({'objc_name': name, **value})
    return checked


def current_inputs(root):
    build = json.loads((root/'reports/ios-preview-build.json').read_text(encoding='utf-8'))
    assert build['status'] == 'passed' and build['build_verified'] is True
    bound = build['source_and_build_input_sha256']
    required = {str(p.relative_to(root)) for pattern in ('canvas-core/src/commonMain/**/*.kt',
        'canvas-core/src/iosMain/**/*.kt', 'shared-core/src/iosMain/**/*.kt', 'shared-core/src/commonMain/**/*.kt', 'ios-app/NoteCorePreview/*.swift')
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
    properties = check_property_channels(declarations)
    candidate_methods = check_candidate_channels(declarations)
    candidate_properties = check_property_channels(declarations, CANDIDATE_PROPERTIES)
    for name in (HEADER, 'reports/ios-preview-build.json', 'reports/preview-build.json', 'tools/check_architecture.py', 'tools/audit_public_boundary.py'):
        bindings[name] = sha(ROOT/name)
    report = {
        'schema': 'public-boundary-inventory-1', 'status': 'passed',
        'source_and_artifact_sha256': bindings,
        'objc_declaration_count': len(declarations),
        'objc_method_count': sum(len(item['methods']) for item in declarations),
        'objc_property_count': sum(len(item['properties']) for item in declarations),
        'reviewed_property_count': len(properties), 'reviewed_property_channels': properties,
        'reviewed_method_count': len(checked), 'reviewed_declaration_channels': checked,
        'candidate_method_count': len(candidate_methods), 'candidate_methods': candidate_methods,
        'candidate_property_count': len(candidate_properties), 'candidate_properties': candidate_properties,
        'candidate_scope': 'Selected preview consumer declaration shape only: NSError parameter, return nullability and readonly/class/nullable result properties. Not full ABI type layout or semantic validation, exception safety, owner transfer, index safety, host I/O acknowledgement or frozen SDK approval.',
        'objc_declarations': [{'kind': item['kind'], 'objc_name': item['objc_name'],
            'properties': item['properties'],
            'methods': [method['swift_name'] for method in item['methods']],
            'nserror_methods': [method['swift_name'] for method in item['methods'] if method['declares_nserror']]}
            for item in declarations],
        'physical_modules': {'shared-core': ['canvas-core'], 'canvas-core': []},
        'dependency_scope': 'Current build files separately checked by check_architecture.py; this map is the reviewed topology, not a transitive binary dependency scan.',
        'scope': 'Inventory of retained source-bound iOS Simulator compiler header, including compiler support/generated methods. Properties are inventoried separately; readonly/nullable/class accessor shape is structural, not owner/index/exception safety. NSError declaration is structural evidence only: no claim that every thrown exception or callback is caught. Methods with no NSError may return typed failures or trap on precondition/owner/index violations. No API surface is approved by this count.',
        'decision': 'Retain the two physical core modules and unfrozen preview facade. Reject direct promotion of exported reference authorities, resource pool internals or experiment shims into a stable SDK. Freeze only a reviewed capability/typed-failure/owner boundary after conformance and C1; hide implementation types as a separately validated ABI change.',
        'all_exported_apis_safe_for_arbitrary_swift_input': False,
        'swift_abi_or_semver_frozen': False, 'complete_failure_conformance_verified': False,
        'new_native_execution_performed': False, 'physical_device_verified': False, 'c1_gate_complete': False,
    }
    (ROOT/'reports/public-boundary-inventory.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('status', 'objc_declaration_count', 'objc_method_count', 'objc_property_count', 'reviewed_property_count', 'reviewed_method_count', 'candidate_method_count', 'candidate_property_count', 'c1_gate_complete')}))


if __name__ == '__main__': main()
