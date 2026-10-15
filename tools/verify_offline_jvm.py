#!/usr/bin/env python3
"""Socket-free cached compiler/JUnit validation; never replace Gradle/native build evidence."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--measure-reduction', action='store_true')
    parser.add_argument('--measure-chunks', action='store_true')
    parser.add_argument('--native-library', action='store_true', help='Also compile two iOS-target KLIBs; no framework or simulator execution')
    args = parser.parse_args()
    java = Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name == 'nt' else 'java')
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(ROOT/'.gradle-user-home')))/'caches/modules-2/files-2.1'
    build = ROOT/'canvas-core/build/offline-jvm'
    build.mkdir(parents=True, exist_ok=True)
    def jar(group, artifact, version):
        matches = list((cache/group/artifact/version).glob('*/*.jar'))
        assert len(matches) == 1, 'Cached exact dependency unavailable: '+artifact+':'+version
        return matches[0]
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21')
    annotations = jar('org.jetbrains', 'annotations', '13.0')
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'), stdlib,
        jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'), jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        jar('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.2.21'),
        jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), annotations]
    testing = [jar('org.jetbrains.kotlin', 'kotlin-test', '2.2.21'), jar('org.jetbrains.kotlin', 'kotlin-test-junit', '2.2.21'),
        jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3'),
        jar('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.9.0'),
        jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.9.0')]
    groups = {
        'canvas-main': sorted([*ROOT.glob('canvas-core/src/commonMain/**/*.kt'), *ROOT.glob('canvas-core/src/jvmAndAndroidMain/**/*.kt'), *ROOT.glob('canvas-core/src/jvmMain/**/*.kt')]),
        'shared-main': sorted(ROOT.glob('shared-core/src/commonMain/**/*.kt')),
        'canvas-tests': sorted([*ROOT.glob('canvas-core/src/commonTest/**/*.kt'), *ROOT.glob('canvas-core/src/jvmTest/**/*.kt')]),
        'shared-tests': sorted([*ROOT.glob('shared-core/src/commonTest/**/*.kt'), *ROOT.glob('shared-core/src/jvmTest/**/*.kt')]),
    }
    inputs = sorted({p for files in groups.values() for p in files} | set(ROOT.glob('fixtures/**/*.json')) |
        set(ROOT.glob('.generated-fixtures/scale/*.bin')) | {Path(__file__).resolve(), ROOT/'tools/verification_hash.py', ROOT/'build.gradle.kts'})
    assert 'version "2.2.21"' in (ROOT/'build.gradle.kts').read_text(encoding='utf-8'), 'Offline compiler version must be reviewed against project Kotlin version'
    before = {p.relative_to(ROOT).as_posix(): verification_digest(p) for p in inputs}
    outputs = {name: build/(name+'.jar') for name in groups}
    def classpath(files):
        return os.pathsep.join(str(p) for p in files)
    def run(command, name, timeout=180):
        log = build/(name+'.log')
        with log.open('w', encoding='utf-8') as stream:
            completed = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        if completed.returncode:
            raise RuntimeError(name+' failed:\n'+log.read_text(encoding='utf-8')[-14000:])
        return log.read_text(encoding='utf-8')
    def compile_group(name, dependencies, friend=None):
        outputs[name].unlink(missing_ok=True)
        command = [str(java), '-Xmx2g', '-cp', classpath(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-module-name', name+'-offline',
            '-classpath', classpath([stdlib, annotations, *dependencies]), '-d', str(outputs[name])]
        if friend: command.append('-Xfriend-paths='+str(outputs[friend]))
        run([*command, *map(str, groups[name])], name)
    # Keep both main modules separate and grant each test group friendship only to its own module.
    compile_group('canvas-main', [])
    compile_group('shared-main', [outputs['canvas-main']])
    compile_group('canvas-tests', [outputs['canvas-main'], *testing], 'canvas-main')
    compile_group('shared-tests', [outputs['canvas-main'], outputs['shared-main'], *testing], 'shared-main')
    test_classes = []
    for name in ('canvas-tests', 'shared-tests'):
        with zipfile.ZipFile(outputs[name]) as archive:
            test_classes.extend(n[:-6].replace('/', '.') for n in archive.namelist() if n.endswith('Test.class') and '$' not in n)
    cp = [*outputs.values(), stdlib, annotations, *testing, ROOT/'fixtures/input', ROOT/'fixtures/geometry']
    properties = ['-Dnotecore.scale.fixture.dir='+str(ROOT/'.generated-fixtures/scale'),
                  '-Dnotecore.scale.manifest='+str(ROOT/'fixtures/scale/manifest.json')]
    output = run([str(java), *properties, '-cp', classpath(cp), 'org.junit.runner.JUnitCore', *sorted(test_classes)], 'tests')
    count = re.search(r'^OK \((\d+) tests\)', output, re.MULTILINE)
    assert count, output
    if args.measure_reduction:
        run([str(java), '-cp', classpath(cp), 'dev.notecore.canvas.prototype.BrushReductionCosts', str(ROOT)], 'reduction-costs')
    if args.measure_chunks:
        run([str(java), '-cp', classpath(cp), 'dev.notecore.canvas.prototype.ChunkedBrushCosts', str(ROOT)], 'chunk-costs')
    native_outputs = []
    native_version = None
    if args.native_library:
        native = Path(os.environ['KOTLIN_NATIVE_HOME'])/'bin'/('konanc.bat' if os.name == 'nt' else 'konanc')
        native_version = run([str(native), '-version'], 'native-version').strip()
        assert '2.2.21' in native_version, native_version
        for name, sources, dependency in (
            ('canvas-native', sorted(ROOT.glob('canvas-core/src/commonMain/**/*.kt')), None),
            ('shared-native', groups['shared-main'], 'canvas-native'),
        ):
            output_file = build/(name+'.klib')
            output_file.unlink(missing_ok=True)
            command = [str(native), '-produce', 'library', '-target', 'ios_simulator_arm64', '-o', str(output_file)]
            if dependency: command.extend(['-library', str(build/(dependency+'.klib'))])
            run([*command, *map(str, sources)], name)
            assert output_file.exists(), name
            native_outputs.append(output_file)
    assert before == {p.relative_to(ROOT).as_posix(): verification_digest(p) for p in inputs}, 'Sources changed during offline verification'
    for script in ('check_architecture.py', 'verify_portable_inputs.py'):
        run([sys.executable, str(ROOT/'tools'/script)], script)
    raw = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    report = {'status': 'passed', 'mode': 'cached Kotlin compiler + JUnit; separate main/test modules, no Gradle or network',
        'tests': int(count.group(1)), 'test_classes': sorted(test_classes), 'failures': 0,
        'source_and_fixture_sha256': before, 'output_jar_sha256': {p.relative_to(ROOT).as_posix(): raw(p) for p in outputs.values()},
        'compiler_and_runtime_sha256': {p.relative_to(cache).as_posix(): raw(p) for p in set([*compiler, *testing])},
        'test_log_sha256': raw(build/'tests.log'), 'reduction_measurement_run': args.measure_reduction,
        'chunk_measurement_run': args.measure_chunks,
        'ios_target_klib_compilation_verified': args.native_library, 'native_compiler_version': native_version,
        'native_library_sha256': {p.relative_to(ROOT).as_posix(): raw(p) for p in native_outputs},
        'gradle_verification_refreshed': False, 'android_build_verified': False, 'apple_build_verified': False,
        'physical_device_measured': False,
        'scope': 'Fresh isolated JVM compilation and real JUnit execution with original module direction/friend paths. Optional iOS-target KLIB compilation is recorded separately. No Gradle plugin/task/resource packaging, framework linking, native execution or device proof. Previous Gradle/runtime reports remain historical.'}
    (ROOT/'reports/offline-jvm-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('source_and_fixture_sha256', 'output_jar_sha256', 'compiler_and_runtime_sha256', 'test_classes')}))


if __name__ == '__main__':
    main()
