#!/usr/bin/env python3
"""Reject concrete declaration-shape drift in copies of the actual retained compiler header."""
from pathlib import Path
import hashlib
import json
import re
from audit_public_boundary import (ROOT, HEADER, inspect_header, check_candidate_channels,
                                   check_property_channels, CANDIDATE_PROPERTIES)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(text):
    declarations = inspect_header(text)
    return check_candidate_channels(declarations), check_property_channels(declarations, CANDIDATE_PROPERTIES)


def rewrite(text, name, member, change):
    pattern = re.compile(r'(^@(interface|protocol) '+re.escape(name)+r'\b[^;\n]*\n)(.*?)(^@end)', re.M | re.S)
    matches = list(pattern.finditer(text)); assert len(matches) == 1, name
    match = matches[0]; lines = match[3].splitlines(keepends=True)
    indexes = [i for i, line in enumerate(lines) if 'swift_name("'+member+'")' in line]
    assert len(indexes) == 1, (name, member)
    index = indexes[0]; original = lines[index]; lines[index] = change(original)
    assert lines[index] != original
    return text[:match.start(3)] + ''.join(lines) + text[match.end(3):]


def main():
    path = ROOT/HEADER
    before = sha(path); original = path.read_text(encoding='utf-8')
    methods, properties = check(original)
    cases = [
        ('missing_recovery_entry', 'NCPPagedPreviewNotebookCompanion', 'openRecovery(snapshot:sessionNonce:budget:ownerCheck:)', lambda line: '', 'Missing/duplicate candidate method'),
        ('prepare_loses_nullable_return', 'NCPPagedPreviewNotebook', 'prepareRealBatch(phase:count:)', lambda line: line.replace('NCPPreviewInputResult * _Nullable', 'NCPPreviewInputResult *'), 'Changed candidate nullable return'),
        ('capture_switches_to_nserror', 'NCPPagedPreviewNotebook', 'captureRecovery()', lambda line: line.replace('captureRecovery ', 'captureRecoveryError:(NSError * _Nullable * _Nullable)error '), 'Changed candidate NSError channel'),
        ('open_result_loses_nullable_notebook', 'NCPPreviewNotebookOpen', 'notebook', lambda line: line.replace(' * _Nullable ', ' *'), 'Changed reviewed property shape'),
        ('failure_becomes_mutable', 'NCPPreviewInputResult', 'failure', lambda line: line.replace('(readonly)', '(readwrite)'), 'Changed reviewed property qualifiers'),
        ('failure_becomes_class_global', 'NCPPreviewInputResult', 'failure', lambda line: line.replace('(readonly)', '(class, readonly)'), 'Changed reviewed property shape'),
        ('encode_loses_nserror_channel', 'NCPPagedPreviewSnapshot', 'encode()', lambda line: line.replace('encodeAndReturnError:(NSError * _Nullable * _Nullable)error ', 'encode '), 'Changed candidate NSError channel'),
    ]
    rejected = []
    for name, declaration, member, change, expected in cases:
        altered = rewrite(original, declaration, member, change)
        try:
            check(altered)
        except AssertionError as error:
            assert expected in str(error), (name, str(error))
            rejected.append({'case': name, 'objc_name': declaration, 'swift_name': member, 'reason': str(error)})
        else:
            raise AssertionError(('Unsafe candidate declaration change accepted', name))
    assert sha(path) == before
    retained = ROOT/'reports/candidate-public-boundary-20261031/NoteCanvasPrototype.h'
    retained.parent.mkdir(parents=True, exist_ok=True)
    retained.write_bytes(path.read_bytes())
    assert sha(retained) == before
    source = [Path(__file__), ROOT/'tools/audit_public_boundary.py', path]
    report = {'schema': 'candidate-public-boundary-guards-1', 'status': 'passed',
              'actual_header_candidate_methods': len(methods), 'actual_header_candidate_properties': len(properties),
              'changed_headers_rejected': rejected, 'actual_header_unchanged': True,
              'retained_header_sha256': {retained.relative_to(ROOT).as_posix(): before},
              'source_and_header_sha256': {p.relative_to(ROOT).as_posix(): sha(p) for p in source},
              'scope': 'Structural guard mutations of in-memory copies of a real source-bound compiler header. No compiler, Native execution, semantic failure/owner/index conformance, full ABI type layout or SDK freeze claim.',
              'c1_complete': False, 'whole_requirement_accepted': False}
    (ROOT/'reports/candidate-public-boundary-guards.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({'status': 'passed', 'candidate_methods': len(methods), 'candidate_properties': len(properties), 'mutations_rejected': len(rejected)}))


if __name__ == '__main__':
    main()
