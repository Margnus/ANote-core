package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class ReadOnlyNotebookTest {
    private val a = PageDescriptor(PageId("a"), LayerId("la"), 595.0, 842.0)
    private val b = PageDescriptor(PageId("b"), LayerId("lb"), 595.0, 842.0)
    private val root = DocumentRoot.compose("reading", SceneSnapshot.empty(SceneId("doc")), listOf(a, b))
    private fun note() = PagedPreviewNotebook(root, "reading-session")
    private fun draw(note: PagedPreviewNotebook) {
        assertTrue(note.beginStroke(1.0, 2.0, .4f, 1)); assertTrue(note.endStroke(3.0, 4.0, .8f, 2))
    }

    @Test fun readonlyRejectsOriginalRawAndLegacyInputWithoutIdentityOrBudgetChanges() {
        val note = note(); val control = note()
        assertTrue(note.setReadOnly(true))
        for (phase in PointerPhase.entries) {
            val result = assertNotNull(note.prepareRealBatch(phase, 0))
            assertEquals(0, result.acceptedSamples)
            assertEquals(PreviewFailureKind.READ_ONLY, result.failure!!.kind)
            val offered = note.offerRealBatch(phase, 1, doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))
            assertEquals(PreviewInputDisposition.REJECTED, offered.disposition)
            assertEquals(0, offered.acceptedSamples)
        }
        assertFalse(note.beginStroke(1.0, 2.0, .5f, 1))
        assertFalse(note.appendStroke(1.0, 2.0, .5f, 1))
        assertFalse(note.endStroke(1.0, 2.0, .5f, 1))
        assertEquals(0, note.inputReservedByteCharge); assertFalse(note.hasActiveStroke)
        assertSame(root, note.snapshot())
        assertTrue(note.setReadOnly(false)); draw(note); draw(control)
        assertEquals(PagedPreviewCodec.encode(control.snapshot()), PagedPreviewCodec.encode(note.snapshot()))
    }

    @Test fun originalEditFacadeAndDirectMethodsCannotBypassReadingMode() {
        val note = note(); draw(note); assertTrue(note.canUndo)
        val before = PagedPreviewCodec.encode(note.snapshot()); val status = note.authorityStatus()
        assertTrue(note.setReadOnly(true)); assertFalse(note.canUndo); assertFalse(note.canRedo)
        for (edit in listOf(PreviewEdit.Rename("changed"), PreviewEdit.SetPaper(400.0, 600.0, PaperKind.GRID),
            PreviewEdit.InsertPage, PreviewEdit.CopyPage, PreviewEdit.DeletePage, PreviewEdit.MovePage("b"), PreviewEdit.Undo, PreviewEdit.Redo)) {
            val result = note.edit(edit)
            assertFalse(result.accepted); assertEquals(PreviewFailureKind.READ_ONLY, result.failure!!.kind)
        }
        for (action in listOf<() -> Boolean>({ note.rename("changed") }, { note.setPaper(400.0, 600.0, PaperKind.DOTS) },
            { note.insertPage() }, { note.copyCurrentPage() }, { note.deleteCurrentPage() }, { note.moveCurrentPage(b.id) },
            { note.undo() }, { note.redo() })) assertFalse(action())
        assertEquals(before, PagedPreviewCodec.encode(note.snapshot())); assertEquals(status, note.authorityStatus())
        assertTrue(note.setReadOnly(false)); assertTrue(note.canUndo); assertTrue(note.undo()); assertTrue(note.canRedo)
        assertTrue(note.setReadOnly(true)); assertFalse(note.canRedo)
        assertTrue(note.setReadOnly(false)); assertTrue(note.redo()); assertEquals(1, note.totalStrokeCount)
    }

    @Test fun navigationAndToolSurviveModeRoundtripWithoutContentMutation() {
        val note = note(); assertTrue(note.selectPen(0xff123456.toInt(), 7.0))
        assertTrue(note.setReadOnly(true)); val before = PagedPreviewCodec.encode(note.snapshot())
        assertTrue(note.edit(PreviewEdit.SelectPage("b")).accepted)
        assertTrue(note.isReadOnly); assertEquals(b.id, note.currentPageId)
        assertEquals(before, PagedPreviewCodec.encode(note.snapshot()))
        assertTrue(note.setReadOnly(false)); draw(note)
        assertEquals(b.id, note.currentPageId)
        assertEquals(0xff123456.toInt(), note.stroke(0).brush.argb); assertEquals(7.0, note.stroke(0).brush.width)
        assertTrue(note.setReadOnly(true)); assertTrue(note.selectPen(0xff654321.toInt(), 3.0))
        assertTrue(note.setReadOnly(false)); draw(note)
        assertEquals(0xff654321.toInt(), note.stroke(1).brush.argb); assertEquals(3.0, note.stroke(1).brush.width)
    }

    @Test fun activeInputPreventsModeChangeAndKeepsAcceptedPrefix() {
        val note = note(); assertTrue(note.beginStroke(1.0, 2.0, .4f, 1))
        val charge = note.inputReservedByteCharge
        assertFalse(note.setReadOnly(true)); assertEquals(PreviewFailureKind.ACTIVE_STROKE, note.lastFailure!!.kind)
        assertFalse(note.isReadOnly); assertTrue(note.hasActiveStroke); assertEquals(charge, note.inputReservedByteCharge)
        assertTrue(note.endStroke(3.0, 4.0, .8f, 2)); assertEquals(2, note.pointCount(0))
        assertTrue(note.setReadOnly(true)); assertEquals(1, note.totalStrokeCount)
    }

    @Test fun retainedCompletionBlocksModeChangeUntilExplicitRetry() {
        val samples = StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))
        val pending = DocumentCommand.Canvas(a.id, CanvasCommand.AddStroke(CommandId("pending"), 0,
            StrokeTarget(root.id, a.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)), ObjectId("pending-ink"), samples))
        val note = assertNotNull(PagedPreviewNotebook.openRecovery(PagedPreviewSnapshot(root, pending), "recover-reading").notebook)
        val charge = note.inputReservedByteCharge
        assertFalse(note.setReadOnly(true)); assertEquals(PreviewFailureKind.PENDING_STROKE, note.lastFailure!!.kind)
        assertSame(pending, note.pendingStroke()); assertEquals(charge, note.inputReservedByteCharge); assertSame(root, note.snapshot())
        assertTrue(note.retryPendingStroke()); assertTrue(note.setReadOnly(true)); assertEquals(1, note.totalStrokeCount)
    }

    @Test fun closedAndWrongOwnerModeAccessFailsWithoutChangingContent() {
        var forbidden = false
        val note = PagedPreviewNotebook(root, "owner-reading", ownerCheck = { check(!forbidden) })
        forbidden = true
        assertFailsWith<IllegalStateException> { note.setReadOnly(true) }
        assertFailsWith<IllegalStateException> { note.isReadOnly }
        forbidden = false; assertFalse(note.isReadOnly); assertTrue(note.retire())
        assertFalse(note.setReadOnly(true)); assertEquals(PreviewFailureKind.CLOSED, note.lastFailure!!.kind)
        assertSame(root, note.snapshot())
    }
}
