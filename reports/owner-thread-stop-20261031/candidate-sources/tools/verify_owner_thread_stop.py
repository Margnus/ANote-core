#!/usr/bin/env python3
"""Before/after isolation for creator-termination worker stop; no ownership transfer."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    phase = sys.argv[1]
    assert phase in ('before', 'after')
    raw = ROOT / 'reports/owner-thread-stop-20261031' / phase
    raw.mkdir(parents=True, exist_ok=False)
    cache = ROOT / '.gradle-user-home/caches/modules-2/files-2.1'
    java = Path('/Users/lixiaofan/Library/Java/JavaVirtualMachines/jbr-17.0.14/Contents/Home/bin/java')
    def jar(g, a, v):
        candidates = list((cache/g/a/v).glob('*/' + a + '-' + v + '.jar'))
        assert len(candidates) == 1, a
        return candidates[0]
    dependencies = [jar(g, a, v) for g, a, v in [
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.2.21'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        ('org.jetbrains', 'annotations', '13.0')]]
    sources = [ROOT/'canvas-core/src/commonMain/kotlin/dev/notecore/canvas/prototype/OwnedWork.kt',
               ROOT/'canvas-core/src/jvmAndAndroidMain/kotlin/dev/notecore/canvas/prototype/BoundedOwnerExecutor.kt',
               ROOT/'tools/probes/OwnerThreadStop.kt']
    inputs = {p.relative_to(ROOT).as_posix(): sha(p) for p in [*sources, Path(__file__).resolve()]}
    (raw/'executed-executor.kt.txt').write_bytes(sources[1].read_bytes())
    output = raw/'probe.jar'
    command = [str(java), '-cp', os.pathsep.join(map(str, dependencies)),
               'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
               '-classpath', os.pathsep.join(map(str, dependencies[1:])), '-d', str(output), *map(str, sources)]
    compiled = subprocess.run(command, capture_output=True, timeout=120, cwd=ROOT)
    (raw/'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, compiled.stderr.decode()
    with (raw/'run.log').open('wb') as log:
        child = subprocess.Popen([str(java), '-cp', os.pathsep.join(map(str, [output, dependencies[1]])),
                                  'dev.notecore.probes.OwnerThreadStopKt'], stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
        forced = False
        try:
            code = child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            forced = True; child.terminate(); code = child.wait(timeout=10)
    result = (raw/'run.log').read_text(encoding='utf-8')
    passed = code == 0 and not forced and 'OWNER_THREAD_STOP_PASSED' in result
    assert all(sha(ROOT/p) == v for p, v in inputs.items())
    report = {'phase': phase, 'status': 'passed' if passed else 'failed', 'child_pid': child.pid,
              'child_returncode': code, 'parent_forced_process_cleanup': forced,
              'source_at_execution_sha256': inputs, 'compiler_sha256': {str(p):sha(p) for p in dependencies},
              'compile_argv': command, 'evidence_sha256': {p.relative_to(ROOT).as_posix():sha(p) for p in raw.iterdir() if p.is_file()},
              'scope': 'Real JVM creator termination: empty idle executor and running+queued original work. Foreign owner still refused; exact input/result/charge retained. Live idle owner with failed wake stays accepting. No takeover, ACK after death, native resource release, hung running job cancellation or physical-platform proof.'}
    (raw/'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'phase':phase, 'status':report['status'], 'forced_cleanup':forced, 'returncode':code}))
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
