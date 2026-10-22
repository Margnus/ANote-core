package dev.notecore.document

import dev.notecore.canvas.preview.*
import dev.notecore.canvas.prototype.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PreviewSaveDispatchExecutorTest {
    private fun snapshot() = PagedPreviewNotebook.create("dispatch", "保留😀", "dispatch-session").run {
        assertTrue(beginStroke(1.0, 2.0, .5f, 1)); assertTrue(endStroke(3.0, 4.0, .8f, 2)); capture()
    }

    private fun pump(executor: BoundedOwnerExecutor, done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < end) { executor.drainResults(); Thread.sleep(1) }
        executor.drainResults(); assertTrue(done(), "Bounded executor did not settle")
    }

    @Test fun saturatedRealWorkerKeepsTheSnapshotThenWritesAndReopensExactlyOnce() {
        val snapshot = snapshot(); val budget = snapshot.encodingWorkByteCharge
        val executor = BoundedOwnerExecutor(2, budget)
        val ledger = PreviewSaveLedger(ownerCheck = executor::checkOwnerThread)
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        val file = Files.createTempFile("notecore-dispatch-", ".ncp")
        var writes = 0
        try {
            assertIs<WorkAdmission.Accepted>(executor.submit(Unit, budget, {
                entered.countDown(); check(gate.await(5, TimeUnit.SECONDS)); Unit
            }) { _, result -> assertIs<WorkResult.Succeeded<Unit>>(result); ResultDisposition.ACKNOWLEDGED })
            assertTrue(entered.await(3, TimeUnit.SECONDS)); ledger.offer(snapshot)
            val blocked = ledger.admitNextWrite(budget - executor.status().chargedBytes, budget)
            assertEquals(PreviewWriteDisposition.BACKPRESSURE, blocked.disposition)
            assertNull(blocked.attempt); assertFalse(ledger.states().single().inFlight)
            assertSame(snapshot, ledger.latest(snapshot.documentId))
            gate.countDown(); pump(executor) { executor.status().entries.isEmpty() }
            val attempt = assertNotNull(ledger.admitNextWrite(budget - executor.status().chargedBytes, budget).attempt)
            assertEquals(1L, attempt.number)
            assertIs<WorkAdmission.Accepted>(executor.submit(attempt, budget, {
                Files.writeString(file, it.snapshot.encode(), Charsets.UTF_8)
                PagedPreviewCodec.decode(Files.readString(file, Charsets.UTF_8))
            }) { original, result ->
                val reopened = assertIs<WorkResult.Succeeded<DocumentRoot>>(result).value
                assertEquals(snapshot.root.title, reopened.title)
                assertEquals(snapshot.root.scene.objects(), reopened.scene.objects())
                writes++; assertTrue(ledger.complete(original, true)); ResultDisposition.ACKNOWLEDGED
            })
            pump(executor) { ledger.isDrained }
            assertEquals(1, writes); assertEquals(0L, executor.status().chargedBytes)
            assertFalse(ledger.complete(attempt, true))
        } finally {
            gate.countDown(); executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000)); Files.deleteIfExists(file)
        }
    }

    @Test fun actualForeignWorkerCannotDispatchInspectOrCompleteTheOwnerFlight() {
        val snapshot = snapshot(); val executor = BoundedOwnerExecutor()
        val ledger = PreviewSaveLedger(ownerCheck = executor::checkOwnerThread)
        ledger.offer(snapshot); val flight = assertNotNull(ledger.nextWrite()); var failures = 0
        try {
            val operations: List<() -> Any?> = listOf({ ledger.complete(flight, true) },
                { ledger.admitNextWrite(Long.MAX_VALUE, Long.MAX_VALUE) }, { ledger.states() })
            for (operation in operations) assertIs<WorkAdmission.Accepted>(executor.submit(operation, 1024, { it() ?: Unit }) { _, result ->
                assertIs<WorkResult.OutcomeUnknown>(result); failures++
                assertSame(snapshot, ledger.latest(snapshot.documentId)); assertTrue(ledger.states().single().inFlight)
                ResultDisposition.ACKNOWLEDGED
            })
            executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000)); executor.drainResults()
            assertEquals(3, failures); assertTrue(ledger.complete(flight, true)); assertTrue(ledger.isDrained)
        } finally { executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000)) }
    }

    @Test fun executorSlotRefusalReturnsTheUnstartedAttemptWithoutClaimingAWrite() {
        val snapshot = snapshot(); val budget = snapshot.encodingWorkByteCharge
        val executor = BoundedOwnerExecutor(1, budget * 2)
        val ledger = PreviewSaveLedger(ownerCheck = executor::checkOwnerThread)
        val gate = CountDownLatch(1); val entered = CountDownLatch(1)
        try {
            assertIs<WorkAdmission.Accepted>(executor.submit(Unit, budget, {
                entered.countDown(); check(gate.await(5, TimeUnit.SECONDS)); Unit
            }) { _, _ -> ResultDisposition.ACKNOWLEDGED })
            assertTrue(entered.await(3, TimeUnit.SECONDS)); ledger.offer(snapshot)
            val attempt = assertNotNull(ledger.admitNextWrite(budget * 2 - executor.status().chargedBytes, budget * 2).attempt)
            val rejected = assertIs<WorkAdmission.Rejected>(executor.submit(attempt, budget,
                { error("Refused computation must never execute") }) { _, _ -> error("Refused result must never deliver") })
            assertEquals(WorkAdmissionFailure.ENTRY_CAPACITY, rejected.reason)
            assertTrue(ledger.returnUnstarted(attempt)); assertFalse(ledger.complete(attempt, true))
            assertSame(snapshot, ledger.latest(snapshot.documentId)); assertFalse(ledger.states().single().inFlight)
            assertFalse(ledger.states().single().retryRequired)
            gate.countDown(); pump(executor) { executor.status().entries.isEmpty() }
            val retry = assertNotNull(ledger.admitNextWrite(budget * 2, budget * 2).attempt)
            assertEquals(2L, retry.number); assertSame(snapshot, retry.snapshot)
            assertTrue(ledger.returnUnstarted(retry))
        } finally { gate.countDown(); executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000)) }
    }

    @Test fun exhaustedAttemptIdentityReturnsTypedFailureAndRetainsRecoveryContent() {
        val snapshot = snapshot(); val ledger = PreviewSaveLedger(); ledger.offer(snapshot)
        // Reach an otherwise impractical lifetime boundary without exposing a production mutation hook.
        PreviewSaveLedger::class.java.getDeclaredField("sequence").apply { isAccessible = true }.setLong(ledger, Long.MAX_VALUE)
        val result = ledger.admitNextWrite(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEquals(PreviewWriteDisposition.IDENTITY_EXHAUSTED, result.disposition)
        assertEquals(snapshot.documentId, result.documentId); assertNull(result.attempt)
        assertSame(snapshot, ledger.latest(snapshot.documentId)); assertFalse(ledger.states().single().inFlight)
        assertNull(ledger.nextWrite()); assertEquals(snapshot.retainedByteCharge, ledger.chargedBytes)
    }

    @Test fun stoppedRealExecutorReportsUnavailableBeforeAnyAttemptIsAllocated() {
        val snapshot = snapshot(); val budget = snapshot.encodingWorkByteCharge
        val executor = BoundedOwnerExecutor(1, budget)
        val ledger = PreviewSaveLedger(ownerCheck = executor::checkOwnerThread)
        try {
            ledger.offer(snapshot); executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000))
            val worker = executor.status()
            val rejected = ledger.admitNextWrite(budget - worker.chargedBytes, budget, worker.accepting && worker.workerFailure == null)
            assertEquals(PreviewWriteDisposition.BACKEND_UNAVAILABLE, rejected.disposition)
            assertNull(rejected.attempt); assertTrue(worker.entries.isEmpty())
            assertSame(snapshot, ledger.latest(snapshot.documentId)); assertFalse(ledger.states().single().inFlight)
        } finally { executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(5000)) }
    }
}
