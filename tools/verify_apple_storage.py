#!/usr/bin/env python3
"""Actual Foundation filesystem failures on macOS, never iOS UI/crash durability evidence."""
import hashlib
import json
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def main():
    sources = [ROOT/'ios-app/NoteCorePreview/BoundedOwnerExecutor.swift', ROOT/'ios-app/NoteCorePreview/PreviewFileIO.swift', ROOT/'apple-runtime-tests/StorageConformance.swift']
    build = ROOT/'apple-runtime-tests/build'; build.mkdir(exist_ok=True, parents=True)
    subprocess.run(['xcrun', 'swiftc', '-swift-version', '5', '-warnings-as-errors', '-module-cache-path', str(build/'cache'),
                    *map(str, sources), '-o', str(build/'storage-tests')], check=True)
    report = json.loads(subprocess.run([str(build/'storage-tests')], capture_output=True, text=True, check=True, timeout=60).stdout)
    assert report['status'] == 'passed' and report['test_count'] == 6 and len(set(report['passed'])) == 6
    report['input_sha256'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + [Path(__file__)]}
    report['scope'] = 'macOS execution of same Foundation IO helper compiled into iOS; no UIKit, KMP model runtime or physical-device durability validation'
    (ROOT/'reports/apple-storage-conformance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({k: v for k, v in report.items() if k != 'input_sha256'}, ensure_ascii=False))
if __name__ == '__main__': main()
