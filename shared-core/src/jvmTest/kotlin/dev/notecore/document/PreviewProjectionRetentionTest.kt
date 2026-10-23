package dev.notecore.document

import kotlin.test.*

/** Inspect the actual cache without depending on GC timing or adding production test hooks. */
class PreviewProjectionRetentionTest {
    private fun cache(n: PagedPreviewNotebook): List<*> {
        val field = n.javaClass.getDeclaredField("projection"); field.isAccessible = true
        return field.get(n) as List<*>
    }
    private fun note() = PagedPreviewNotebook.create("retention", "title", "session")
    private fun draw(n: PagedPreviewNotebook) {
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1))
        assertTrue(n.endStroke(2.0, 3.0, .5f, 2))
        assertEquals(1, n.strokeCount); assertEquals(1, cache(n).size)
    }
    @Test fun committedContentChangeDropsProjectionBeforeAnySubsequentRead() {
        val n = note(); draw(n); val saved = n.capture(); val oldStroke = n.stroke(0)
        assertTrue(n.undo()); assertTrue(cache(n).isEmpty())
        assertEquals(1, saved.root.scene.objectCount) // external accepted snapshot remains valid
        assertSame(oldStroke, saved.root.scene.objects().single())
        assertTrue(n.redo()); assertTrue(cache(n).isEmpty())
        assertEquals(1, n.strokeCount); assertEquals(1, cache(n).size)
        assertTrue(n.deleteCurrentPage()); assertTrue(cache(n).isEmpty())
        assertEquals(0, n.totalStrokeCount)
    }
    @Test fun selectionAndRetirementDropProjectionWhileFailedEditPreservesIt() {
        val n = note(); val first = n.currentPageId; draw(n)
        val cached = cache(n)
        assertFalse(n.setPaper(Double.NaN, 500.0, PaperKind.GRID))
        assertSame(cached, cache(n))
        assertTrue(n.insertPage()); assertTrue(cache(n).isEmpty())
        assertTrue(n.selectPage(first)); assertEquals(1, n.strokeCount)
        val second = n.page(1).id; assertTrue(n.selectPage(second)); assertTrue(cache(n).isEmpty())
        assertTrue(n.selectPage(first)); assertEquals(1, n.strokeCount)
        assertTrue(n.retire()); assertTrue(cache(n).isEmpty())
        assertFalse(n.rename("late")); assertTrue(cache(n).isEmpty())
    }
}
