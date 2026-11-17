package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** DEV-042 E3b: three-brush data link at algorithm version 1, without opening PRESSURE. */
class SelectPenKindTest {
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
