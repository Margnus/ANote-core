#!/usr/bin/env python3
"""Offline text-hash/encoding checks; does not claim execution on Windows."""
import ast
import hashlib
import json
import re
import tempfile
from pathlib import Path, PureWindowsPath, PurePosixPath
from verification_hash import TEXT_SUFFIXES, verification_bytes, verification_digest

ROOT = Path(__file__).resolve().parents[1]


def main():
    checks = []
    with tempfile.TemporaryDirectory(prefix='notecore-portable-') as temporary:
        root = Path(temporary)
        lf = '中文😀\nsecond line\n'.encode('utf-8')
        crlf = lf.replace(b'\n', b'\r\n')
        for suffix in TEXT_SUFFIXES:
            first, second = root/('lf'+suffix), root/('crlf'+suffix)
            first.write_bytes(lf); second.write_bytes(crlf)
            assert verification_digest(first) == verification_digest(second) == hashlib.sha256(lf).hexdigest()
        checks.append('utf8_text_lf_crlf_same_verification_hash')
        for suffix in ('.bin', '.ncp', '.apk', '.zip', '.png'):
            first, second = root/('lf'+suffix), root/('crlf'+suffix)
            first.write_bytes(lf); second.write_bytes(crlf)
            assert verification_digest(first) != verification_digest(second)
            assert verification_bytes(second) == crlf
        checks.append('binary_and_interchange_artifacts_remain_byte_exact')
        # Do not replace whitespace or every CR: only CRLF is a checkout line-ending difference.
        path = root/'bare-cr.py'; path.write_bytes(b'a\rb\r\n')
        assert verification_bytes(path) == b'a\rb\n'
        checks.append('standalone_cr_is_not_silently_changed')
    reads = writes = 0
    for path in (ROOT/'tools').glob('*.py'):
        tree = ast.parse(path.read_text(encoding='utf-8'))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in {'read_text', 'write_text'}:
                assert any(k.arg == 'encoding' and isinstance(k.value, ast.Constant) and k.value.value.lower().replace('-', '') == 'utf8' for k in node.keywords), (path, node.lineno)
                if node.func.attr == 'read_text': reads += 1
                else: writes += 1
    checks.append('tool_text_io_explicit_utf8')
    assert PureWindowsPath(r'shared-core\src\commonMain\model.kt').as_posix() == PurePosixPath('shared-core/src/commonMain/model.kt').as_posix()
    for name in ('collect_verification.py', 'package_android_preview.py'):
        source = (ROOT/'tools'/name).read_text(encoding='utf-8')
        assert not re.search(r'str\(\w+\.relative_to\(ROOT\)\)', source)
    checks.append('stamp_keys_use_platform_independent_forward_slashes')
    print(json.dumps({'status': 'passed', 'checks': checks, 'text_reads': reads, 'text_writes': writes,
                      'windows_execution_verified': False, 'scope': 'synthetic LF/CRLF and explicit UTF-8 code checks on current host; no native cross-OS build claim'}))


if __name__ == '__main__':
    main()
