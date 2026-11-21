package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** DEV-042 E3b: three-brush data link at algorithm version 1, without opening PRESSURE. */
class SelectPenKindTest {
    @Test fun mixedBrushPreviewReopensWithoutChangingCanonicalInk() {
        val n = note()
        for (kind in listOf(BrushKind.MONOLINE, BrushKind.HIGHLIGHTER)) {
            accepted(n, PreviewEdit.SelectPen(0x7f123456, 6.0, kind))
            assertTrue(n.beginStroke(1.0, 2.0, .25f, 1))
            assertTrue(n.endStroke(3.0, 4.0, .75f, 2))
        }
        val encoded = n.capture().encode()
        val root = assertNotNull(PreviewDocumentReader.read(encoded).root)
        val reopened = assertNotNull(PagedPreviewNotebook.open(root, "mixed-reopen").notebook)
        assertEquals(encoded, reopened.capture().encode())
        assertEquals(listOf(BrushKind.MONOLINE, BrushKind.HIGHLIGHTER),
            root.scene.objects().map { it.brush.kind })
        assertTrue(root.scene.objects().all { it.brush.argb == 0x7f123456 && it.brush.width == 6.0 })
        assertEquals(2, reopened.totalStrokeCount)
    }

    @Test fun pendingHighlighterRecoversAndCommitsExactlyOnce() {
        val fault = PendingInputOwnerFault(nonce = "highlighter-recovery")
        val n = fault.note
        accepted(n, PreviewEdit.SelectPen(0x7f123456, 6.0, BrushKind.HIGHLIGHTER))
        assertTrue(n.beginStroke(1.25, 2.5, .25f, 1))
        fault.arm()
        assertFailsWith<IllegalStateException> { n.endStroke(3.5, 4.75, .75f, 2) }
        val original = n.captureRecovery()
        val encoded = original.encode()
        val snapshot = assertNotNull(PreviewRecoveryCodec.read(encoded).snapshot)
        assertTrue(original.sameContent(snapshot))
        val reopened = assertNotNull(PagedPreviewNotebook.openRecovery(snapshot, "highlight-resume").notebook)
        assertTrue(reopened.hasPendingStroke)
        assertTrue(reopened.retryPendingStroke())
        val committed = reopened.snapshot()
        assertTrue(reopened.retryPendingStroke())
        assertSame(committed, reopened.snapshot())
        assertEquals(1, reopened.totalStrokeCount)
        val stroke = reopened.stroke(0)
        assertEquals(BrushKind.HIGHLIGHTER, stroke.brush.kind)
        assertEquals(0x7f123456, stroke.brush.argb)
        assertEquals(6.0, stroke.brush.width)
        assertEquals(2, stroke.samples.size)
        assertNotNull(PreviewRecoveryCodec.read(reopened.capture().encode()).snapshot)
        assertEquals(encoded, original.encode())
    }
    private fun note(): PagedPreviewNotebook = PagedPreviewNotebook(
        DocumentRoot.empty(SceneId("pen"), "pen",
            PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)), "pen-session", DocumentBudget())

    private fun accepted(note: PagedPreviewNotebook, request: PreviewEdit) {
        val result = note.edit(request)
        assertTrue(result.accepted); assertNull(result.failure); assertNull(note.lastFailure)
    }

    @Test fun defaultKindRemainsMonolineForLegacyCallers() {
        val n = note()
        accepted(n, PreviewEdit.SelectPen(-1, 4.0))
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(n.endStroke(2.0, 3.0, .5f, 2))
        assertEquals(BrushKind.MONOLINE, n.stroke(0).brush.kind)
        assertEquals(1, n.stroke(0).brush.algorithmVersion)
    }

    @Test fun highlighterKindSurvivesUndoRedoAndCodecRoundTrip() {
        val n = note()
        accepted(n, PreviewEdit.SelectPen(-1, 4.0, BrushKind.HIGHLIGHTER))
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(n.endStroke(2.0, 3.0, .5f, 2))
        assertEquals(BrushKind.HIGHLIGHTER, n.stroke(0).brush.kind)
        assertEquals(1, n.stroke(0).brush.algorithmVersion)
        accepted(n, PreviewEdit.Undo)
        assertEquals(0, n.snapshot().scene.objectCount)
        accepted(n, PreviewEdit.Redo)
        assertEquals(BrushKind.HIGHLIGHTER, n.stroke(0).brush.kind)
        val decoded = PagedPreviewCodec.decode(n.capture().encode())
        assertEquals(BrushKind.HIGHLIGHTER, decoded.scene.objects().single().brush.kind)
        assertEquals(1, decoded.scene.objects().single().brush.algorithmVersion)
    }

    @Test fun pressureIsRejectedAtVersionOneAndKeepsPreviousSelection() {
        val n = note()
        accepted(n, PreviewEdit.SelectPen(-1, 4.0, BrushKind.HIGHLIGHTER))
        val result = n.edit(PreviewEdit.SelectPen(-1, 4.0, BrushKind.PRESSURE))
        assertFalse(result.accepted)
        assertEquals(PreviewFailureKind.UNSUPPORTED_BRUSH, result.failure?.kind)
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(n.endStroke(2.0, 3.0, .5f, 2))
        assertEquals(BrushKind.HIGHLIGHTER, n.stroke(0).brush.kind)
    }

    @Test fun kindSwitchBetweenStrokesKeepsExistingInkUnchanged() {
        val n = note()
        accepted(n, PreviewEdit.SelectPen(-1, 4.0))
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(n.endStroke(2.0, 3.0, .5f, 2))
        accepted(n, PreviewEdit.SelectPen(-1, 6.0, BrushKind.HIGHLIGHTER))
        assertTrue(n.beginStroke(10.0, 20.0, .5f, 3)); assertTrue(n.endStroke(20.0, 30.0, .5f, 4))
        assertEquals(2, n.snapshot().scene.objectCount)
        assertEquals(BrushKind.MONOLINE, n.stroke(0).brush.kind)
        assertEquals(BrushKind.HIGHLIGHTER, n.stroke(1).brush.kind)
        assertEquals(6.0, n.stroke(1).brush.width)
    }
}
