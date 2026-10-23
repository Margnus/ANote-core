package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** Inspect actual retained references rather than depending on nondeterministic GC/heap timing. */
class PreviewProjectionRetentionTest {
    private fun note() = PagedPreviewNotebook.create("projection", "title", "projection-session")
    private fun draw(n: PagedPreviewNotebook) {
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1))
        assertTrue(n.endStroke(3.0, 4.0, .5f, 2))
        assertEquals(1, n.strokeCount); assertEquals(1, cached(n).size)
    }
    private fun cached(n: PagedPreviewNotebook): List<*> =
        PagedPreviewNotebook::class.java.getDeclaredField("projection").apply { isAccessible = true }.get(n) as List<*>

    @Test fun deletedPagePayloadLeavesProjectionBeforeAnyLaterDrawingRead() {
        val n = note(); draw(n)
        val oldStroke = n.stroke(0)
        assertSame(oldStroke, cached(n).single())
        assertTrue(n.deleteCurrentPage())
        assertEquals(0, n.snapshot().scene.objectCount)
        assertFalse(cached(n).any { it === oldStroke })
        assertTrue(n.undo())
        assertEquals(oldStroke.samples, n.stroke(0).samples) // History responsibility was preserved.
    }

    @Test fun successfulEditsAndPageChangesDropOldCacheButFailedEditKeepsCurrentProjection() {
        val n = note(); draw(n); n.stroke(0)
        val first = n.currentPageId
        val original = cached(n)
        assertFalse(n.edit(PreviewEdit.SetPaper(Double.NaN, 500.0, PaperKind.GRID)).accepted)
        assertSame(original, cached(n)); assertEquals(1, n.strokeCount)
        assertTrue(n.insertPage()); assertTrue(cached(n).isEmpty())
        draw(n); val second = n.stroke(0)
        assertTrue(n.selectPage(first)); assertTrue(cached(n).isEmpty())
        assertNotEquals(second.id, n.stroke(0).id)
        assertTrue(n.rename("renamed")); assertTrue(cached(n).isEmpty())
        assertEquals(1, n.strokeCount)
    }

    @Test fun retiredNotebookRemainsReadableWithoutReacquiringProjectionReferences() {
        val n = note(); draw(n); val source = n.stroke(0)
        assertTrue(n.retire()); assertTrue(cached(n).isEmpty())
        assertEquals(1, n.strokeCount); assertEquals(source.samples, n.stroke(0).samples)
        assertTrue(cached(n).isEmpty())
        assertFalse(n.rename("closed")); assertTrue(cached(n).isEmpty())
    }
    @Test fun committedContentChangeDropsProjectionBeforeAnySubsequentRead() {
        val n = note(); draw(n); val saved = n.capture(); val oldStroke = n.stroke(0)
        assertTrue(n.undo()); assertTrue(cached(n).isEmpty())
        assertEquals(1, saved.root.scene.objectCount) // external accepted snapshot remains valid
        assertSame(oldStroke, saved.root.scene.objects().single())
        assertTrue(n.redo()); assertTrue(cached(n).isEmpty())
        assertEquals(1, n.strokeCount); assertEquals(1, cached(n).size)
        assertTrue(n.deleteCurrentPage()); assertTrue(cached(n).isEmpty())
        assertEquals(0, n.totalStrokeCount)
    }
    @Test fun selectionAndRetirementDropProjectionWhileFailedEditPreservesIt() {
        val n = note(); val first = n.currentPageId; draw(n)
        val cached = cached(n)
        assertFalse(n.setPaper(Double.NaN, 500.0, PaperKind.GRID))
        assertSame(cached, cached(n))
        assertTrue(n.insertPage()); assertTrue(cached(n).isEmpty())
        assertTrue(n.selectPage(first)); assertEquals(1, n.strokeCount)
        val second = n.page(1).id; assertTrue(n.selectPage(second)); assertTrue(cached(n).isEmpty())
        assertTrue(n.selectPage(first)); assertEquals(1, n.strokeCount)
        assertTrue(n.retire()); assertTrue(cached(n).isEmpty())
        assertFalse(n.rename("late")); assertTrue(cached(n).isEmpty())
    }
}
