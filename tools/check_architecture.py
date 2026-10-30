#!/usr/bin/env python3
"""Small C1 source boundary check, not a Kotlin compiler or full dependency audit."""
import json
import plistlib
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = ('android.', 'androidx.', 'java.', 'javax.', 'platform.', 'com.jideos.',
             'dev.notecore.app.', 'dev.notecore.sample.', 'io.ktor.', 'androidx.room.', 'app.cash.sqldelight.',
             'kotlinx.coroutines.', 'org.jetbrains.skia.')

def main():
    sources = [p for module in ('canvas-core', 'shared-core') for p in sorted((ROOT/module/'src/commonMain').rglob('*.kt'))]
    errors = []
    imports = 0
    for path in sources:
        for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            match = re.match(r'\s*import\s+([\w.]+)', line)
            if match:
                imports += 1
                if match.group(1).startswith(FORBIDDEN) or ('canvas-core' in path.parts and match.group(1).startswith('dev.notecore.document.')):
                    errors.append(f'{path.relative_to(ROOT)}:{number}: {match.group(1)}')
    # Prototype is an isolated module. App/plugin dependencies require an explicit architecture change.
    build = (ROOT/'canvas-core/build.gradle.kts').read_text(encoding='utf-8')
    if re.search(r'(implementation|api|compileOnly)\s*\(\s*project\s*\(', build):
        errors.append('canvas-core has a project dependency')
    domain_build = (ROOT/'shared-core/build.gradle.kts').read_text(encoding='utf-8')
    domain_dependencies = re.findall(r'(?:implementation|api|compileOnly)\s*\(\s*project\s*\(\s*"([^"]+)"', domain_build)
    if domain_dependencies != [':canvas-core']: errors.append('shared-core must depend only on canvas-core')
    sample_build = (ROOT/'samples/sketch-host/build.gradle.kts').read_text(encoding='utf-8')
    sample_dependencies = re.findall(r'(?:implementation|api|compileOnly)\s*\(\s*project\s*\(\s*"([^"]+)"', sample_build)
    if sample_dependencies != [':shared-core']: errors.append('Independent sketch host must depend only on shared-core')
    for path in (ROOT/'samples/sketch-host/src').rglob('*.kt'):
        if re.search(r'^\s*import\s+dev\.notecore\.app\.', path.read_text(encoding='utf-8'), re.M):
            errors.append(f'Sample imports NoteCore App: {path.relative_to(ROOT)}')
    apple_sample = ROOT/'samples/apple-sketch'
    apple_objects = plistlib.loads((apple_sample/'NoteCoreSketch.xcodeproj/project.pbxproj').read_bytes())['objects']
    apple_refs = [v['path'] for v in apple_objects.values() if v['isa'] == 'PBXFileReference']
    if any('ios-app' in p or 'NoteCorePreview' in p for p in apple_refs):
        errors.append('Independent Apple sketch references the Preview App')
    apple_imports = set()
    for path in apple_sample.rglob('*.swift'):
        names = re.findall(r'^\s*(?:@testable\s+)?import\s+(\w+)', path.read_text(encoding='utf-8'), re.M)
        apple_imports.update(names)
        if set(names) - {'UIKit', 'XCTest', 'NoteCanvasPrototype', 'NoteCoreSketch'}:
            errors.append(f'Unexpected Apple sketch dependency: {path.relative_to(ROOT)}')
    retired = re.compile(r'\b(?:PreviewNotebook|PreviewNotebookSnapshot|PreviewNotebookHeader|LegacyPreviewSaveSnapshot)\b')
    roots = ['canvas-core/src', 'shared-core/src', 'android-app/src', 'ios-app/NoteCorePreview', 'ios-app/NoteCorePreviewTests', 'samples/sketch-host/src', 'samples/apple-sketch']
    for root in roots:
        for path in (ROOT/root).rglob('*'):
            if path.suffix in {'.kt', '.swift'} and retired.search(path.read_text(encoding='utf-8')):
                errors.append(f'Retired single-page editor/save stack referenced: {path.relative_to(ROOT)}')
    report = {'status': 'failed' if errors else 'passed', 'common_source_files': len(sources),
              'imports_checked': imports, 'errors': errors,
              'modules': ['canvas-core', 'shared-core'], 'project_dependency_direction': 'shared-core -> canvas-core only',
              'independent_sketch_host_project_dependencies': sample_dependencies,
              'independent_apple_sketch_imports': sorted(apple_imports),
              'retired_single_page_stack_absent': not any('Retired single-page' in e for e in errors),
              'scope': 'commonMain imports and direct project dependencies; not a transitive bytecode audit'}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)

if __name__ == '__main__': main()
