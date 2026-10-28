package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PageBookmarkRulesTest {
    private val a = PageDescriptor(PageId("a"), LayerId("la"), 595.0, 842.0)
    private val b = PageDescriptor(PageId("b"), LayerId("lb"), 595.0, 842.0)
    private val root = DocumentRoot.compose("bookmarks", SceneSnapshot.empty(SceneId("doc")), listOf(a, b))
    private val bookmarks = PageBookmarkSnapshot(root.id, 3, true, listOf(b.id))

    @Test fun markUnmarkAndNoOpPlansPreserveSourceAndBindBothRevisions() {
        val patch = assertNotNull(PageBookmarkRules.plan(root, bookmarks, 3, a.id, true).patch)
        assertEquals(root.id, patch.documentId); assertEquals(0, patch.expectedDocumentRevision)
        assertEquals(3, patch.expectedMetadataRevision); assertFalse(patch.beforeMarked); assertTrue(patch.afterMarked)
        val changed = bookmarks.copy(revision = 4, pages = bookmarks.pages + a.id)
        val noop = assertNotNull(PageBookmarkRules.plan(root, changed, 4, a.id, true).patch)
        assertTrue(noop.beforeMarked); assertTrue(noop.afterMarked)
        val remove = assertNotNull(PageBookmarkRules.plan(root, changed, 4, a.id, false).patch)
        assertTrue(remove.beforeMarked); assertFalse(remove.afterMarked)
        assertEquals(listOf(b.id), bookmarks.pages); assertEquals(0, root.revision)
    }

    @Test fun navigationAfterReorderUsesStablePageIdThroughOriginalSession() {
        val owner = MemoryDocumentAuthority(root)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.MovePage(CommandId("move"), root.id, 0, b.id, a.id)))
        val reordered = owner.snapshot()
        assertEquals(b.id, reordered.page(0).id)
        val navigation = assertNotNull(PageBookmarkRules.navigate(reordered, bookmarks, 3, b.id).request)
        val session = PagedPreviewNotebook(reordered, "bookmark-session")
        assertTrue(session.selectPage(a.id))
        val before = PagedPreviewCodec.encode(session.snapshot())
        assertTrue(session.edit(navigation).accepted)
        assertEquals(b.id, session.currentPageId); assertEquals(0, session.currentPageIndex)
        assertEquals(before, PagedPreviewCodec.encode(session.snapshot()))
    }

    @Test fun deletedBookmarkIsExplicitlyMissingAndMayBeUnmarkedOrRestored() {
        val owner = MemoryDocumentAuthority(root)
        val deletion = DocumentCommand.DeletePage(CommandId("delete"), root.id, 0, b.id)
        assertIs<DocumentCommit.Accepted>(owner.commit(deletion))
        val deleted = owner.snapshot()
        val navigation = PageBookmarkRules.navigate(deleted, bookmarks, 3, b.id)
        assertNull(navigation.request); assertEquals(PageBookmarkFailure.MISSING_PAGE, navigation.failure)
        assertEquals(PageBookmarkFailure.MISSING_PAGE, PageBookmarkRules.plan(deleted, bookmarks, 3, b.id, true).failure)
        assertFalse(assertNotNull(PageBookmarkRules.plan(deleted, bookmarks, 3, b.id, false).patch).afterMarked)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Undo(CommandId("undo"), root.id, deleted.revision, deletion.id)))
        assertEquals(PreviewEdit.SelectPage("b"), PageBookmarkRules.navigate(owner.snapshot(), bookmarks, 3, b.id).request)
    }

    @Test fun pageDeletedAfterPlanningIsRejectedByOriginalSelectPage() {
        val request = assertNotNull(PageBookmarkRules.navigate(root, bookmarks, 3, b.id).request)
        val session = PagedPreviewNotebook(root, "late-navigation")
        assertTrue(session.selectPage(b.id)); assertTrue(session.deleteCurrentPage())
        assertTrue(session.beginStroke(10.0, 20.0, .5f, 1))
        val reserved = session.inputReservedByteCharge
        val before = PagedPreviewCodec.encode(session.snapshot())
        val selected = session.currentPageId
        assertFalse(session.edit(request).accepted)
        assertEquals(selected, session.currentPageId)
        assertEquals(before, PagedPreviewCodec.encode(session.snapshot()))
        assertTrue(session.hasActiveStroke); assertEquals(reserved, session.inputReservedByteCharge)
        assertTrue(session.endStroke(11.0, 21.0, .6f, 2))
        assertEquals(1, session.totalStrokeCount)
    }

    @Test fun invalidMetadataNeverProducesPatchOrNavigation() {
        val malformed = PageId("\uD800")
        for ((snapshot, reason) in listOf(
            bookmarks.copy(documentId = SceneId("other")) to PageBookmarkFailure.WRONG_DOCUMENT,
            bookmarks.copy(revision = 2) to PageBookmarkFailure.STALE_METADATA,
            bookmarks.copy(complete = false) to PageBookmarkFailure.INCOMPLETE_SNAPSHOT,
            bookmarks.copy(pages = listOf(b.id, b.id)) to PageBookmarkFailure.INVALID_BOOKMARKS,
            bookmarks.copy(pages = listOf(malformed)) to PageBookmarkFailure.INVALID_BOOKMARKS,
            bookmarks.copy(pages = List(PageBookmarkRules.MAX_BOOKMARKS + 1) { PageId("p$it") }) to PageBookmarkFailure.CAPACITY)) {
            val plan = PageBookmarkRules.plan(root, snapshot, 3, a.id, true)
            assertNull(plan.patch); assertEquals(reason, plan.failure)
            val navigation = PageBookmarkRules.navigate(root, snapshot, 3, b.id)
            assertNull(navigation.request); assertEquals(reason, navigation.failure)
        }
        assertEquals(PageBookmarkFailure.INVALID_BOOKMARKS, PageBookmarkRules.plan(root, bookmarks, 3, malformed, false).failure)
        assertEquals(PageBookmarkFailure.NOT_BOOKMARKED, PageBookmarkRules.navigate(root, bookmarks, 3, a.id).failure)
    }

    @Test fun fullBookmarkCatalogAllowsCleanupButRefusesAdditionalMarks() {
        val full = bookmarks.copy(pages = List(PageBookmarkRules.MAX_BOOKMARKS) { PageId("old$it") })
        assertEquals(PageBookmarkFailure.CAPACITY, PageBookmarkRules.plan(root, full, 3, a.id, true).failure)
        assertNotNull(PageBookmarkRules.plan(root, full, 3, full.pages.first(), false).patch)
        assertEquals(PageBookmarkFailure.MISSING_PAGE, PageBookmarkRules.navigate(root, full, 3, full.pages.first()).failure)
    }
}
