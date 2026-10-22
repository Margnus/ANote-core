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
        assertEquals(PreviewInputDisposition.COMMITTED, offer(note, PointerPhase.UP, 2).disposition)
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
}
