#!/usr/bin/env python3
"""Trace the reviewed preview declaration profile through a generated ObjC header."""

import argparse
from collections import deque
import hashlib
import json
from pathlib import Path
import re

from audit_public_boundary import (
    CANDIDATE_METHODS, CANDIDATE_PROPERTIES, check_candidate_channels,
    check_property_channels, inspect_header,
)

TYPE = re.compile(r'\bNCP[A-Za-z0-9_]+\b')
BLOCK = re.compile(r'^@(interface|protocol) (NCP\w+)([^;\n]*)\n(.*?)^@end', re.M | re.S)
SWIFT_NAME = re.compile(r'__attribute__\(\(swift_name\("([^"]+)"\)\)\)')
EDIT_VARIANTS = {
    'NCPPreviewEditCopyPage', 'NCPPreviewEditDeletePage', 'NCPPreviewEditInsertPage',
    'NCPPreviewEditMovePage', 'NCPPreviewEditRedo', 'NCPPreviewEditRename',
    'NCPPreviewEditSelectPage', 'NCPPreviewEditSelectPen', 'NCPPreviewEditSetPaper',
    'NCPPreviewEditUndo',
}
CURRENT_EDIT_VARIANTS = EDIT_VARIANTS | {
    'NCPPreviewEditEraseStrokes', 'NCPPreviewEditLassoSelect',
    'NCPPreviewEditTransformSelection', 'NCPPreviewEditDeleteSelection',
}
# Supplementary members needed to obtain/use the selected values. This profile
# excludes the App's legacy compatibility, catalog, save ledger and render ports.
VALUE_MEMBERS = {
    'NCPDocumentBudget': ('companion',),
    'NCPPagedPreviewNotebook': ('companion', 'changeVersion', 'inputBatchLimit'),
    'NCPApplePreviewInput': ('shared',),
    'NCPPreviewDocumentReader': ('shared',),
    'NCPPreviewRecoveryCodec': ('shared',),
    'NCPPageDescriptor': ('id', 'layerId', 'width', 'height', 'paper', 'contentRevision'),
    'NCPPageId': ('value',),
    'NCPLayerId': ('value',),
    'NCPDocumentRoot': ('id', 'title', 'revision', 'pageCount'),
    'NCPPagedPreviewSnapshot': ('documentId', 'title'),
    'NCPStrokeObject': ('samples', 'brush', 'transform'),
    'NCPStrokeSamples': ('size', 'x(index:)', 'y(index:)', 'pressure(index:)', 'timeMicros(index:)'),
    'NCPInputSample': ('x', 'y', 'pressure', 'timeMicros'),
    'NCPBrushSpec': ('argb', 'width'),
    'NCPAffine2D': ('a', 'b', 'c', 'd', 'tx', 'ty'),
}


def parse_header(header):
    declarations = {}
    for _, name, tail, body in BLOCK.findall(header):
        if tail.lstrip().startswith('('):
            continue
        assert name not in declarations, ('duplicate type', name)
        members = {}
        for line in body.splitlines():
            if not line.startswith(('- (', '+ (', '@property ')) or 'unavailable' in line:
                continue
            swift_name = SWIFT_NAME.search(line)
            if swift_name:
                key = swift_name[1]
                assert key not in members, ('duplicate member', name, key)
                members[key] = line
        declarations[name] = {'inheritance': tail.strip(), 'members': members}
    return declarations


def selected_members(declarations, current_candidate=False):
    result = {name: set(members) for name, members in VALUE_MEMBERS.items()}
    for group in (CANDIDATE_METHODS, CANDIDATE_PROPERTIES):
        for name, members in group.items():
            result.setdefault(name, set()).update(members)
    # The typed request protocol alone cannot create a request. Select the
    # public constructors of the actual edit variants and enum case getters.
    variants = {name for name, item in declarations.items() if '<NCPPreviewEdit>' in item['inheritance']}
    expected_variants = CURRENT_EDIT_VARIANTS if current_candidate else EDIT_VARIANTS
    assert variants == expected_variants, ('review changed typed edit variants', variants ^ expected_variants)
    if current_candidate:
        # Select the recoverable factory, never the raw constructor's precondition.
        result['NCPPointD'] = {'companion', 'x', 'y'}
        result['NCPPointDCompanion'] = {'validated(x:y:)'}
    for name in sorted(variants):
        members = declarations[name]['members']
        if 'shared' in members:
            result[name] = {'shared'}
        else:
            constructors = [member for member in members if member.startswith('init(')]
            assert len(constructors) == 1, (name, constructors)
            result[name] = set(constructors)
    enums = ('NCPDocumentBudgetFailure', 'NCPDocumentRejection', 'NCPInputRejection', 'NCPPaperKind',
             'NCPPointerPhase', 'NCPPreviewFailureKind', 'NCPPreviewInputDisposition',
             'NCPPreviewOpenFailure', 'NCPPreviewReadFailure')
    for name in enums:
        result[name] = {key for key, line in declarations[name]['members'].items()
                        if line.startswith('@property (class, readonly') and ' *' in line
                        and 'getter=companion' not in line}
        assert result[name], ('enum has no case declarations', name)
    for name, members in result.items():
        assert members <= declarations[name]['members'].keys(), (name, members - declarations[name]['members'].keys())
    return result


def dependencies(declaration):
    signature = SWIFT_NAME.split(declaration, maxsplit=1)[0]
    return set(TYPE.findall(signature))


def closure(declarations, roots, members_for, consumer_edges):
    paths = {name: [] for name in sorted(roots)}
    queue = deque(paths)
    while queue:
        name = queue.popleft()
        item = declarations[name]
        edges = [('<inheritance>', item['inheritance'])]
        edges.extend((key, item['members'][key]) for key in sorted(members_for(name)))
        edges.extend((kind, target) for kind, target in consumer_edges.get(name, ()))
        for member, signature in edges:
            for target in sorted(dependencies(signature) - {name}):
                assert target in declarations, ('unresolved exported signature', name, member, target)
                if target not in paths:
                    paths[target] = paths[name] + [{'from': name, 'member': member, 'to': target}]
                    queue.append(target)
    return dict(sorted(paths.items()))


def audit(header, current_candidate=False):
    declarations = parse_header(header)
    inventory = inspect_header(header)
    assert set(declarations) == {item['objc_name'] for item in inventory}
    for item in inventory:
        assert set(declarations[item['objc_name']]['members']) == {
            member['swift_name'] for member in item['methods'] + item['properties']}
    methods = check_candidate_channels(inventory)
    properties = check_property_channels(inventory, CANDIDATE_PROPERTIES)
    if current_candidate:
        point_types = [item for item in inventory if item['objc_name'] == 'NCPPointDCompanion']
        assert len(point_types) == 1, 'Missing/duplicate point factory companion'
        point_type = point_types[0]
        factories = [m for m in point_type['methods'] if m['swift_name'] == 'validated(x:y:)']
        assert len(factories) == 1 and factories[0]['nullable_return'] and not factories[0]['declares_nserror'], 'Changed point factory refusal channel'
    selected = selected_members(declarations, current_candidate)
    roots = set(CANDIDATE_METHODS) | set(CANDIDATE_PROPERTIES)
    # Protocol conformance is one-way in the generated declaration. The
    # consumer must construct the typed edit variants.
    consumer_edges = {
        'NCPPreviewEdit': [('<typed request construction>', name) for name in
                           sorted(CURRENT_EDIT_VARIANTS if current_candidate else EDIT_VARIANTS)],
    }
    for protocol, edges in consumer_edges.items():
        assert all('<'+protocol+'>' in declarations[target]['inheritance'] for _, target in edges)
    selected_paths = closure(declarations, roots, lambda name: selected.get(name, ()), consumer_edges)
    assert set(selected) <= selected_paths.keys(), ('unreachable selected types', set(selected) - selected_paths.keys())
    full_paths = closure(declarations, roots, lambda name: declarations[name]['members'], consumer_edges)
    assert selected_paths.keys() <= full_paths.keys()
    # Every declaration is accounted for, including those outside either closure.
    types = {}
    for name, item in sorted(declarations.items()):
        members = item['members']
        types[name] = {
            'classification': ('selected_signature_or_value' if name in selected_paths else
                               'reachable_only_via_other_members' if name in full_paths else
                               'outside_candidate_signature_graph'),
            'inheritance_references': sorted(dependencies(item['inheritance']) - {name}),
            'selected_members': {key: members[key] for key in sorted(selected.get(name, ()))},
        }
        if name in selected_paths:
            types[name]['selected_path'] = selected_paths[name]
        if name in full_paths and name not in selected_paths:
            types[name]['full_path'] = full_paths[name]
    return {
        'profile': 'current-unfrozen' if current_candidate else 'historical-reviewed',
        'point_constructor_precondition_remaining': current_candidate,
        'selected_point_factory_nullable_checked': current_candidate,
        'structural_methods': len(methods), 'structural_properties': len(properties),
        'roots': sorted(roots), 'declared_types': len(types), 'selected_types': len(selected_paths),
        'all_members_reachable_types': len(full_paths),
        'outside_full_graph_types': len(types) - len(full_paths), 'types': types,
    }


def projected_header(header, report):
    """Compiler probe only. Preserve selected declarations in a copy of the real header."""
    pattern = re.compile(
        r'((?:^__attribute__[^\n]*\n)*^@(interface|protocol) (NCP\w+)([^;\n]*)\n)(.*?)^@end',
        re.M | re.S,
    )

    def project(match):
        start, _, name, tail, body = match.groups()
        if tail.lstrip().startswith('('):
            return match[0]
        item = report['types'][name]
        if 'selected_path' not in item:
            return ''
        allowed = set(item['selected_members'])
        lines = []
        for line in body.splitlines(keepends=True):
            if line.startswith(('- (', '+ (', '@property ')) and 'unavailable' not in line:
                swift_name = SWIFT_NAME.search(line)
                if swift_name is None or swift_name[1] not in allowed:
                    continue
            lines.append(line)
        return start + ''.join(lines) + '@end'

    stripped = re.sub(r'/\*.*?\*/', '', header, flags=re.S)
    return pattern.sub(project, stripped)



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('header', type=Path)
    parser.add_argument('--projected-header', type=Path)
    parser.add_argument('--current-candidate', action='store_true')
    parser.add_argument('--support-profile', type=Path,
                        help='Check the selected signature boundary against a reviewed fixed candidate profile')
    args = parser.parse_args()
    if args.projected_header and args.projected_header.resolve() == args.header.resolve():
        parser.error('projected header must be written to a different path')
    header = args.header.read_bytes()
    result = audit(header.decode('utf-8'), args.current_candidate)
    result['header_sha256'] = hashlib.sha256(header).hexdigest()
    if args.support_profile:
        profile_bytes = args.support_profile.read_bytes()
        profile = json.loads(profile_bytes)
        assert profile['schema'] == 'c1-supported-objc-profile-1', 'Unknown support profile'
        declarations = parse_header(header.decode('utf-8'))
        supported = {name: {'inheritance': declarations[name]['inheritance'], 'members': item['selected_members']}
                     for name, item in result['types'].items()
                     if item['classification'] == 'selected_signature_or_value'}
        assert supported == profile['support'], 'Selected SDK signature contract differs from reviewed profile'
        classified = {name: item['classification'] for name, item in result['types'].items()}
        assert classified == profile['type_classification'], 'Exported SDK type classification requires review'
        assert result['header_sha256'] == profile['header_sha256'], 'Candidate header differs from fixed profile'
        result['support_profile'] = {
            'sha256': hashlib.sha256(profile_bytes).hexdigest(),
            'candidate_source_commit': profile['candidate_source_commit'],
            'selected_signatures_matched': True,
        }
    if args.projected_header:
        projection = projected_header(header.decode('utf-8'), result)
        expected = {name: set(item['selected_members']) for name, item in result['types'].items()
                    if 'selected_path' in item}
        actual = {name: set(item['members']) for name, item in parse_header(projection).items()}
        assert actual == expected, 'Projected declarations differ from selected profile'
        args.projected_header.write_text(projection, encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
