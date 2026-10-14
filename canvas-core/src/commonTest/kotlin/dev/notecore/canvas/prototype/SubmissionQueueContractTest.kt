package dev.notecore.canvas.prototype

import kotlin.test.*

class SubmissionQueueContractTest {
    private val sceneId = SceneId("page")
    private val target = StrokeTarget(sceneId, LayerId("at-down"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
    private fun request(name: String = "a", x: Double = 0.0) = StrokeSubmission(CommandId("op-$name"), ObjectId(name),
        CompletedInput(target, StrokeSamples.copyOf(doubleArrayOf(x, x+1), doubleArrayOf(0.0, 1.0), floatArrayOf(0.3f, 0.5f), longArrayOf(0, 1))))
    private fun ready(queue: StrokeSubmissionQueue, memory: MemorySceneCoordinator) = assertIs<DispatchResult.Ready>(queue.dispatch(memory.snapshot())).attempt
    private fun commit(queue: StrokeSubmissionQueue, memory: MemorySceneCoordinator): SubmissionAttempt {
        val attempt = ready(queue, memory)
        assertEquals(CompletionUpdate.UPDATED, queue.complete(attempt, memory.commit(attempt.patch)))
        return attempt
    }

    @Test fun countCapacityLeavesRejectedOutputWithCallerAndKeepsAdmittedStroke() {
        val queue = StrokeSubmissionQueue(sceneId, maxEntries = 1)
        val a = request(); val b = request("b")
        assertIs<AdmissionResult.Admitted>(queue.enqueue(a))
        assertEquals(AdmissionRejection.ENTRY_CAPACITY, assertIs<AdmissionResult.Rejected>(queue.enqueue(b)).reason)
        assertEquals(a, queue.submission(a.id)); assertNull(queue.submission(b.id))
        assertEquals(2, b.stroke.samples.size); assertEquals(a.chargedBytes, queue.chargedBytes)
    }

    @Test fun byteCapacityAndOversizedAdmissionNeverEvictExistingPayload() {
        val a = request(); val queue = StrokeSubmissionQueue(sceneId, byteBudget = a.chargedBytes)
        queue.enqueue(a)
        assertEquals(AdmissionRejection.BYTE_CAPACITY, assertIs<AdmissionResult.Rejected>(queue.enqueue(request("b"))).reason)
        assertEquals(a, queue.submission(a.id)); assertEquals(a.chargedBytes, queue.chargedBytes)
        val tooSmall = StrokeSubmissionQueue(sceneId, byteBudget = a.chargedBytes - 1)
        assertIs<AdmissionResult.Rejected>(tooSmall.enqueue(a)); assertEquals(0L, tooSmall.chargedBytes)
    }

    @Test fun duplicateAdmissionDoesNotChargeTwiceButDifferentPayloadCannotReuseId() {
        val a = request(); val queue = StrokeSubmissionQueue(sceneId, maxEntries = 1)
        queue.enqueue(a)
        assertIs<AdmissionResult.Existing>(queue.enqueue(request()))
        assertEquals(AdmissionRejection.REUSED_ID, assertIs<AdmissionResult.Rejected>(queue.enqueue(request(x = 100.0))).reason)
        assertEquals(1, queue.retainedEntries); assertEquals(a.chargedBytes, queue.chargedBytes)
    }

    @Test fun fifoUsesLatestAuthorityBaseAtFirstDispatchAndCapturedLayer() {
        val queue = StrokeSubmissionQueue(sceneId); val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        queue.enqueue(request()); queue.enqueue(request("b"))
        assertEquals(0L, commit(queue, memory).patch.baseRevision)
        assertEquals(1L, commit(queue, memory).patch.baseRevision)
        assertEquals(2L, memory.snapshot().revision)
        assertEquals(listOf(ObjectId("a"), ObjectId("b")), memory.snapshot().objects().map { it.id })
        assertTrue(memory.snapshot().objects().all { it.layerId == target.layerId })
    }

    @Test fun inFlightIsChargedAndOnlyOneAttemptCanRun() {
        val a = request(); val queue = StrokeSubmissionQueue(sceneId, maxEntries = 1)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId)); queue.enqueue(a)
        val attempt = ready(queue, memory)
        assertIs<DispatchResult.Busy>(queue.dispatch(memory.snapshot()))
        assertEquals(a.chargedBytes, queue.chargedBytes); assertEquals(1, queue.unconfirmedEntries)
        assertNull(queue.collectConfirmed(a.id)); assertNull(queue.collectBlocked(a.id))
        assertIs<AdmissionResult.Rejected>(queue.enqueue(request("b")))
        queue.complete(attempt, memory.commit(attempt.patch))
    }

    @Test fun lostAckReplaysExactPatchAndCommitsOnlyOnce() {
        val a = request(); val queue = StrokeSubmissionQueue(sceneId)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId)); queue.enqueue(a)
        val first = ready(queue, memory)
        val actual = assertIs<CommitResult.Accepted>(memory.commit(first.patch)) // caller never receives this ack
        assertEquals(CompletionUpdate.UPDATED, queue.transportFailed(first, "lost acknowledgement"))
        assertIs<SubmissionState.RetryRequired>(queue.state(a.id))
        assertIs<DispatchResult.RetryRequired>(queue.dispatch(memory.snapshot()))
        assertNull(queue.collectBlocked(a.id)); assertTrue(queue.retry(a.id))
        val second = ready(queue, memory)
        assertTrue(first.patch === second.patch); assertTrue(second.number > first.number)
        assertEquals(CompletionUpdate.STALE_ATTEMPT, queue.complete(first, actual))
        assertEquals(CompletionUpdate.UPDATED, queue.complete(second, memory.commit(second.patch)))
        assertEquals(1, memory.snapshot().objectCount); assertEquals(1L, memory.snapshot().revision)
        assertEquals(actual.receipt, assertIs<SubmissionState.Confirmed>(queue.state(a.id)).receipt)
    }

    @Test fun failureBeforeCommitCanRetrySamePayloadWithoutLoss() {
        val a = request(); val queue = StrokeSubmissionQueue(sceneId)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId)); queue.enqueue(a)
        val first = ready(queue, memory); queue.transportFailed(first, "backend unavailable")
        assertEquals(a, queue.submission(a.id)); assertEquals(a.chargedBytes, queue.chargedBytes)
        queue.retry(a.id); commit(queue, memory)
        assertEquals(1, memory.snapshot().objectCount)
    }

    @Test fun definitiveFailureBlocksFifoAndRequiresExplicitRecoveryHandoff() {
        val a = request(); val b = request("b")
        val queue = StrokeSubmissionQueue(sceneId); val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId), journalCapacity = 1)
        queue.enqueue(a); queue.enqueue(b); commit(queue, memory); commit(queue, memory)
        assertEquals(Rejection.JOURNAL_FULL, assertIs<SubmissionState.Blocked>(queue.state(b.id)).reason)
        assertIs<DispatchResult.Blocked>(queue.dispatch(memory.snapshot())); assertFalse(queue.retry(b.id))
        assertEquals(b, queue.submission(b.id)); assertNull(queue.collectConfirmed(b.id))
        val handoff = assertNotNull(queue.collectBlocked(b.id))
        assertEquals(b, handoff.first); assertEquals(Rejection.JOURNAL_FULL, handoff.second)
        assertEquals(a.chargedBytes, queue.chargedBytes)
        assertEquals(1, memory.snapshot().objectCount)
    }

    @Test fun staleSceneFailureDoesNotSilentlyRebaseTheProposal() {
        val queue = StrokeSubmissionQueue(sceneId); val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        queue.enqueue(request()); val first = ready(queue, memory)
        val other = request("external").stroke.command(CommandId("external"), ObjectId("external"), 0)
        memory.commit(assertIs<PrepareResult.Prepared>(SceneReducer.prepare(memory.snapshot(), other)).patch)
        queue.complete(first, memory.commit(first.patch))
        assertEquals(Rejection.STALE_SCENE, assertIs<SubmissionState.Blocked>(queue.state(CommandId("op-a"))).reason)
        assertEquals(1, memory.snapshot().objectCount); assertFalse(queue.retry(CommandId("op-a")))
    }

    @Test fun sessionCloseAndStopAdmissionDoNotDiscardQueuedOrInFlightStroke() {
        val session = InputReplaySession()
        session.consume(InputBatch(0, 0, 1, PointerTool.STYLUS, PointerPhase.DOWN, listOf(InputSample(0.0, 0.0, 0.5f, 0))), target)
        val completed = assertIs<InputResult.Completed>(session.consume(InputBatch(1, 1, 1, PointerTool.STYLUS, PointerPhase.UP,
            listOf(InputSample(1.0, 1.0, 0.5f, 1))), target)).stroke
        val a = StrokeSubmission(CommandId("op-a"), ObjectId("a"), completed)
        val queue = StrokeSubmissionQueue(sceneId); queue.enqueue(a)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId)); val attempt = ready(queue, memory)
        session.close(); queue.stopAccepting(); queue.stopAccepting()
        assertEquals(AdmissionRejection.STOPPED, assertIs<AdmissionResult.Rejected>(queue.enqueue(request("b"))).reason)
        assertEquals(CompletionUpdate.UPDATED, queue.complete(attempt, memory.commit(attempt.patch)))
        assertEquals(2, memory.snapshot().find(ObjectId("a"))!!.samples.size)
    }

    @Test fun confirmedPayloadIsChargedUntilHostCollectsItAndIsNeverCalledDurable() {
        val a = request(); val queue = StrokeSubmissionQueue(sceneId, maxEntries = 1)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId)); queue.enqueue(a); commit(queue, memory)
        assertEquals(0, queue.unconfirmedEntries); assertEquals(a.chargedBytes, queue.chargedBytes)
        assertIs<AdmissionResult.Rejected>(queue.enqueue(request("b")))
        val collected = assertNotNull(queue.collectConfirmed(a.id))
        assertEquals(a, collected.first); assertEquals(Durability.MEMORY_ONLY, collected.second.durability)
        assertEquals(0L, queue.chargedBytes); assertEquals(0, queue.retainedEntries)
        assertNull(queue.collectConfirmed(a.id)); assertIs<AdmissionResult.Admitted>(queue.enqueue(request("b")))
    }

    @Test fun wrongReceiptCannotConfirmAndDuplicateCallbackCannotChangeAccounting() {
        val queue = StrokeSubmissionQueue(sceneId); val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        val a = request(); queue.enqueue(a); val attempt = ready(queue, memory)
        assertEquals(CompletionUpdate.INVALID_RECEIPT, queue.complete(attempt, CommitResult.Accepted(CommitReceipt(CommandId("wrong"), 1, Durability.MEMORY_ONLY))))
        assertEquals(CompletionUpdate.INVALID_RECEIPT, queue.complete(attempt, CommitResult.Accepted(CommitReceipt(a.id, 99, Durability.MEMORY_ONLY))))
        assertIs<SubmissionState.InFlight>(queue.state(a.id))
        val result = memory.commit(attempt.patch)
        assertEquals(CompletionUpdate.UPDATED, queue.complete(attempt, result))
        assertEquals(CompletionUpdate.STALE_ATTEMPT, queue.complete(attempt, result))
        assertEquals(CompletionUpdate.STALE_ATTEMPT, queue.transportFailed(attempt, "late failure"))
        assertEquals(a.chargedBytes, queue.chargedBytes)
    }

    @Test fun attemptFromAnotherQueueCannotConfirmMatchingLocalId() {
        val one = StrokeSubmissionQueue(sceneId); val two = StrokeSubmissionQueue(sceneId)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId)); one.enqueue(request()); two.enqueue(request())
        val a = ready(one, memory); val b = ready(two, memory)
        assertEquals(CompletionUpdate.STALE_ATTEMPT, two.complete(a, memory.commit(a.patch)))
        assertIs<SubmissionState.InFlight>(two.state(b.id))
        assertEquals(CompletionUpdate.UPDATED, two.complete(b, memory.commit(b.patch)))
    }

    @Test fun prepareFailureRetainsPayloadAndWrongDocumentDoesNotPoisonQueue() {
        val a = request(); val queue = StrokeSubmissionQueue(sceneId); val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        queue.enqueue(a)
        assertIs<DispatchResult.Blocked>(queue.dispatch(SceneSnapshot.empty(SceneId("other"))))
        assertIs<SubmissionState.Queued>(queue.state(a.id))
        val existing = a.stroke.command(CommandId("existing"), a.objectId, 0)
        memory.commit(assertIs<PrepareResult.Prepared>(SceneReducer.prepare(memory.snapshot(), existing)).patch)
        assertEquals(Rejection.DUPLICATE_OBJECT, assertIs<DispatchResult.Blocked>(queue.dispatch(memory.snapshot())).reason)
        assertEquals(a, assertNotNull(queue.collectBlocked(a.id)).first)
        assertEquals(0L, queue.chargedBytes)
    }

    @Test fun repeatedBackpressureAndAckLossPreserveAllAdmittedObjectsAndBudget() {
        val queue = StrokeSubmissionQueue(sceneId, maxEntries = 3, byteBudget = 2000)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(sceneId))
        val owned = linkedMapOf<CommandId, StrokeSubmission>()
        var dispatched = 0
        fun audit() {
            assertEquals(owned.size, queue.retainedEntries)
            assertEquals(owned.values.sumOf { it.chargedBytes }, queue.chargedBytes)
            assertTrue(queue.chargedBytes in 0..queue.byteBudget)
        }
        fun drainOne() {
            val first = ready(queue, memory)
            val accepted = if (dispatched++ % 2 == 0) {
                val lost = memory.commit(first.patch)
                assertIs<CommitResult.Accepted>(lost)
                queue.transportFailed(first, "ack lost")
                assertTrue(queue.retry(first.id))
                val retry = ready(queue, memory)
                assertEquals(CompletionUpdate.STALE_ATTEMPT, queue.complete(first, lost))
                queue.complete(retry, memory.commit(retry.patch))
            } else queue.complete(first, memory.commit(first.patch))
            assertEquals(CompletionUpdate.UPDATED, accepted)
            val collected = assertNotNull(queue.collectConfirmed(first.id))
            assertEquals(owned.remove(first.id), collected.first)
            audit()
        }
        repeat(200) { index ->
            val item = request("stroke-$index", index.toDouble())
            var admission = queue.enqueue(item)
            if (admission is AdmissionResult.Rejected) {
                assertTrue(admission.reason == AdmissionRejection.ENTRY_CAPACITY || admission.reason == AdmissionRejection.BYTE_CAPACITY)
                audit(); drainOne(); admission = queue.enqueue(item)
            }
            assertIs<AdmissionResult.Admitted>(admission); owned[item.id] = item
            audit()
            if (index % 3 == 0) drainOne()
        }
        queue.stopAccepting()
        while (owned.isNotEmpty()) drainOne()
        assertEquals(200L, memory.snapshot().revision); assertEquals(200, memory.snapshot().objectCount)
        repeat(200) { index -> assertEquals(index.toDouble(), memory.snapshot().find(ObjectId("stroke-$index"))!!.samples.x(0)) }
    }
}
