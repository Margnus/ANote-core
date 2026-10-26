package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PreviewInputCapacityTest {
    private fun note() = PagedPreviewNotebook(
        DocumentRoot.empty(SceneId("capacity"), "title",
            PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)),
        "capacity-session", DocumentBudget(maxObjects = 1))

    private fun offer(note: PagedPreviewNotebook, phase: PointerPhase, time: Long) =
        note.offerRealBatch(phase, time, doubleArrayOf(time.toDouble()), doubleArrayOf(2.0),
            floatArrayOf(.5f), longArrayOf(time))

    @Test fun fullDocumentRefusesDownBeforeAcceptingInkAndUndoCanRestoreWriting() {
        val note = note()
        assertTrue(offer(note, PointerPhase.DOWN, 1).admitted)
        assertEquals(1_329_280L, note.inputReservedByteCharge)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(note, PointerPhase.UP, 2).disposition)
        assertEquals(0L, note.inputReservedByteCharge)
        val full = note.snapshot()
        repeat(3) {
            val refusal = offer(note, PointerPhase.DOWN, 3)
            assertEquals(PreviewInputDisposition.REJECTED, refusal.disposition)
            assertEquals(PreviewFailureKind.CAPACITY, refusal.failure?.kind)
            assertEquals(0, refusal.acceptedSamples)
            assertEquals(0, refusal.activeSamples)
            assertSame(full, note.snapshot())
            assertFalse(note.hasActiveStroke)
            assertFalse(note.hasPendingStroke)
            assertEquals(0L, note.inputReservedByteCharge)
        }
        assertTrue(note.edit(PreviewEdit.Undo).accepted)
        assertTrue(offer(note, PointerPhase.DOWN, 4).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(note, PointerPhase.UP, 5).disposition)
        assertEquals(CommandId("capacity-session-op-4"), note.authorityStatus().nextUndoOperationId)
        val restored = PagedPreviewCodec.decode(note.capture().encode())
        assertEquals(1, restored.scene.objectCount)
        assertEquals(listOf(4.0, 5.0), restored.scene.objects().single().samples.let {
            List(it.size) { index -> it.x(index) }
        })
    }

    @Test fun redoRestoresTheCapacityGateWithoutCreatingAPendingCommand() {
        val note = note()
        assertTrue(offer(note, PointerPhase.DOWN, 1).admitted)
        assertTrue(offer(note, PointerPhase.UP, 2).admitted)
        assertTrue(note.undo())
        assertTrue(note.redo())
        val before = note.capture()
        assertFalse(offer(note, PointerPhase.DOWN, 3).admitted)
        assertTrue(before.sameContent(note.capture()))
        assertTrue(note.canUndo)
        assertNull(note.pendingStroke())
        assertTrue(note.retire())
    }

    private fun byteLimitedNote(points: Int): PagedPreviewNotebook {
        // Derive a real committed payload boundary independently of the new input admission helper.
        val root = DocumentRoot.empty(SceneId("bytes"), "title",
            PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0))
        val probe = PagedPreviewNotebook(root, "bytes-session")
        val times = LongArray(points) { it + 1L }
        assertTrue(probe.offerRealBatch(PointerPhase.DOWN, points.toLong(), DoubleArray(points), DoubleArray(points),
            FloatArray(points), times).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED,
            probe.offerRealBatch(PointerPhase.UP, points.toLong(), doubleArrayOf(), doubleArrayOf(), floatArrayOf(), longArrayOf()).disposition)
        return PagedPreviewNotebook(root, "bytes-session", DocumentBudget(liveBytes = probe.authorityStatus().liveByteCharge))
    }

    @Test fun liveBytesFullRefusesDownWithoutPendingAndUndoRestoresWriting() {
        val n = byteLimitedNote(2)
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 2).disposition)
        val full = n.capture(); val status = n.authorityStatus()
        repeat(3) {
            val result = offer(n, PointerPhase.DOWN, 3)
            assertEquals(PreviewFailureKind.CAPACITY, result.failure?.kind)
            assertEquals(0, result.acceptedSamples)
            assertEquals(0L, n.inputReservedByteCharge)
            assertTrue(full.sameContent(n.capture()))
            assertEquals(status, n.authorityStatus())
            assertNull(n.pendingStroke())
        }
        assertTrue(n.canUndo); assertTrue(n.undo())
        assertTrue(offer(n, PointerPhase.DOWN, 4).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 5).disposition)
        assertEquals(CommandId("bytes-session-op-4"), n.authorityStatus().nextUndoOperationId)
        assertEquals(listOf(4.0, 5.0), PagedPreviewCodec.decode(n.capture().encode()).scene.objects().single().samples.let {
            List(it.size) { index -> it.x(index) }
        })
    }

    @Test fun liveBytesBoundWholeBatchesAndEmptyUpCommitsAllAdmittedPoints() {
        val n = byteLimitedNote(3)
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        assertTrue(offer(n, PointerPhase.MOVE, 2).admitted)
        val before = n.snapshot(); val reserved = n.inputReservedByteCharge
        val refusal = n.offerRealBatch(PointerPhase.MOVE, 4, doubleArrayOf(3.0, 4.0), doubleArrayOf(2.0, 2.0),
            floatArrayOf(.5f, .5f), longArrayOf(3, 4))
        assertEquals(0, refusal.acceptedSamples); assertEquals(2, refusal.activeSamples)
        assertEquals(InputRejection.CAPACITY, refusal.inputReason)
        assertSame(before, n.snapshot()); assertEquals(reserved, n.inputReservedByteCharge)
        assertTrue(offer(n, PointerPhase.MOVE, 3).admitted) // refused suffix did not advance time/sequence
        val up = n.offerRealBatch(PointerPhase.UP, 3, doubleArrayOf(), doubleArrayOf(), floatArrayOf(), longArrayOf())
        assertEquals(PreviewInputDisposition.COMMITTED, up.disposition)
        assertNull(n.pendingStroke()); assertEquals(0L, n.inputReservedByteCharge)
        assertEquals(3, n.pointCount(0)); assertEquals(3.0, n.pointX(0, 2))
    }

    @Test fun liveByteDownBatchRefusalDoesNotConsumeIdentityOrPreventRetry() {
        val n = byteLimitedNote(2)
        val before = n.snapshot()
        val refused = n.offerRealBatch(PointerPhase.DOWN, 3, doubleArrayOf(1.0, 2.0, 3.0),
            doubleArrayOf(2.0, 2.0, 2.0), floatArrayOf(.5f, .5f, .5f), longArrayOf(1, 2, 3))
        assertEquals(0, refused.acceptedSamples); assertEquals(0, refused.activeSamples)
        assertFalse(n.hasActiveStroke); assertNull(n.pendingStroke()); assertSame(before, n.snapshot())
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 2).disposition)
        assertEquals(CommandId("bytes-session-op-1"), n.authorityStatus().nextUndoOperationId)
        assertEquals("bytes-session-ink-2", n.strokeId(0))
    }
}
