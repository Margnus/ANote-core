import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import stat
import subprocess
import time
import uuid
import zipfile

from verification_hash import verification_digest

ROOT = Path(__file__).resolve().parents[1]
RECOVERY_SOURCE = ROOT / 'reports/sdk-artifact-boundary-20261107/android/RecoveryConsumer.kt'
HISTORICAL_CONSUMER = ROOT / 'reports/sdk-artifact-boundary-20261107/android/recovery-consumer.jar'
JAVA_CONSUMER = ROOT / 'tools/probes/C1AndroidFailureConsumer.java'
ORACLE = ROOT / 'reports/c1-android-failure-channels-20261118.json'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def executable(home, name):
    suffix = '.exe' if os.name == 'nt' else ''
    return home / 'bin' / (name + suffix)


def sdk_executable(sdk, relative):
    path = sdk / relative
    if os.name == 'nt' and not path.exists():
        path = path.with_name(path.name + '.exe')
    return path


def remove_tree(path):
    def onerror(function, target, _):
        os.chmod(target, stat.S_IWRITE)
        function(target)
    shutil.rmtree(path, onerror=onerror)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', type=Path)
    parser.add_argument('--port', type=int, default=5588)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/android-sdk-candidate-current')
    parser.add_argument('--report', type=Path, default=ROOT / 'reports/android-sdk-candidate-current.json')
    parser.add_argument('--resolve-build-dependencies', action='store_true',
                        help='Allow Gradle to fetch external app build tools; candidate artifacts still come from the local generated repository')
    args = parser.parse_args()
    sdk = Path(os.environ['ANDROID_HOME'])
    jdk = Path(os.environ['JAVA_HOME'])
    gradle_home = Path(os.environ['GRADLE_USER_HOME'])
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    log = output / 'commands.log'
    log.write_text('', encoding='utf-8')

    def run(*command, codes=(0,), timeout=120):
        command = [str(part) for part in command]
        result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8',
                                errors='replace', timeout=timeout)
        with log.open('a', encoding='utf-8') as stream:
            stream.write(f'{result.returncode} {shlex.join(command)}\n{result.stdout}{result.stderr}\n')
        if result.returncode not in codes:
            raise RuntimeError(f'{shlex.join(command)} exited {result.returncode}; see {log}')
        return result.stdout.strip()

    repository = ROOT / 'build/sdk-candidate-repository'
    group = repository / 'dev/notecore/probe'
    version = '0.0.0-c1'
    forbidden = (
        'PreviewSaveLedger', 'PreviewSaveAttempt', 'PreviewSaveState', 'PreviewSaveAdmission',
        'PreviewWriteAdmission', 'PreviewWriteDisposition', 'PreviewStreamReader',
        'PreviewAppRecoveryPreflight', 'PreviewEditorAdmission', 'AndroidPreviewTextCodec',
        'AndroidInkFrameExperiment', 'AndroidBrushImagePoc', 'DocumentCloseCoordinator',
        'OwnedWork', 'ResourceLeasePool', 'BrushRenderOwnerPoc', 'BrushTileCachePoc',
        'BrushTilePixelsPoc', 'SubmissionQueue', 'Presentation', 'ChunkedBrushPoc',
        'WeightedLruCache', 'BoundedOwnerExecutor', 'BrushRenderExecutorPoc', 'PrototypeDemo',
        'dev/notecore/library/',
    )
    classes = []
    aars = {}
    for module in ('canvas-core', 'shared-core'):
        artifact_name = f'{module}-android-{version}.aar'
        artifact = group / f'{module}-android/{version}/{artifact_name}'
        aars[artifact_name] = artifact
        with zipfile.ZipFile(artifact) as aar:
            bytecode = aar.read('classes.jar')
            with zipfile.ZipFile(__import__('io').BytesIO(bytecode)) as jar:
                leaked = [name for name in jar.namelist() if any(token in name for token in forbidden)]
                assert not leaked, (artifact_name, leaked)
        destination = output / f'{module}-android.jar'
        destination.write_bytes(bytecode)
        classes.append(destination)
    for sources in group.rglob('*sources.jar'):
        with zipfile.ZipFile(sources) as jar:
            leaked = [name for name in jar.namelist() if any(token in name for token in forbidden)]
            assert not leaked, (sources, leaked)

    cache = gradle_home / 'caches/modules-2/files-2.1'

    def dependency(group_name, name, dependency_version):
        matches = list((cache / group_name / name / dependency_version).glob(f'*/{name}-{dependency_version}.jar'))
        assert len(matches) == 1, (group_name, name, dependency_version, matches)
        return matches[0]

    kotlin = 'org.jetbrains.kotlin'
    kotlin_version = '2.2.21'
    stdlib = dependency(kotlin, 'kotlin-stdlib', kotlin_version)
    compiler_jars = [stdlib] + [dependency(kotlin, f'kotlin-{name}', kotlin_version)
                                for name in ('compiler-embeddable', 'daemon-embeddable', 'script-runtime')]
    compiler_jars += [dependency(kotlin, 'kotlin-reflect', '1.6.10'),
                      dependency('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                      dependency('org.jetbrains', 'annotations', '13.0')]
    java = executable(jdk, 'java')
    javac = executable(jdk, 'javac')

    def cp(paths):
        return os.pathsep.join(map(str, paths))

    compiler = [java, '-cp', cp(compiler_jars), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-cp', cp([*classes, stdlib])]
    refusal_sources = {
        'AppPorts': (ROOT / 'reports/sdk-maven-metadata-20261110/AppPorts.kt.txt',
                     ('PreviewSaveLedger', 'previewEditorAdmissionFailure')),
        'RenderPort': (ROOT / 'reports/sdk-maven-metadata-20261110/RenderPort.kt.txt',
                       ('AndroidInkFrameExperiment',)),
    }
    refusal_results = []
    for name, (template, symbols) in refusal_sources.items():
        probe = output / f'{name}.kt'
        probe.write_bytes(template.read_bytes())
        result = run(*compiler, '-d', output / f'{name}.jar', probe, codes=(1,))
        combined = log.read_text(encoding='utf-8')
        assert all(symbol in combined for symbol in symbols), (name, symbols)
        refusal_results.append(name)
    generated_refusals = {
        'LibraryPort': 'package dev.notecore.probe\nimport dev.notecore.library.LibrarySearchRules\nfun port() = LibrarySearchRules\n',
        'ExecutorPort': 'package dev.notecore.probe\nimport dev.notecore.canvas.prototype.BoundedOwnerExecutor\nfun port() = BoundedOwnerExecutor::class\n',
    }
    for name, content in generated_refusals.items():
        probe = output / f'{name}.kt'
        probe.write_text(content, encoding='utf-8')
        run(*compiler, '-d', output / f'{name}.jar', probe, codes=(1,))
        refusal_results.append(name)

    current_consumer = output / 'recovery-current.jar'
    run(*compiler, '-d', current_consumer, RECOVERY_SOURCE)
    fixtures = list(ROOT.glob('reports/view-pending-process-20261106/process-checkpoint-*/prepare.ncp'))
    assert len(fixtures) == 1, fixtures
    pending = fixtures[0]
    confirmed = pending.with_name('confirmed-final.ncp')
    current_run = run(java, '-cp', cp([current_consumer, *classes, stdlib]),
                      'dev.notecore.probe.RecoveryConsumerKt', pending, confirmed)
    assert 'AAR consumer recovered one stroke / four points / revision 2' in current_run
    historical_run = run(java, '-cp', cp([HISTORICAL_CONSUMER, *classes, stdlib]),
                         'dev.notecore.probe.RecoveryConsumerKt', pending, confirmed)
    assert 'AAR consumer recovered one stroke / four points / revision 2' in historical_run

    android_jar = sdk / 'platforms/android-36/android.jar'
    d8 = sdk / 'build-tools/36.1.0/lib/d8.jar'
    java_classes = output / 'java-classes'
    java_classes.mkdir(exist_ok=True)
    run(javac, '--release', '8', '-cp', cp([android_jar, *classes, stdlib]),
        '-d', java_classes, JAVA_CONSUMER)
    dex = output / 'failure-consumer.jar'
    run(java, '-Xmx512m', '-cp', d8, 'com.android.tools.r8.D8', '--min-api', '26',
        '--lib', android_jar, '--output', dex, *sorted(java_classes.rglob('*.class')), *classes, stdlib)

    consumer_root = output / 'maven-consumer'
    (consumer_root / 'src/main/java/dev/notecore/candidateconsumer').mkdir(parents=True, exist_ok=True)
    (consumer_root / 'src/main/AndroidManifest.xml').write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
        '<application android:label="DetachedCandidate"><activity android:name=".MainActivity" android:exported="true">'
        '<intent-filter><action android:name="android.intent.action.MAIN"/>'
        '<category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>',
        encoding='utf-8')
    (consumer_root / 'src/main/java/dev/notecore/candidateconsumer/MainActivity.kt').write_text(
        'package dev.notecore.candidateconsumer\nimport android.app.Activity\n'
        'import dev.notecore.document.PagedPreviewNotebook\n'
        'class MainActivity : Activity() { fun candidateType() = PagedPreviewNotebook::class }\n', encoding='utf-8')
    (consumer_root / 'settings.gradle.kts').write_text(
        'pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }\n'
        'dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); '
        'repositories { maven { url = uri("' + repository.as_uri() + '") }; google(); mavenCentral() } }\n'
        'rootProject.name = "DetachedCandidate"\n', encoding='utf-8')
    (consumer_root / 'build.gradle.kts').write_text(
        'plugins { id("com.android.application") version "8.10.1"; kotlin("android") version "2.2.21" }\n'
        'android { namespace = "dev.notecore.candidateconsumer"; compileSdk = 36; '
        'defaultConfig { applicationId = "dev.notecore.candidateconsumer"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.0.0-c1" }; '
        'compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }\n'
        'kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }\n'
        'dependencies { implementation("dev.notecore.probe:shared-core:0.0.0-c1") }\n', encoding='utf-8')
    wrapper = ['cmd', '/c', ROOT / 'gradlew.bat'] if os.name == 'nt' else [ROOT / 'gradlew']
    dependency_mode = [] if args.resolve_build_dependencies else ['--offline']
    insight = run(*wrapper, *dependency_mode, '--no-daemon', '--console=plain', '-p', consumer_root,
                  'assembleDebug', 'dependencyInsight', '--configuration', 'debugRuntimeClasspath',
                  '--dependency', 'canvas-core', timeout=300)
    assert 'dev.notecore.probe:canvas-core' in insight
    external_apk = consumer_root / 'build/outputs/apk/debug/DetachedCandidate-debug.apk'
    retained_apk = output / 'external-consumer.apk'
    shutil.copyfile(external_apk, retained_apk)
    external = {'apk_assembled': True, 'transitive_canvas_core_resolved': True,
                'apk_sha256': sha(retained_apk), 'ui_verified': False}

    art = {'status': 'not_run', 'minimum_api26_verified': False,
           'reason': 'No --image supplied; JDK, external compilation and DEX packaging passed'}
    if args.image is not None:
        image = args.image.resolve()
        assert (image / 'system.img').exists(), image
        adb = sdk_executable(sdk, 'platform-tools/adb')
        emulator_binary = sdk_executable(sdk, 'emulator/emulator')
        serial = f'emulator-{args.port}'
        assert not any(line.split()[:1] == [serial] for line in run(adb, 'devices').splitlines())
        name = 'NoteCoreCandidate-' + uuid.uuid4().hex
        image_api = next(part for part in image.parts if part.startswith('android-'))
        avds = output / 'avd'
        avd = avds / (name + '.avd')
        avd.mkdir(parents=True)
        (avds / (name + '.ini')).write_text(f'avd.ini.encoding=UTF-8\npath={avd}\ntarget={image_api}\n', encoding='utf-8')
        abi = 'x86_64' if 'x86_64' in image.parts else 'arm64'
        config = {'AvdId': name, 'avd.ini.displayname': name,
                  'abi.type': 'x86_64' if abi == 'x86_64' else 'arm64-v8a',
                  'hw.cpu.arch': abi, 'hw.cpu.ncore': '4', 'hw.ramSize': '2048',
                  'image.sysdir.1': str(image) + '/', 'tag.id': 'default',
                  'tag.display': 'Default Android System Image', 'disk.dataPartition.size': '2147483648',
                  'hw.lcd.width': '1080', 'hw.lcd.height': '1920', 'hw.lcd.density': '420'}
        (avd / 'config.ini').write_text(''.join(f'{key}={value}\n' for key, value in config.items()), encoding='utf-8')
        emulator_env = {**os.environ, 'ANDROID_AVD_HOME': str(avds), 'ANDROID_EMULATOR_HOME': str(output),
                        'ANDROID_USER_HOME': str(output)}

        def device(*command, **kwargs):
            return run(adb, '-s', serial, *command, **kwargs)

        with (output / 'emulator.log').open('wb') as emulator_log:
            emulator = subprocess.Popen([emulator_binary, '-avd', name, '-port', str(args.port),
                                         '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader_indirect'],
                                        env=emulator_env, stdout=emulator_log, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    assert emulator.poll() is None, 'emulator exited; see emulator.log'
                    if device('shell', 'getprop', 'sys.boot_completed', codes=(0, 1, 255), timeout=10) == '1':
                        break
                    time.sleep(2)
                else:
                    raise RuntimeError('isolated emulator did not boot')
                runtime = {key: device('shell', *command) for key, command in {
                    'api': ('getprop', 'ro.build.version.sdk'), 'fingerprint': ('getprop', 'ro.build.fingerprint'),
                    'abi': ('getprop', 'ro.product.cpu.abi'), 'selinux': ('getenforce',), 'kernel': ('uname', '-r'),
                }.items()}
                remote = '/data/local/tmp/' + name
                device('shell', 'mkdir', remote)
                for path in (dex, pending, confirmed):
                    deployed = f'{remote}/{path.name}'
                    device('push', path, deployed)
                    assert device('shell', 'sha256sum', deployed).split()[0] == sha(path)
                receipt = json.loads(device('shell', f'CLASSPATH={remote}/{dex.name} /system/bin/app_process /system/bin '
                                            f'dev.notecore.probes.C1AndroidFailureConsumer '
                                            f'{remote}/prepare.ncp {remote}/confirmed-final.ncp --current-candidate'))
                package = 'dev.notecore.candidateconsumer'
                device('install', '-r', retained_apk, timeout=120)
                launch = device('shell', 'am', 'start', '-W', f'{package}/.MainActivity', timeout=60)
                assert 'Status: ok' in launch, launch
                pid = device('shell', 'pidof', package)
                assert pid and all(part.isdigit() for part in pid.split()), pid
                device('shell', 'am', 'force-stop', package)
                device('uninstall', package, timeout=120)
                external['runtime_install_launch'] = True
                external['runtime_api'] = runtime['api']
            finally:
                if emulator.poll() is None:
                    try:
                        device('emu', 'kill', codes=(0, 1, 255), timeout=10)
                    except (RuntimeError, subprocess.TimeoutExpired):
                        pass
                    try:
                        emulator.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        emulator.terminate()
                        try:
                            emulator.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            emulator.kill()
                            emulator.wait(timeout=10)
                remove_tree(avds)
        oracle = json.loads(ORACLE.read_text(encoding='utf-8'))
        assert receipt.pop('pid') > 0
        exceptions = {key: receipt.pop(key) for key in (
            'budget_constructor_exception', 'budget_copy_exception', 'malformed_recovery_exception')}
        assert all(exceptions.values())
        assert receipt == oracle['selected_typed_channels_and_recovery'], (oracle, receipt)
        art = {'status': 'passed', 'runtime': runtime, 'selected_typed_channels_and_recovery': receipt,
               'compatibility_exceptions': exceptions, 'typed_observations_match_oracle': True,
               'minimum_api26_verified': runtime['api'] == '26'}

    inputs = [Path(__file__).resolve(), RECOVERY_SOURCE, HISTORICAL_CONSUMER, JAVA_CONSUMER, ORACLE,
              pending, confirmed, ROOT / 'settings.gradle.kts', ROOT / 'build.gradle.kts',
              ROOT / 'canvas-core/build.gradle.kts', ROOT / 'shared-core/build.gradle.kts']
    source_hashes = {path.relative_to(ROOT).as_posix(): verification_digest(path) for path in inputs}
    artifact_hashes = {path.relative_to(ROOT).as_posix(): sha(path) for path in sorted(repository.rglob('*')) if path.is_file()}
    artifact_hashes.update({path.relative_to(ROOT).as_posix(): sha(path)
                            for path in (current_consumer, dex, output / 'canvas-core-android.jar',
                                         output / 'shared-core-android.jar', retained_apk)})
    report = {
        'status': 'passed',
        'candidate_source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'candidate_version': version,
        'external_build_dependencies_online': args.resolve_build_dependencies,
        'scope': 'Current-source JVM/Android candidate artifacts, forbidden-port audit, independent source and historical-binary recovery consumers, Java failure-channel DEX; optional isolated ART execution',
        'forbidden_port_audits': {'aar_bytecode': True, 'maven_source_archives': True,
                                  'external_compilation_refused': refusal_results},
        'recovery': {'source_compiled_current_aar': True, 'historical_binary_current_aar': True,
                     'denied_bytes': 2247, 'admitted_bytes': 2248, 'strokes': 1, 'points': 4,
                     'revision': 2, 'repeat_completion_idempotent': True, 'files_byte_exact': True},
        'java_failure_consumer': {'source_compiled_current_aar': True, 'min_api_dex': 26},
        'external_maven_consumer': external,
        'art': art,
        'source_sha256': source_hashes,
        'artifact_sha256': artifact_hashes,
        'android_aars_sha256': {name: sha(path) for name, path in aars.items()},
        'consumer_dex_sha256': sha(dex),
        'current_consumer_sha256': sha(current_consumer),
        'historical_consumer_sha256': sha(HISTORICAL_CONSUMER),
        'raw_sha256': {path.name: sha(path) for path in sorted(output.iterdir()) if path.is_file()},
        'production_sdk_frozen': False,
        'c1_gate_complete': False,
        'ios_17_verified': False,
        'physical_device_verified': False,
        'production_sqlite_authorized': False,
    }
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({key: value for key, value in report.items()
                      if key not in ('source_sha256', 'artifact_sha256', 'raw_sha256')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
