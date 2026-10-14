package dev.notecore.canvas.prototype

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BoundedOwnerExecutorTest {
    private fun <T> using(max: Int = 8, bytes: Long = 64, body: (BoundedOwnerExecutor) -> T): T {
        val executor = BoundedOwnerExecutor(max, bytes, "NoteCore-test-worker")
        try { return body(executor) }
        finally { executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000), "Test worker leaked") }
    }
    private fun awaitReady(executor: BoundedOwnerExecutor, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (executor.status().entries.count { it.stage == OwnedWorkStage.READY } < count) {
            check(System.nanoTime() < deadline) { "Worker did not publish expected results" }; Thread.sleep(1)
        }
    }
    @Test fun realWorkerExecutesFifoAndOwnerReceivesFiniteBatches() = using { executor ->
        val owner = Thread.currentThread().id; val order = mutableListOf<Int>(); val workers = mutableListOf<Long>()
        repeat(5) { value ->
            assertIs<WorkAdmission.Accepted>(executor.submit(4, {
                executor.checkWorkerThread(); value to Thread.currentThread().id
            }) { result ->
                executor.checkOwnerThread(); assertEquals(owner, Thread.currentThread().id)
                val output = assertIs<WorkResult.Succeeded<Pair<Int, Long>>>(result).value
                order.add(output.first); workers.add(output.second); ResultDisposition.ACKNOWLEDGED
            })
        }
        executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000))
        assertFalse(executor.status().isDrained); assertEquals(20L, executor.status().chargedBytes)
        assertEquals(2, executor.drainResults(2).acknowledged); assertEquals(listOf(0, 1), order)
        assertEquals(3, executor.drainResults().acknowledged); assertEquals((0..4).toList(), order)
        assertEquals(setOf(executor.status().workerThreadId), workers.toSet()); assertTrue(workers.all { it != owner })
        assertTrue(executor.status().isDrained)
    }
    @Test fun runningQueuedAndReadyJobsAllConsumeCapacityAndRejectedWorkNeverRuns() = using(2, 8) { executor ->
        val started = CountDownLatch(1); val release = CountDownLatch(1); val rejectedRuns = AtomicInteger()
        try {
            executor.submit(4, { started.countDown(); check(release.await(2, TimeUnit.SECONDS)); 1 }) { ResultDisposition.ACKNOWLEDGED }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            executor.submit(4, { 2 }) { ResultDisposition.ACKNOWLEDGED }
            assertEquals(listOf(OwnedWorkStage.RUNNING, OwnedWorkStage.QUEUED), executor.status().entries.map { it.stage })
            assertEquals(WorkAdmissionFailure.ENTRY_CAPACITY, assertIs<WorkAdmission.Rejected>(executor.submit(1, { rejectedRuns.incrementAndGet() }) { ResultDisposition.ACKNOWLEDGED }).reason)
            release.countDown(); awaitReady(executor, 2)
            assertEquals(8L, executor.status().chargedBytes)
            assertIs<WorkAdmission.Rejected>(executor.submit(1, { rejectedRuns.incrementAndGet() }) { ResultDisposition.ACKNOWLEDGED })
            assertEquals(2, executor.drainResults().acknowledged); assertEquals(0, rejectedRuns.get())
            assertIs<WorkAdmission.Accepted>(executor.submit(4, { 3 }) { ResultDisposition.ACKNOWLEDGED })
            executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)); executor.drainResults(); assertTrue(executor.status().isDrained)
        } finally { release.countDown() }
    }
    @Test fun byteAdmissionUsesRemainingBudgetWithoutOverflowAndRejectsInvalidCharge() = using(2, Long.MAX_VALUE) { executor ->
        assertEquals(WorkAdmissionFailure.INVALID_CHARGE, assertIs<WorkAdmission.Rejected>(executor.submit(0, { 1 }) { ResultDisposition.ACKNOWLEDGED }).reason)
        assertIs<WorkAdmission.Rejected>(executor.submit(-1, { 1 }) { ResultDisposition.ACKNOWLEDGED })
        executor.submit(Long.MAX_VALUE, { 1 }) { ResultDisposition.ACKNOWLEDGED }; awaitReady(executor, 1)
        assertEquals(Long.MAX_VALUE, executor.status().chargedBytes)
        assertEquals(WorkAdmissionFailure.BYTE_CAPACITY, assertIs<WorkAdmission.Rejected>(executor.submit(1, { 2 }) { ResultDisposition.ACKNOWLEDGED }).reason)
        executor.drainResults(); assertEquals(0L, executor.status().chargedBytes)
    }
    @Test fun stopDoesNotInterruptRunningJobOrLoseQueuedWorkAndTimeoutDoesNotCancel() = using { executor ->
        val started = CountDownLatch(1); val release = CountDownLatch(1); val order = mutableListOf<Int>()
        try {
            executor.submit(4, { started.countDown(); check(release.await(2, TimeUnit.SECONDS)); check(!Thread.currentThread().isInterrupted); 1 }) {
                order.add(assertIs<WorkResult.Succeeded<Int>>(it).value); ResultDisposition.ACKNOWLEDGED
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            executor.submit(4, { 2 }) { order.add(assertIs<WorkResult.Succeeded<Int>>(it).value); ResultDisposition.ACKNOWLEDGED }
            executor.stopAccepting(); assertFalse(executor.awaitWorkerStop(1)); assertFalse(executor.status().isDrained)
            assertEquals(WorkAdmissionFailure.STOPPED, assertIs<WorkAdmission.Rejected>(executor.submit(1, { 3 }) { ResultDisposition.ACKNOWLEDGED }).reason)
            release.countDown(); assertTrue(executor.awaitWorkerStop(3000)); assertEquals(8L, executor.status().chargedBytes)
            executor.drainResults(); assertEquals(listOf(1, 2), order); assertTrue(executor.status().isDrained)
        } finally { release.countDown() }
    }
    @Test fun blockedReceiverKeepsExactResultAndFifoUntilExplicitRetryWithoutRerunningWork() = using { executor ->
        val runs = AtomicInteger(); val delivered = mutableListOf<Int>(); var previous: WorkResult<Int>? = null; var allow = false
        val input = SceneId("original-input")
        val first = assertIs<WorkAdmission.Accepted>(executor.submit(input, 4, { runs.incrementAndGet(); 1 }) { retained, result ->
            assertSame(input, retained)
            previous?.let { assertSame(it, result) }; previous = result
            if (!allow) ResultDisposition.RETRY_REQUIRED else { delivered.add(1); ResultDisposition.ACKNOWLEDGED }
        }).ticket
        executor.submit(4, { runs.incrementAndGet(); 2 }) { delivered.add(2); ResultDisposition.ACKNOWLEDGED }
        executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000))
        assertSame(first, executor.drainResults().blocked); assertEquals(8L, executor.status().chargedBytes)
        assertEquals(listOf(OwnedWorkStage.DELIVERY_BLOCKED, OwnedWorkStage.READY), executor.status().entries.map { it.stage })
        assertSame(first, executor.drainResults().blocked); assertTrue(delivered.isEmpty()); assertEquals(2, runs.get())
        allow = true; assertTrue(executor.retryDelivery(first)); assertEquals(2, executor.drainResults().acknowledged)
        assertEquals(listOf(1, 2), delivered); assertEquals(2, runs.get()); assertFalse(executor.retryDelivery(first))
        assertTrue(executor.status().isDrained)
    }
    @Test fun receiverExceptionAfterPartialApplyRequiresIdempotentReceiverAndRetainsCharge() = using { executor ->
        var applied = 0; var callbacks = 0; val receiptIds = mutableSetOf<Int>()
        val ticket = assertIs<WorkAdmission.Accepted>(executor.submit(4, { 42 }) {
            callbacks++; val id = assertIs<WorkResult.Succeeded<Int>>(it).value
            if (receiptIds.add(id)) applied++
            if (callbacks == 1) error("after receiver applied receipt")
            ResultDisposition.ACKNOWLEDGED
        }).ticket
        awaitReady(executor, 1); assertSame(ticket, executor.drainResults().blocked)
        assertEquals(1, applied); assertEquals(4L, executor.status().chargedBytes)
        assertNotNull(executor.status().entries.single().deliveryFailure)
        executor.retryDelivery(ticket); executor.drainResults(); assertEquals(2, callbacks); assertEquals(1, applied)
    }
    @Test fun computeFailureIsUnknownOutcomeAndNextAcceptedJobStillExecutes() = using { executor ->
        val effects = AtomicInteger(); val results = mutableListOf<WorkResult<Int>>()
        executor.submit<Int>(4, { effects.incrementAndGet(); error("after effect") }) { results.add(it); ResultDisposition.ACKNOWLEDGED }
        executor.submit(4, { 7 }) { results.add(it); ResultDisposition.ACKNOWLEDGED }
        executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)); executor.drainResults()
        assertIs<WorkResult.OutcomeUnknown>(results[0]); assertEquals(7, assertIs<WorkResult.Succeeded<Int>>(results[1]).value)
        assertEquals(1, effects.get()); assertTrue(executor.status().isDrained)
    }
    @Test fun taskSelfInterruptDoesNotContaminateNextAlreadyAcceptedJob() = using { executor ->
        val started = CountDownLatch(1); val release = CountDownLatch(1); var clean = false
        try {
            executor.submit(4, { started.countDown(); check(release.await(2, TimeUnit.SECONDS)); Thread.currentThread().interrupt(); 1 }) { ResultDisposition.ACKNOWLEDGED }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            executor.submit(4, { !Thread.currentThread().isInterrupted }) {
                clean = assertIs<WorkResult.Succeeded<Boolean>>(it).value; ResultDisposition.ACKNOWLEDGED
            }
            executor.stopAccepting(); release.countDown(); assertTrue(executor.awaitWorkerStop(3000))
            executor.drainResults(); assertTrue(clean); assertTrue(executor.status().isDrained)
        } finally { release.countDown() }
    }
    private class BrokenMessageException : RuntimeException() {
        override val message: String get() = error("failure while reporting task error")
    }
    @Test fun infrastructureTerminationReturnsUnknownRunningAndNeverExecutedQueuedResultsInFifo() = using { executor ->
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val effects = AtomicInteger(); val results = mutableListOf<WorkResult<Int>>()
        val input = CompletedInput(StrokeTarget(SceneId("queued"), LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)),
            StrokeSamples.copyOf(doubleArrayOf(31.0), doubleArrayOf(9.0), floatArrayOf(0.7f), longArrayOf(123)))
        var handedBack: CompletedInput? = null
        try {
            executor.submit<Int>(4, { started.countDown(); check(release.await(2, TimeUnit.SECONDS)); effects.incrementAndGet(); throw BrokenMessageException() }) {
                results.add(it); ResultDisposition.ACKNOWLEDGED
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            executor.submit(input, 4, { effects.addAndGet(100) }) { retained, result -> handedBack = retained; results.add(result); ResultDisposition.ACKNOWLEDGED }
            release.countDown(); assertTrue(executor.awaitWorkerStop(3000))
            assertNotNull(executor.status().workerFailure); assertEquals(8L, executor.status().chargedBytes)
            assertEquals(WorkAdmissionFailure.WORKER_TERMINATED, assertIs<WorkAdmission.Rejected>(executor.submit(1, { 1 }) { ResultDisposition.ACKNOWLEDGED }).reason)
            assertEquals(2, executor.drainResults().acknowledged)
            assertIs<WorkResult.OutcomeUnknown>(results[0]); assertIs<WorkResult.NotExecuted>(results[1])
            assertSame(input, handedBack); assertEquals(31.0, handedBack!!.samples.x(0)); assertEquals(123L, handedBack!!.samples.timeMicros(0))
            assertEquals(1, effects.get()); assertTrue(executor.status().isDrained)
        } finally { release.countDown() }
    }
    @Test fun idleWorkerInterruptionRetainsReadyResultUntilReceiverAcknowledges() = using { executor ->
        var worker: Thread? = null; var received = false
        executor.submit(4, { worker = Thread.currentThread(); 1 }) { assertIs<WorkResult.Succeeded<Int>>(it); received = true; ResultDisposition.ACKNOWLEDGED }
        awaitReady(executor, 1)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (worker!!.state != Thread.State.WAITING) { check(System.nanoTime() < deadline); Thread.sleep(1) }
        worker!!.interrupt(); assertTrue(executor.awaitWorkerStop(3000))
        assertNotNull(executor.status().workerFailure); assertEquals(4L, executor.status().chargedBytes); assertFalse(received)
        executor.drainResults(); assertTrue(received); assertTrue(executor.status().isDrained)
    }
    @Test fun foreignThreadCannotAdmitDrainStopInspectRetryOrMutateDocument() = using { executor ->
        val scene = SceneId("guarded"); val document = DocumentCloseCoordinator<String, Any>(scene, ownerCheck = executor::checkOwnerThread)
        val failures = AtomicInteger()
        val thread = Thread {
            val operations = listOf<() -> Unit>(
                { executor.submit(1, { 1 }) { ResultDisposition.ACKNOWLEDGED } }, { executor.drainResults() },
                { executor.stopAccepting() }, { executor.status() }, { executor.awaitWorkerStop(1) },
                { document.beginClose() }, { document.status() }, { document.reserveResource(1) })
            operations.forEach { operation -> try { operation() } catch (_: IllegalStateException) { failures.incrementAndGet() } }
        }
        thread.start(); thread.join(3000); assertFalse(thread.isAlive); assertEquals(8, failures.get())
        assertTrue(executor.status().entries.isEmpty()); assertEquals(DocumentClosePhase.OPEN, document.status().phase)
        assertFailsWith<IllegalStateException> { executor.checkWorkerThread() }
        document.beginClose(); assertEquals(DocumentClosePhase.CLOSED, document.status().phase)
    }
    @Test fun reentrantDrainCannotDeliverAnotherResultWhileCurrentReceiverOwnsIt() = using { executor ->
        var recursive = true; var deliveries = 0
        val ticket = assertIs<WorkAdmission.Accepted>(executor.submit(4, { 1 }) {
            assertEquals(OwnedWorkStage.DELIVERING, executor.status().entries.first().stage)
            assertEquals(4L, executor.status().chargedBytes)
            if (recursive) executor.drainResults()
            deliveries++; ResultDisposition.ACKNOWLEDGED
        }).ticket
        awaitReady(executor, 1); assertSame(ticket, executor.drainResults().blocked); assertEquals(0, deliveries)
        recursive = false; executor.retryDelivery(ticket); executor.drainResults(); assertEquals(1, deliveries)
    }
    @Test fun foreignDeliveryTicketAndDuplicateAcknowledgmentCannotFreeAnotherExecutorBudget() = using { first ->
        using { second ->
            var allow = false
            val a = assertIs<WorkAdmission.Accepted>(first.submit(4, { 1 }) { if (allow) ResultDisposition.ACKNOWLEDGED else ResultDisposition.RETRY_REQUIRED }).ticket
            val b = assertIs<WorkAdmission.Accepted>(second.submit(4, { 2 }) { ResultDisposition.ACKNOWLEDGED }).ticket
            awaitReady(first, 1); awaitReady(second, 1); first.drainResults()
            assertFalse(first.retryDelivery(b)); assertFalse(second.retryDelivery(a)); assertEquals(4L, first.status().chargedBytes)
            second.drainResults(); assertFalse(second.retryDelivery(b)); assertEquals(0L, second.status().chargedBytes)
            // Explicitly blocked results remain retained; stop is not a discard API.
            first.stopAccepting(); assertTrue(first.awaitWorkerStop(3000)); assertFalse(first.status().isDrained)
            allow = true; first.retryDelivery(a); first.drainResults(); assertTrue(first.status().isDrained)
        }
    }
    @Test fun lostCommitOutcomeCanReplayExactPatchAcrossRealWorkerOwnerBoundaryDuringClose() = using { executor ->
        val scene = SceneId("real-thread-page"); val initial = SceneSnapshot.empty(scene)
        val document = DocumentCloseCoordinator<String, Any>(scene, ownerCheck = executor::checkOwnerThread)
        val frame = FrameToken(scene, 0, 0, 1, 1, 0); val session = assertIs<SessionOpenResult.Opened>(document.openSession(frame, 10)).session
        val target = StrokeTarget(scene, LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
        for ((seq, phase) in listOf(PointerPhase.DOWN, PointerPhase.UP).withIndex()) document.consume(session,
            InputBatch(seq.toLong(), seq.toLong(), 1, PointerTool.STYLUS, phase, listOf(InputSample(seq.toDouble(), 1.0, 0.5f, seq.toLong()))), target)
        document.submitPending(session, CommandId("op"), ObjectId("stroke"))
        val first = assertIs<DispatchResult.Ready>(document.dispatch(initial)).attempt
        var authority: MemorySceneCoordinator? = null // accessed only by serial worker tasks
        executor.submit<SubmissionAttempt, Int>(first, 4, { current -> executor.checkWorkerThread(); authority = MemorySceneCoordinator(initial); authority!!.commit(current.patch); error("lost commit result") }) { current, result ->
            assertIs<WorkResult.OutcomeUnknown>(result); document.transportFailed(current, "worker outcome unknown"); ResultDisposition.ACKNOWLEDGED
        }
        document.beginClose(); awaitReady(executor, 1); executor.drainResults()
        assertEquals(DocumentClosePhase.CLOSING, document.status().phase); assertNull(document.offerSubmission(first.id))
        document.retry(first.id); val retry = assertIs<DispatchResult.Ready>(document.dispatch(initial)).attempt
        assertSame(first.patch, retry.patch)
        executor.submit(retry, 4, { current -> executor.checkWorkerThread(); authority!!.commit(current.patch) to authority!!.snapshot() }) { current, outcome ->
            val result = assertIs<WorkResult.Succeeded<Pair<CommitResult, SceneSnapshot>>>(outcome).value
            document.complete(current, result.first); assertEquals(1, result.second.objectCount); assertEquals(1L, result.second.revision)
            val offer = document.offerSubmission(current.id)!!; val owned = offer.payload
            assertIs<RecoveryPayload.Confirmed>(owned); document.acknowledge(offer); ResultDisposition.ACKNOWLEDGED
        }
        executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)); executor.drainResults()
        assertEquals(DocumentClosePhase.CLOSED, document.status().phase); assertTrue(executor.status().isDrained)
    }
    @Test fun destructionOutcomeUnknownKeepsResourceUntilWorkerQueryAndOwnerReconciliation() = using { executor ->
        val document = DocumentCloseCoordinator<String, AtomicInteger>(SceneId("resource"), residentBudget = 4, resourceBudget = 8, ownerCheck = executor::checkOwnerThread)
        val destroyed = AtomicInteger(); val reservation = assertIs<ReservationResult.Reserved>(document.reserveResource(4)).reservation
        val handle = assertIs<ResourcePublication.Resident>(document.publishResource(reservation, "tile", destroyed)).handle
        document.beginClose(); val ticket = assertIs<ResourceReleaseDispatch.Ready<AtomicInteger>>(document.nextRelease(ResourceLane.GPU)).ticket
        // Borrow the release capability only for this worker operation; return its outcome to the owner.
        executor.submit<ResourceReleaseTicket<AtomicInteger>, Int>(ticket, 4, { current -> executor.checkWorkerThread(); check(current.value.incrementAndGet() == 1); error("destruction result lost") }) { current, result ->
            assertIs<WorkResult.OutcomeUnknown>(result); document.completeRelease(current, ResourceReleaseOutcome.UNKNOWN); ResultDisposition.ACKNOWLEDGED
        }
        awaitReady(executor, 1); executor.drainResults(); assertEquals(4L, document.status().resources.chargedBytes)
        assertIs<ResourceReleaseDispatch.Idle>(document.nextRelease(ResourceLane.GPU)); assertEquals(DocumentClosePhase.CLOSING, document.status().phase)
        executor.submit(4, { executor.checkWorkerThread(); destroyed.get() }) {
            assertEquals(1, assertIs<WorkResult.Succeeded<Int>>(it).value)
            document.resolveUnknown(handle, ResourceReleaseOutcome.RELEASED); ResultDisposition.ACKNOWLEDGED
        }
        executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)); executor.drainResults()
        assertEquals(1, destroyed.get()); assertEquals(DocumentClosePhase.CLOSED, document.status().phase); assertTrue(executor.status().isDrained)
    }
    @Test fun wakeRunsAfterPublicationOffMonitorAndFailureKeepsResultsAndAcceptedNextJob() {
        val wakes = AtomicInteger()
        val published = CountDownLatch(1)
        val executor = BoundedOwnerExecutor(requestOwnerWake = {
            wakes.incrementAndGet(); published.countDown(); throw IllegalStateException("Owner loop unavailable")
        })
        try {
            repeat(2) { value ->
                assertIs<WorkAdmission.Accepted>(executor.submit(value, 4, { it }) { input, result ->
                    assertEquals(input, assertIs<WorkResult.Succeeded<Int>>(result).value)
                    ResultDisposition.ACKNOWLEDGED
                })
            }
            assertTrue(published.await(3, TimeUnit.SECONDS))
            executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000))
            assertNull(executor.status().workerFailure)
            assertEquals("IllegalStateException", executor.status().ownerWakeFailure)
            assertEquals(8L, executor.status().chargedBytes); assertTrue(wakes.get() >= 2)
            assertEquals(2, executor.drainResults().acknowledged); assertTrue(executor.status().isDrained)
        } finally { executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)) }
    }

}
