package dev.notecore.canvas.prototype

import kotlin.test.*

class DocumentCloseContractTest {
    private val scene = SceneId("page")
    private val target = StrokeTarget(scene, LayerId("captured"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1))
    private val frame = FrameToken(scene, 0, 0, 1, 1, 0)
    private fun coordinator(max: Int = 64) = DocumentCloseCoordinator<String, FakeGpuResource>(scene, maxSubmissions = max, residentBudget = 4, resourceBudget = 8)
    private fun session(c: DocumentCloseCoordinator<String, FakeGpuResource>) = assertIs<SessionOpenResult.Opened>(c.openSession(frame, 10)).session
    private fun batch(seq: Long, phase: PointerPhase) = InputBatch(seq, seq, 1, PointerTool.STYLUS, phase, listOf(InputSample(seq.toDouble(), 1.0, 0.5f, seq)))
    private fun completed(c: DocumentCloseCoordinator<String, FakeGpuResource>, s: DocumentSession, seq: Long = 0): CompletedInput {
        assertIs<InputResult.Preview>(assertIs<SessionInputResult.Consumed>(c.consume(s, batch(seq, PointerPhase.DOWN), target)).result)
        return assertIs<InputResult.Completed>(assertIs<SessionInputResult.Consumed>(c.consume(s, batch(seq + 1, PointerPhase.UP), target)).result).stroke
    }
    private fun submit(c: DocumentCloseCoordinator<String, FakeGpuResource>, s: DocumentSession, name: String) {
        completed(c, s); assertIs<AdmissionResult.Admitted>(c.submitPending(s, CommandId(name), ObjectId(name)))
    }
    private fun ready(c: DocumentCloseCoordinator<String, FakeGpuResource>, memory: MemorySceneCoordinator) = assertIs<DispatchResult.Ready>(c.dispatch(memory.snapshot())).attempt
    private fun confirm(c: DocumentCloseCoordinator<String, FakeGpuResource>, memory: MemorySceneCoordinator): SubmissionAttempt {
        val attempt = ready(c, memory); assertEquals(CompletionUpdate.UPDATED, c.complete(attempt, memory.commit(attempt.patch))); return attempt
    }
    private fun handoff(c: DocumentCloseCoordinator<String, FakeGpuResource>, id: CommandId): RecoveryPayload {
        val offer = assertNotNull(c.offerSubmission(id)); val payload = offer.payload
        assertTrue(c.acknowledge(offer)); assertFailsWith<IllegalStateException> { offer.payload }; return payload
    }

    @Test fun emptyCloseIsTerminalAndRepeatDoesNotReopenAdmissions() {
        val c = coordinator(); assertEquals(DocumentClosePhase.OPEN, c.status().phase)
        assertTrue(c.beginClose().isEmpty()); assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
        assertTrue(c.beginClose().isEmpty())
        assertEquals(SessionOpenFailure.CLOSING, assertIs<SessionOpenResult.Rejected>(c.openSession(frame)).reason)
        assertEquals(ReservationFailure.CLOSED, assertIs<ReservationResult.Rejected>(c.reserveResource(4)).reason)
        assertIs<ResourceBorrow.Closed>(c.borrowResource("tile"))
    }
    @Test fun unfinishedInputReportsCancellationAndLateFrameCannotPublish() {
        val c = coordinator(); val s = session(c)
        c.consume(s, batch(0, PointerPhase.DOWN), target); c.consume(s, batch(1, PointerPhase.MOVE), target)
        assertTrue(c.canPublish(s, frame))
        val stop = c.beginClose().single()
        assertEquals(2, stop.cancelledRealSamples); assertFalse(stop.completedPending)
        assertFalse(c.canPublish(s, frame)); assertFalse(c.expectFrame(s, frame.copy(requestSequence = 1)))
        assertIs<SessionInputResult.Rejected>(c.consume(s, batch(2, PointerPhase.UP), target))
        assertEquals(DocumentClosePhase.CLOSED, c.status().phase); assertEquals(0L, c.status().inputReservedBytes)
    }
    @Test fun acceptedCommitInvalidatesOldContentFrameBeforeHostUpdatesExpectedFrame() {
        val c = coordinator(); val s = session(c); submit(c, s, "op")
        assertTrue(c.canPublish(s, frame))
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene)); confirm(c, memory)
        assertFalse(c.canPublish(s, frame)); assertFalse(c.expectFrame(s, frame.copy(requestSequence = 1)))
        val current = frame.copy(sceneRevision = 1, requestSequence = 1)
        assertTrue(c.expectFrame(s, current)); assertTrue(c.canPublish(s, current))
        assertFalse(c.observeScene(SceneSnapshot.empty(scene))); assertEquals(1L, c.status().observedSceneRevision)
        assertFalse(c.observeScene(SceneSnapshot.empty(SceneId("foreign")))); assertTrue(c.canPublish(s, current))
        handoff(c, CommandId("op")); c.beginClose()
        assertFalse(c.canPublish(s, current))
    }
    @Test fun authoritySnapshotObservationRejectsStaleSessionWithoutAnyQueuedSubmission() {
        val c = coordinator(); val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene))
        val input = CompletedInput(target, StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(1.0), floatArrayOf(0.5f), longArrayOf(0)))
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(memory.snapshot(), input.command(CommandId("external"), ObjectId("external"), 0))).patch
        memory.commit(patch); assertIs<DispatchResult.NoWork>(c.dispatch(memory.snapshot()))
        assertEquals(SessionOpenFailure.STALE_FRAME, assertIs<SessionOpenResult.Rejected>(c.openSession(frame, 10)).reason)
        val token = assertIs<SessionOpenResult.Opened>(c.openSession(frame.copy(sceneRevision = 1), 10)).session
        assertTrue(c.canPublish(token, frame.copy(sceneRevision = 1)))
    }
    @Test fun completedButUnsubmittedInputRequiresTwoPhaseRecoveryAcknowledgment() {
        val c = coordinator(); val s = session(c); val stroke = completed(c, s)
        assertEquals(SessionInputFailure.COMPLETED_PENDING, assertIs<SessionInputResult.Rejected>(c.consume(s, batch(2, PointerPhase.DOWN), target)).reason)
        assertNull(c.offerPending(s)); val stop = c.closeSession(s)!!
        assertEquals(0, stop.cancelledRealSamples); assertTrue(stop.completedPending)
        c.beginClose(); assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
        val offer = assertNotNull(c.offerPending(s)); assertSame(offer, c.offerPending(s))
        val recovery = assertIs<RecoveryPayload.Pending>(offer.payload)
        assertSame(stroke, recovery.completed); assertNull(recovery.proposal)
        assertTrue(c.status().inputReservedBytes > 0); assertFalse(coordinator().acknowledge(offer))
        assertEquals(1, c.status().pendingSessions); assertTrue(c.acknowledge(offer)); assertFalse(c.acknowledge(offer))
        assertEquals(stroke, recovery.completed); assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
        assertFailsWith<IllegalStateException> { offer.payload }
    }
    @Test fun capacityRejectedProposalKeepsStableIdsAndFullSamplesAcrossClose() {
        val c = coordinator(1); val s = session(c); submit(c, s, "first")
        val second = completed(c, s, 2)
        assertEquals(AdmissionRejection.ENTRY_CAPACITY, assertIs<AdmissionResult.Rejected>(c.submitPending(s, CommandId("second"), ObjectId("second"))).reason)
        assertEquals(AdmissionRejection.REUSED_ID, assertIs<AdmissionResult.Rejected>(c.submitPending(s, CommandId("changed"), ObjectId("second"))).reason)
        c.beginClose(); val offer = assertNotNull(c.offerPending(s)); val payload = assertIs<RecoveryPayload.Pending>(offer.payload)
        assertSame(second, payload.completed); assertEquals(CommandId("second"), payload.proposal!!.id)
        assertEquals(AdmissionRejection.ENTRY_CAPACITY, payload.lastAdmissionFailure)
        assertTrue(c.acknowledge(offer)); assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene)); confirm(c, memory)
        assertIs<RecoveryPayload.Confirmed>(handoff(c, CommandId("first")))
        assertEquals(DocumentClosePhase.CLOSED, c.status().phase); assertEquals(1, memory.snapshot().objectCount)
    }
    @Test fun stoppingSessionStillAllowsPendingAdmissionIntoLiveDocument() {
        val c = coordinator(); val s = session(c); completed(c, s); c.closeSession(s)
        assertFalse(c.canPublish(s, frame)); assertIs<AdmissionResult.Admitted>(c.submitPending(s, CommandId("op"), ObjectId("stroke")))
        assertEquals(0L, c.status().inputReservedBytes)
        c.beginClose(); val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene)); confirm(c, memory)
        val offer = assertNotNull(c.offerSubmission(CommandId("op")))
        assertSame(offer, c.offerSubmission(CommandId("op"))); assertTrue(c.status().submissionChargedBytes > 0)
        assertEquals(Durability.MEMORY_ONLY, assertIs<RecoveryPayload.Confirmed>(offer.payload).receipt.durability)
        assertTrue(c.acknowledge(offer)); assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
    }
    @Test fun admissionCanRetryCapacityBeforeCloseWithoutChangingCommandIdentity() {
        val c = coordinator(1); val s = session(c); submit(c, s, "a"); completed(c, s, 2)
        assertIs<AdmissionResult.Rejected>(c.submitPending(s, CommandId("b"), ObjectId("b")))
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene)); confirm(c, memory); handoff(c, CommandId("a"))
        assertIs<AdmissionResult.Admitted>(c.submitPending(s, CommandId("b"), ObjectId("b")))
        assertNull(c.pending(s)); c.beginClose(); confirm(c, memory); handoff(c, CommandId("b"))
        assertEquals(2, memory.snapshot().objectCount); assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
    }
    @Test fun ambiguousCommitAtCloseReplaysExactPatchAndRejectsOldAcknowledgment() {
        val c = coordinator(); val s = session(c); submit(c, s, "op")
        val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene)); val first = ready(c, memory)
        val accepted = memory.commit(first.patch); c.beginClose()
        assertNull(c.offerSubmission(first.id)); assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
        assertEquals(CompletionUpdate.UPDATED, c.transportFailed(first, "ack lost")); assertNull(c.offerSubmission(first.id))
        assertIs<SubmissionState.RetryRequired>(c.status().submissions.single().state)
        assertTrue(c.retry(first.id)); val retry = ready(c, memory); assertSame(first.patch, retry.patch)
        assertEquals(CompletionUpdate.STALE_ATTEMPT, c.complete(first, accepted))
        assertEquals(CompletionUpdate.UPDATED, c.complete(retry, memory.commit(retry.patch)))
        handoff(c, first.id); assertEquals(1L, memory.snapshot().revision); assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
    }
    @Test fun definitiveRejectionTransfersOnlyAfterReceiverAcknowledges() {
        val c = coordinator(); val s = session(c); submit(c, s, "a"); val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene), journalCapacity = 1)
        confirm(c, memory); handoff(c, CommandId("a")); completed(c, s, 2)
        c.submitPending(s, CommandId("b"), ObjectId("b")); c.beginClose(); confirm(c, memory)
        val offer = assertNotNull(c.offerSubmission(CommandId("b"))); val payload = assertIs<RecoveryPayload.Blocked>(offer.payload)
        assertEquals(Rejection.JOURNAL_FULL, payload.reason); assertEquals(2.0, payload.submission.stroke.samples.x(0))
        assertFalse(c.retry(CommandId("b"))); assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
        assertTrue(c.acknowledge(offer)); assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
    }
    @Test fun closingKeepsLateAllocationAndReadersChargedUntilDestructionAcknowledged() {
        val c = coordinator(); val reservation = assertIs<ReservationResult.Reserved>(c.reserveResource(4)).reservation
        val resource = FakeGpuResource("late"); c.beginClose()
        assertEquals(1, c.status().resources.reservations); assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
        assertIs<ResourcePublication.RetiredAfterClose>(c.publishResource(reservation, "late", resource))
        val ticket = assertIs<ResourceReleaseDispatch.Ready<FakeGpuResource>>(c.nextRelease(ResourceLane.GPU)).ticket
        val scheduler = ControlledResourceScheduler(); scheduler.post("destroy", ResourceLane.GPU) { ticket.value.destroy(scheduler) }; scheduler.run("destroy")
        assertEquals(1, c.status().resources.releasing); assertEquals(4L, c.status().resources.chargedBytes)
        c.completeRelease(ticket, ResourceReleaseOutcome.RELEASED)
        assertEquals(DocumentClosePhase.CLOSED, c.status().phase); assertEquals(1, resource.destructions)
    }
    @Test fun unknownDestructionCannotMakeCloseCompleteOrTriggerDoubleFree() {
        val c = coordinator(); val reservation = assertIs<ReservationResult.Reserved>(c.reserveResource(4)).reservation
        val resource = FakeGpuResource("tile"); val handle = assertIs<ResourcePublication.Resident>(c.publishResource(reservation, "tile", resource)).handle
        c.beginClose(); val ticket = assertIs<ResourceReleaseDispatch.Ready<FakeGpuResource>>(c.nextRelease(ResourceLane.GPU)).ticket
        val scheduler = ControlledResourceScheduler(); scheduler.post("destroy", ResourceLane.GPU) { ticket.value.destroy(scheduler) }; scheduler.run("destroy")
        c.completeRelease(ticket, ResourceReleaseOutcome.UNKNOWN)
        assertEquals(listOf(handle), c.status().resources.unknown); assertIs<ResourceReleaseDispatch.Idle>(c.nextRelease(ResourceLane.GPU))
        assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
        c.resolveUnknown(handle, ResourceReleaseOutcome.RELEASED)
        assertEquals(ResourceCompletion.STALE, c.completeRelease(ticket, ResourceReleaseOutcome.RELEASED))
        assertEquals(1, resource.destructions); assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
    }
    @Test fun unallocatedReservationMustBeExplicitlyCancelledBeforeOwnerCanClose() {
        val c = coordinator(); val reservation = assertIs<ReservationResult.Reserved>(c.reserveResource(4)).reservation
        c.beginClose(); assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
        assertTrue(c.cancelUnallocated(reservation)); assertFalse(c.cancelUnallocated(reservation))
        assertEquals(DocumentClosePhase.CLOSED, c.status().phase)
    }
    @Test fun closeCommitFenceAndHandoffInterleaveWithoutDroppingAnyObligation() {
        for (closeFirst in listOf(false, true)) {
            val c = coordinator(); val s = session(c); submit(c, s, "op")
            val memory = MemorySceneCoordinator(SceneSnapshot.empty(scene)); val attempt = ready(c, memory)
            val reservation = assertIs<ReservationResult.Reserved>(c.reserveResource(4)).reservation
            val resource = FakeGpuResource("tile"); c.publishResource(reservation, "tile", resource)
            val reader = assertIs<ResourceBorrow.Borrowed<FakeGpuResource>>(c.borrowResource("tile")).lease
            val scheduler = ControlledResourceScheduler(); var displayed = true
            scheduler.post("close", ResourceLane.OWNER) { c.beginClose() }
            scheduler.post("commit", ResourceLane.WORKER) {
                val result = memory.commit(attempt.patch)
                scheduler.post("ack", ResourceLane.OWNER) { c.complete(attempt, result) }
            }
            scheduler.post("fence", ResourceLane.GPU) {
                assertEquals("tile", reader.value.read())
                scheduler.post("reader-return", ResourceLane.OWNER) { displayed = c.canPublish(s, frame); c.returnLease(reader) }
            }
            if (closeFirst) { scheduler.run("close"); scheduler.run("commit") }
            else { scheduler.run("commit"); scheduler.run("close") }
            assertEquals(DocumentClosePhase.CLOSING, c.status().phase); assertEquals(1, c.status().resources.readers)
            assertIs<ResourceReleaseDispatch.Idle>(c.nextRelease(ResourceLane.GPU))
            scheduler.run("fence"); scheduler.run("reader-return"); assertFalse(displayed)
            val release = assertIs<ResourceReleaseDispatch.Ready<FakeGpuResource>>(c.nextRelease(ResourceLane.GPU)).ticket
            scheduler.post("destroy", ResourceLane.GPU) { release.value.destroy(scheduler) }
            scheduler.run("destroy"); scheduler.run("ack")
            handoff(c, attempt.id); assertEquals(DocumentClosePhase.CLOSING, c.status().phase)
            c.completeRelease(release, ResourceReleaseOutcome.RELEASED)
            assertEquals(DocumentClosePhase.CLOSED, c.status().phase); assertEquals(1, resource.destructions)
            assertEquals(1, memory.snapshot().objectCount); assertEquals(0, scheduler.queued)
        }
    }
    @Test fun sessionLimitsReserveInputBeforeAdmissionAndRejectForeignTokens() {
        val underfunded = DocumentCloseCoordinator<String, FakeGpuResource>(scene, inputBudget = 536)
        assertEquals(SessionOpenFailure.INPUT_BUDGET, assertIs<SessionOpenResult.Rejected>(underfunded.openSession(frame, 10)).reason)
        assertEquals(0L, underfunded.status().inputReservedBytes)
        val c = DocumentCloseCoordinator<String, FakeGpuResource>(scene, maxSessions = 1, inputBudget = 1200)
        assertEquals(SessionOpenFailure.INPUT_BUDGET, assertIs<SessionOpenResult.Rejected>(c.openSession(frame, 11)).reason)
        val s = assertIs<SessionOpenResult.Opened>(c.openSession(frame, 10)).session
        assertEquals(1200L, c.status().inputReservedBytes)
        assertEquals(SessionOpenFailure.SESSION_LIMIT, assertIs<SessionOpenResult.Rejected>(c.openSession(frame, 1)).reason)
        val foreign = session(coordinator()); assertNull(c.closeSession(foreign)); assertNull(c.pending(foreign))
        assertIs<SessionInputResult.Rejected>(c.consume(foreign, batch(0, PointerPhase.DOWN), target))
        assertIs<SessionInputResult.Rejected>(c.consume(s, batch(0, PointerPhase.DOWN), target.copy(sceneId = SceneId("foreign"))))
        c.closeSession(s); assertEquals(0L, c.status().inputReservedBytes)
        assertIs<SessionOpenResult.Opened>(c.openSession(frame, 10))
    }
}
