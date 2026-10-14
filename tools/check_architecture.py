#!/usr/bin/env python3
"""Small C1 source boundary check, not a Kotlin compiler or full dependency audit."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = ('android.', 'androidx.', 'java.', 'javax.', 'platform.', 'com.jideos.',
             'dev.notecore.app.', 'io.ktor.', 'androidx.room.', 'app.cash.sqldelight.',
             'kotlinx.coroutines.', 'org.jetbrains.skia.')

def main():
    sources = sorted((ROOT/'canvas-core/src/commonMain').rglob('*.kt'))
    errors = []
    imports = 0
    for path in sources:
        for number, line in enumerate(path.read_text().splitlines(), 1):
            match = re.match(r'\s*import\s+([\w.]+)', line)
            if match:
                imports += 1
                if match.group(1).startswith(FORBIDDEN):
                    errors.append(f'{path.relative_to(ROOT)}:{number}: {match.group(1)}')
    # Prototype is an isolated module. App/plugin dependencies require an explicit architecture change.
    build = (ROOT/'canvas-core/build.gradle.kts').read_text()
    if re.search(r'(implementation|api|compileOnly)\s*\(\s*project\s*\(', build):
        errors.append('canvas-core has a project dependency')
    report = {'status': 'failed' if errors else 'passed', 'common_source_files': len(sources),
              'imports_checked': imports, 'errors': errors,
              'scope': 'commonMain imports and direct project dependencies; not a transitive bytecode audit'}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)

if __name__ == '__main__': main()
