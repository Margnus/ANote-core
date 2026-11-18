package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** DEV-042 E4b: lasso select/transform/delete commands through the preview notebook facade. */
class LassoNotebookTest {
    private fun note(): PagedPreviewNotebook = PagedPreviewNotebook(
        DocumentRoot.empty(SceneId("lasso"), "lasso",
            PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)), "lasso-session", DocumentBudget())

    private var clock = 0L
    private fun stroke(n: PagedPreviewNotebook, x1: Double, y1: Double, x2: Double, y2: Double) {
        assertTrue(n.beginStroke(x1, y1, .5f, ++clock))
        assertTrue(n.endStroke(x2, y2, .5f, ++clock))
    }

    private fun path(vararg points: Pair<Double, Double>) = points.map { PointD(it.first, it.second) }
    private val square = path(50.0 to 50.0, 300.0 to 50.0, 300.0 to 300.0, 50.0 to 300.0)

    @Test fun lassoSelectIsPresentationOnlyWithoutHistory() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        stroke(n, 100.0, 400.0, 200.0, 400.0)
        val before = n.changeVersion
        val result = n.edit(PreviewEdit.LassoSelect(square))
        assertTrue(result.accepted); assertNull(result.failure)
        assertEquals(before, n.changeVersion)
        assertEquals(1, n.selectedStrokeIds.size)
        // Only the original two strokes are in history; selection added none.
        assertTrue(n.edit(PreviewEdit.Undo).accepted)
        assertEquals(1, n.snapshot().scene.objectCount)
    }

    @Test fun transformSelectionMovesWholeSelectionWithSingleUndo() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        stroke(n, 100.0, 200.0, 200.0, 200.0)
        assertTrue(n.edit(PreviewEdit.LassoSelect(square)).accepted)
        assertEquals(2, n.selectedStrokeIds.size)
        assertTrue(n.edit(PreviewEdit.TransformSelection(10.0, 20.0)).accepted)
        assertEquals(2, n.selectedStrokeIds.size) // selection survives for further moves
        for (i in 0 until n.strokeCount) {
            assertEquals(110.0, n.stroke(i).samples.x(0) + n.stroke(i).transform.tx, 1e-9)
        }
        assertTrue(n.edit(PreviewEdit.Undo).accepted)
        assertEquals(100.0, n.stroke(0).samples.x(0) + n.stroke(0).transform.tx, 1e-9)
        assertTrue(n.edit(PreviewEdit.Redo).accepted)
        assertEquals(110.0, n.stroke(0).samples.x(0) + n.stroke(0).transform.tx, 1e-9)
    }

    @Test fun transformSelectionScalesAroundAnchorThenTranslates() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        assertTrue(n.edit(PreviewEdit.LassoSelect(square)).accepted)
        assertTrue(n.edit(PreviewEdit.TransformSelection(10.0, 0.0, 2.0, PointD(100.0, 100.0))).accepted)
        // p' = 2*(p-anchor)+anchor+(10,0): first sample (100,100) -> (110,100).
        assertEquals(110.0, n.stroke(0).transform.map(PointD(100.0, 100.0)).x, 1e-9)
        assertEquals(100.0, n.stroke(0).transform.map(PointD(100.0, 100.0)).y, 1e-9)
        assertEquals(310.0, n.stroke(0).transform.map(PointD(200.0, 100.0)).x, 1e-9)
    }

    @Test fun deleteSelectionRemovesAllAndSingleUndoRestores() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        stroke(n, 100.0, 200.0, 200.0, 200.0)
        assertTrue(n.edit(PreviewEdit.LassoSelect(square)).accepted)
        assertTrue(n.edit(PreviewEdit.DeleteSelection).accepted)
        assertEquals(0, n.snapshot().scene.objectCount)
        assertEquals(0, n.selectedStrokeIds.size)
        assertTrue(n.edit(PreviewEdit.Undo).accepted)
        assertEquals(2, n.snapshot().scene.objectCount)
    }

    @Test fun emptySelectionOperationsFailWithoutContentChange() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        val before = n.snapshot()
        for (request in listOf(PreviewEdit.TransformSelection(1.0, 1.0), PreviewEdit.DeleteSelection)) {
            val result = n.edit(request)
            assertFalse(result.accepted)
            assertEquals(PreviewFailureKind.EMPTY_SELECTION, result.failure?.kind)
        }
        assertSame(before, n.snapshot())
    }

    @Test fun noHitClearsPreviousSelection() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        assertTrue(n.edit(PreviewEdit.LassoSelect(square)).accepted)
        assertEquals(1, n.selectedStrokeIds.size)
        assertTrue(n.edit(PreviewEdit.LassoSelect(path(500.0 to 500.0, 600.0 to 500.0, 600.0 to 600.0))).accepted)
        assertEquals(0, n.selectedStrokeIds.size)
        assertEquals(PreviewFailureKind.EMPTY_SELECTION, n.edit(PreviewEdit.DeleteSelection).failure?.kind)
    }

    @Test fun selectionInvalidatedByPageSwitchAndHistoryTraversal() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        assertTrue(n.edit(PreviewEdit.LassoSelect(square)).accepted)
        assertTrue(n.edit(PreviewEdit.Undo).accepted)
        assertEquals(0, n.selectedStrokeIds.size)
        assertTrue(n.edit(PreviewEdit.Redo).accepted)
        assertTrue(n.edit(PreviewEdit.LassoSelect(square)).accepted)
        assertTrue(n.edit(PreviewEdit.InsertPage).accepted)
        // Page switch clears the selection; the copied selection cannot leak across pages.
        assertEquals(0, n.selectedStrokeIds.size)
    }

    @Test fun noChangeTransformIsAcceptedWithoutHistory() {
        val n = note()
        stroke(n, 100.0, 100.0, 200.0, 100.0)
        assertTrue(n.edit(PreviewEdit.LassoSelect(square)).accepted)
        val before = n.changeVersion
        assertTrue(n.edit(PreviewEdit.TransformSelection(0.0, 0.0)).accepted)
        assertEquals(before, n.changeVersion)
        assertEquals(1, n.selectedStrokeIds.size)
        assertTrue(n.edit(PreviewEdit.Undo).accepted) // undo targets the stroke, not a no-op transform
        assertEquals(0, n.snapshot().scene.objectCount)
    }
}
