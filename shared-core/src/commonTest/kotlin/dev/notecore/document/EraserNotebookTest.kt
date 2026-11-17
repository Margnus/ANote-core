package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** DEV-042 E4a: whole-stroke eraser command through the preview notebook facade. */
class EraserNotebookTest {
    private fun note(): PagedPreviewNotebook = PagedPreviewNotebook(
        DocumentRoot.empty(SceneId("erase"), "erase",
            PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)), "erase-session", DocumentBudget())

    private var clock = 0L
    private fun stroke(n: PagedPreviewNotebook, x1: Double, y1: Double, x2: Double, y2: Double) {
        assertTrue(n.beginStroke(x1, y1, .5f, ++clock))
        assertTrue(n.endStroke(x2, y2, .5f, ++clock))
    }

    private fun path(vararg points: Pair<Double, Double>) = points.map { PointD(it.first, it.second) }

    @Test fun eraseOneStrokeKeepsOthersAndUndoRedoRestores() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        stroke(n, 100.0, 300.0, 200.0, 300.0)
        val result = n.edit(PreviewEdit.EraseStrokes(path(150.0 to 90.0, 150.0 to 110.0), 6.0))
        assertTrue(result.accepted); assertNull(result.failure)
        assertEquals(1, n.snapshot().scene.objectCount)
        assertEquals(300.0, n.stroke(0).samples.y(0))
        assertTrue(n.edit(PreviewEdit.Undo).accepted)
        assertEquals(2, n.snapshot().scene.objectCount)
        assertTrue(n.edit(PreviewEdit.Redo).accepted)
        assertEquals(1, n.snapshot().scene.objectCount)
    }

    @Test fun multiHitEraseIsSingleHistoryEntry() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        stroke(n, 100.0, 200.0, 200.0, 200.0)
        stroke(n, 100.0, 300.0, 200.0, 300.0)
        assertTrue(n.edit(PreviewEdit.EraseStrokes(path(150.0 to 50.0, 150.0 to 350.0), 6.0)).accepted)
        assertEquals(0, n.snapshot().scene.objectCount)
        // One undo restores all three strokes: the gesture was one command, not three.
        assertTrue(n.edit(PreviewEdit.Undo).accepted)
        assertEquals(3, n.snapshot().scene.objectCount)
        assertTrue(n.edit(PreviewEdit.Redo).accepted)
        assertEquals(0, n.snapshot().scene.objectCount)
    }

    @Test fun noHitIsAcceptedWithoutHistoryOrRevisionChange() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        val before = n.changeVersion
        val result = n.edit(PreviewEdit.EraseStrokes(path(500.0 to 500.0, 500.0 to 520.0), 6.0))
        assertTrue(result.accepted); assertNull(result.failure)
        assertEquals(before, n.changeVersion)
        assertEquals(1, n.snapshot().scene.objectCount)
        // The only history entry remains the stroke itself; the miss created none.
        assertTrue(n.edit(PreviewEdit.Undo).accepted)
        assertEquals(0, n.snapshot().scene.objectCount)
    }

    @Test fun invalidPathKeepsContentAndReportsArgumentFailure() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        val before = n.snapshot()
        for (request in listOf(PreviewEdit.EraseStrokes(emptyList(), 6.0),
            PreviewEdit.EraseStrokes(path(150.0 to 90.0), 0.0),
            PreviewEdit.EraseStrokes(path(1e10 to 90.0), 6.0))) {
            val result = n.edit(request)
            assertFalse(result.accepted)
            assertEquals(PreviewFailureKind.INVALID_ARGUMENT, result.failure?.kind)
        }
        assertSame(before, n.snapshot())
        assertEquals(1, n.snapshot().scene.objectCount)
    }

    @Test fun highlighterStrokeErasesThroughSameHitPath() {
        val n = note()
        assertTrue(n.edit(PreviewEdit.SelectPen(-1, 4.0, BrushKind.HIGHLIGHTER)).accepted)
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        assertTrue(n.edit(PreviewEdit.SelectPen(-1, 4.0)).accepted)
        stroke(n, 100.0, 300.0, 200.0, 300.0)
        assertTrue(n.edit(PreviewEdit.EraseStrokes(path(150.0 to 90.0, 150.0 to 110.0), 6.0)).accepted)
        assertEquals(1, n.snapshot().scene.objectCount)
        assertEquals(BrushKind.MONOLINE, n.stroke(0).brush.kind)
        val decoded = PagedPreviewCodec.decode(n.capture().encode())
        assertEquals(1, decoded.scene.objectCount)
        assertEquals(BrushKind.MONOLINE, decoded.scene.objects().single().brush.kind)
    }

    @Test fun eraserOnlyAffectsCurrentPage() {
        val n = note()
        val first = n.currentPageId
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        assertTrue(n.edit(PreviewEdit.InsertPage).accepted)
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        assertTrue(n.edit(PreviewEdit.EraseStrokes(path(150.0 to 90.0, 150.0 to 110.0), 6.0)).accepted)
        // Only the second page's stroke was erased; the first page's identical stroke remains.
        assertEquals(1, n.snapshot().scene.objectCount)
        assertTrue(n.edit(PreviewEdit.SelectPage(first.value)).accepted)
        assertEquals(1, n.strokeCount)
        assertEquals(100.0, n.stroke(0).samples.y(0))
    }
}
