package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** Inspect actual retained references rather than depending on nondeterministic GC/heap timing. */
class PreviewProjectionRetentionTest {
    private fun note() = PagedPreviewNotebook.create("projection", "title", "projection-session")
    private fun draw(n: PagedPreviewNotebook) {
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1))
        assertTrue(n.endStroke(3.0, 4.0, .5f, 2))
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
}
