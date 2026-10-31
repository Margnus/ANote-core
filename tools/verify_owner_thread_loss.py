#!/usr/bin/env python3
"""Diagnose the original executor after real creator-thread exit; never a recovery implementation."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'reports/owner-thread-loss-20261031'
WORK = ROOT / 'output/owner-thread-loss-20261031'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    RAW.mkdir(exist_ok=False); WORK.mkdir(parents=True, exist_ok=False)
    cache = ROOT / '.gradle-user-home/caches/modules-2/files-2.1'
    java = Path('/Users/lixiaofan/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home/bin/java')

    def jar(group, artifact, version):
        matches = list((cache / group / artifact / version).glob('*/' + artifact + '-' + version + '.jar'))
        assert len(matches) == 1, artifact
        return matches[0]

    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21')
    annotations = jar('org.jetbrains', 'annotations', '13.0')
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'), stdlib,
                jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
                jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.2.21'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), annotations]
    sources = [ROOT / 'canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/OwnedWork.kt',
               ROOT / 'canvas-core/src/jvmAndAndroidMain/kotlin/dev/notecore/canvas/prototype/BoundedOwnerExecutor.kt',
               ROOT / 'tools/probes/OwnerThreadLoss.kt', Path(__file__).resolve()]
    source_hashes = {p.relative_to(ROOT).as_posix(): sha(p) for p in sources}
    output = RAW / 'probe.jar'
    compile_command = [str(java), '-cp', os.pathsep.join(map(str, compiler)),
                       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                       '-jvm-target', '17', '-classpath', os.pathsep.join(map(str, [stdlib, annotations])),
                       '-d', str(output), *map(str, sources[:3])]
    compiled = subprocess.run(compile_command, cwd=ROOT, capture_output=True, timeout=120)
    (RAW / 'compile.stdout.log').write_bytes(compiled.stdout)
    (RAW / 'compile.stderr.log').write_bytes(compiled.stderr)
    assert compiled.returncode == 0, compiled.stderr.decode()
    receipt = WORK / 'observation.json'
    command = [str(java), '-cp', os.pathsep.join(map(str, [output, stdlib])),
               'dev.notecore.probes.OwnerThreadLossKt', str(receipt)]
    with (RAW / 'child.stdout.log').open('wb') as stdout, (RAW / 'child.stderr.log').open('wb') as stderr:
        child = subprocess.Popen(command, cwd=ROOT, stdout=stdout, stderr=stderr)
        try:
            deadline = time.monotonic() + 20
            while not receipt.exists():
                assert child.poll() is None, 'Child exited without evidence'
                assert time.monotonic() < deadline, 'Missing bounded observation'
                time.sleep(.05)
            observed = json.loads(receipt.read_text(encoding='utf-8'))
            assert observed['owner_terminated'] and observed['main_terminated']
            assert observed['worker_alive'] and not observed['worker_daemon']
            assert observed['entries'] == 2 and observed['charged_bytes'] == 16
            assert observed['computations'] == 2 and observed['deliveries'] == 0
            assert observed['retained_input_result_identity'] and observed['survivor_calls_rejected'] == 5
            # Both non-worker user threads exited; the admitted original worker keeps JVM alive.
            assert child.poll() is None
            (RAW / 'observation.json').write_bytes(receipt.read_bytes())
        finally:
            alive_before_cleanup = child.poll() is None
            if alive_before_cleanup:
                child.terminate()
            child.wait(timeout=10)
    assert all(sha(ROOT / p) == digest for p, digest in source_hashes.items())
    report = {'status': 'observed-unsupported-owner-loss', 'observations': observed,
              'child_pid': child.pid, 'child_alive_after_owner_and_main_exit': alive_before_cleanup,
              'cleanup': 'Parent terminated the entire isolated child process, not a production ACK/release.',
              'child_returncode': child.returncode, 'compile_argv': compile_command, 'child_argv': command,
              'source_sha256': source_hashes,
              'compiler_sha256': {str(p): sha(p) for p in compiler},
              'java_version': subprocess.check_output([str(java), '-version'], stderr=subprocess.STDOUT).decode(),
              'scope': 'Original JVM executor compiled unchanged. Real creator thread and main thread terminated; two accepted immutable inputs/results retained, surviving-thread methods refused, original non-daemon worker alive. Read-only private-field reflection is diagnostic, not a supported recovery port. No Android main-thread death, Swift queue loss, native resource, persisted recovery, takeover implementation or C1 approval.'}
    report['evidence_sha256'] = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(RAW.iterdir()) if p.is_file()}
    save(ROOT / 'reports/owner-thread-loss.json', report)
    print('Observed unsupported creator-thread loss; isolated child terminated', flush=True)


if __name__ == '__main__':
    main()
