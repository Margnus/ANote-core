package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PreviewInputBudgetTest {
    private fun note(bytes: Long, journal: Int = 128) = PagedPreviewNotebook(
        DocumentRoot.empty(SceneId("budget"), "title", PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)),
        "bounded", DocumentBudget(inputBytes = bytes, journalEntries = journal))
    private fun offer(note: PagedPreviewNotebook, phase: PointerPhase, vararg times: Long) =
        note.offerRealBatch(phase, times.lastOrNull() ?: 0, DoubleArray(times.size) { times[it].toDouble() },
            DoubleArray(times.size) { 2.0 }, FloatArray(times.size) { .5f }, times)

    @Test fun insufficientBudgetRejectsBeforeIdentityAndEmptyUpCanFinishExactBudget() {
        for (bytes in listOf(0L, InputReplaySession.reservationBytes(1) - 1)) {
            val n = note(bytes); val root = n.snapshot()
            repeat(3) {
                assertEquals(0, offer(n, PointerPhase.DOWN, 1).acceptedSamples)
                assertFalse(n.hasActiveStroke); assertEquals(0L, n.inputReservedByteCharge)
                assertSame(root, n.snapshot())
            }
            assertTrue(n.rename("renamed"))
            assertEquals(CommandId("bounded-op-1"), n.authorityStatus().nextUndoOperationId)
        }
        val n = note(InputReplaySession.reservationBytes(1))
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        assertEquals(InputReplaySession.reservationBytes(1), n.inputReservedByteCharge)
        val done = n.offerRealBatch(PointerPhase.UP, 1, doubleArrayOf(), doubleArrayOf(), floatArrayOf(), longArrayOf())
        assertEquals(PreviewInputDisposition.COMMITTED, done.disposition)
        assertEquals(1, n.pointCount(0)); assertEquals(0L, n.inputReservedByteCharge)
    }
    @Test fun wholeBatchRefusalPreservesPrefixAndAllowsSmallerRetry() {
        val n = note(InputReplaySession.reservationBytes(3))
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        val rejected = offer(n, PointerPhase.MOVE, 2, 3, 4)
        assertFalse(rejected.admitted); assertEquals(0, rejected.acceptedSamples)
        assertEquals(1, rejected.activeSamples); assertEquals(InputRejection.CAPACITY, rejected.inputReason)
        assertTrue(offer(n, PointerPhase.MOVE, 2).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 3).disposition)
        val decoded = PagedPreviewCodec.decode(n.capture().encode()).scene.objects().single().samples
        assertEquals(listOf(1.0, 2.0, 3.0), List(decoded.size) { decoded.x(it) })
    }
    @Test fun chunkBoundaryIsChargedBeforeDownAndCancellationReleasesReservation() {
        val n = note(InputReplaySession.reservationBytes(257) - 1)
        val tooMany = LongArray(257) { it.toLong() }
        assertFalse(offer(n, PointerPhase.DOWN, *tooMany).admitted)
        assertFalse(n.hasActiveStroke)
        assertTrue(offer(n, PointerPhase.DOWN, *tooMany.copyOf(256)).admitted)
        assertEquals(InputReplaySession.reservationBytes(256), n.inputReservedByteCharge)
        n.cancelStroke(); assertEquals(0L, n.inputReservedByteCharge)
        assertTrue(offer(n, PointerPhase.DOWN, 300).admitted)
        n.cancelStroke(); assertTrue(n.retire())
        assertFalse(offer(n, PointerPhase.DOWN, 301).admitted)
    }
    @Test fun pendingAcceptedInkKeepsChargeAfterJournalFailureAndCancellation() {
        val n = note(InputReplaySession.reservationBytes(2), 1)
        assertTrue(n.rename("filled"))
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        assertEquals(PreviewInputDisposition.PENDING_COMMIT, offer(n, PointerPhase.UP, 2).disposition)
        val pending = n.pendingStroke()
        assertEquals(184L, n.inputReservedByteCharge)
        n.cancelStroke(); assertSame(pending, n.pendingStroke()); assertEquals(184L, n.inputReservedByteCharge)
        assertFalse(n.retryPendingStroke()); assertFalse(n.retire())
        assertFalse(offer(n, PointerPhase.DOWN, 3).admitted)
        assertSame(pending, n.pendingStroke())
    }

    @Test fun arenaAndCanonicalLiveLimitsBothApplyAfterRemoteIntegration() {
        val root = DocumentRoot.empty(SceneId("combined"), "title", PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0))
        val probe = PagedPreviewNotebook(root, "bounded")
        assertTrue(offer(probe, PointerPhase.DOWN, 1).admitted)
        assertTrue(offer(probe, PointerPhase.UP, 2).admitted)
        val live = probe.authorityStatus().liveByteCharge
        for (arenaPoints in listOf(1, 3)) {
            val n = PagedPreviewNotebook(root, "bounded", DocumentBudget(liveBytes = live,
                inputBytes = InputReplaySession.reservationBytes(arenaPoints)))
            assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
            val capacity = minOf(arenaPoints, 2)
            assertEquals(InputReplaySession.reservationBytes(capacity), n.inputReservedByteCharge)
            assertFalse(offer(n, PointerPhase.MOVE, 2, 3).admitted)
            if (capacity == 2) assertTrue(offer(n, PointerPhase.MOVE, 2).admitted)
            val up = n.offerRealBatch(PointerPhase.UP, 2, doubleArrayOf(), doubleArrayOf(), floatArrayOf(), longArrayOf())
            assertEquals(PreviewInputDisposition.COMMITTED, up.disposition)
            assertEquals(capacity, n.pointCount(0)); assertFalse(n.hasPendingStroke)
        }
    }
}
