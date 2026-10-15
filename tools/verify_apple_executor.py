#!/usr/bin/env python3
"""Run real Foundation threads on macOS; compare protocol cases with actual JVM XML."""
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    'fifo_finite_drain': 'realWorkerExecutesFifoAndOwnerReceivesFiniteBatches',
    'all_states_capacity_stop': 'runningQueuedAndReadyJobsAllConsumeCapacityAndRejectedWorkNeverRuns',
    'byte_bounds': 'byteAdmissionUsesRemainingBudgetWithoutOverflowAndRejectsInvalidCharge',
    'blocked_identity_retry_no_recompute': 'blockedReceiverKeepsExactResultAndFifoUntilExplicitRetryWithoutRerunningWork',
    'throwing_receiver_idempotent_redelivery': 'receiverExceptionAfterPartialApplyRequiresIdempotentReceiverAndRetainsCharge',
    'compute_side_effect_unknown_next_job': 'computeFailureIsUnknownOutcomeAndNextAcceptedJobStillExecutes',
    'owner_queue_guard_worker_separation': 'foreignThreadCannotAdmitDrainStopInspectRetryOrMutateDocument',
    'recursive_drain_guard': 'reentrantDrainCannotDeliverAnotherResultWhileCurrentReceiverOwnsIt',
    'foreign_ticket_rejected': 'foreignDeliveryTicketAndDuplicateAcknowledgmentCannotFreeAnotherExecutorBudget',
}

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    # Reject stale JVM results before comparing platforms.
    subprocess.run(['python3', str(ROOT/'tools/collect_verification.py')], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    xml = ROOT/'canvas-core/build/test-results/jvmTest/TEST-dev.notecore.canvas.prototype.BoundedOwnerExecutorTest.xml'
    suite = ET.parse(xml).getroot()
    assert all(int(suite.attrib.get(key, 0)) == 0 for key in ('failures', 'errors', 'skipped'))
    jvm = {case.attrib['name'].removesuffix('[jvm]').removesuffix('()') for case in suite.findall('testcase')}
    build = ROOT/'apple-runtime-tests/build'; build.mkdir(exist_ok=True, parents=True)
    sources = [ROOT/'ios-app/NoteCorePreview/BoundedOwnerExecutor.swift', ROOT/'apple-runtime-tests/ExecutorConformance.swift']
    command = ['xcrun', 'swiftc', '-swift-version', '5', '-warnings-as-errors', '-module-cache-path', str(build/'cache'),
               *map(str, sources), '-o', str(build/'executor-tests')]
    subprocess.run(command, cwd=ROOT, check=True)
    result = json.loads(subprocess.run([str(build/'executor-tests')], check=True, capture_output=True, text=True, timeout=60).stdout)
    assert result['real_apple_threads_verified'] and not result['ios_runtime_verified']
    assert result['test_count'] == 12 and len(set(result['passed'])) == 12
    for apple, java in CASES.items(): assert apple in result['passed'] and java in jvm, (apple, java)
    result.update(status='passed', shared_contract_cases=[{'scenario': a, 'jvm_case': j} for a, j in CASES.items()],
                  shared_case_count=len(CASES), ios_build_verified=False, device_performance_verified=False,
                  production_c1_gate_complete=False,
                  limitations=['Apple Foundation worker / serial Dispatch owner verified on macOS, not iOS runtime',
                               'Swift fatal traps / process death are not recoverable worker errors',
                               'Native GPU resources and durable storage are not exercised by this executor suite'],
                  input_sha256={p.relative_to(ROOT).as_posix(): digest(p) for p in [*sources, Path(__file__), xml, ROOT/'reports/verification.json']},
                  compiler=subprocess.run(['xcrun', 'swiftc', '--version'], capture_output=True, text=True, check=True).stdout.strip())
    (ROOT/'reports/apple-executor-conformance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ('input_sha256', 'shared_contract_cases')}, ensure_ascii=False))

if __name__ == '__main__': main()
