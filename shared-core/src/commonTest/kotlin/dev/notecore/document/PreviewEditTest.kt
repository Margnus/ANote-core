package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PreviewEditTest {
    private fun note(budget: DocumentBudget = DocumentBudget(), guard: () -> Unit = {}): PagedPreviewNotebook =
        PagedPreviewNotebook(DocumentRoot.empty(SceneId("edit"), "before",
            PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)), "edit-session", budget, guard)
    private fun accepted(note: PagedPreviewNotebook, request: PreviewEdit): PreviewEditResult = note.edit(request).also {
        assertTrue(it.accepted); assertNull(it.failure); assertNull(note.lastFailure); assertEquals(note.changeVersion, it.rootRevision)
    }
    @Test fun allEditRequestsUseOriginalRootAndSelectionHistory() {
        val n = note(); val first = n.currentPageId
        accepted(n, PreviewEdit.Rename("合法😀")); accepted(n, PreviewEdit.SetPaper(400.0, 600.0, PaperKind.GRID))
        accepted(n, PreviewEdit.SelectPen(-1, 4.0)); assertTrue(n.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(n.endStroke(2.0, 3.0, .5f, 2))
        val samples = n.stroke(0).samples
        accepted(n, PreviewEdit.InsertPage); val second = n.currentPageId
        accepted(n, PreviewEdit.SelectPage(first.value)); assertSame(samples, n.stroke(0).samples)
        accepted(n, PreviewEdit.CopyPage); val copy = n.currentPageId; assertSame(samples, n.stroke(0).samples)
        accepted(n, PreviewEdit.MovePage(null)); assertEquals(copy, n.page(n.pageCount - 1).id)
        accepted(n, PreviewEdit.DeletePage); assertNull(n.snapshot().findPage(copy))
        accepted(n, PreviewEdit.Undo); assertNotNull(n.snapshot().findPage(copy))
        accepted(n, PreviewEdit.Redo); assertNull(n.snapshot().findPage(copy))
        assertNotNull(n.snapshot().findPage(second)); assertEquals("合法😀", PagedPreviewCodec.decode(n.capture().encode()).title)
    }
    @Test fun invalidRawParametersKeepActiveSamplesRootAndOperationIdentity() {
        val n = note(); val original = n.snapshot(); val status = n.authorityStatus()
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1))
        val invalid = listOf(PreviewEdit.Rename("\uD800"), PreviewEdit.SetPaper(Double.NaN, 500.0, PaperKind.GRID),
            PreviewEdit.SetPaper(400.0, Double.POSITIVE_INFINITY, PaperKind.GRID), PreviewEdit.SetPaper(0.0, 500.0, PaperKind.GRID),
            PreviewEdit.SelectPen(-1, Double.NaN), PreviewEdit.SelectPage(""), PreviewEdit.SelectPage("x".repeat(129)), PreviewEdit.MovePage("\uD800"))
        for (request in invalid) {
            val result = n.edit(request); assertFalse(result.accepted); assertEquals(PreviewFailureKind.INVALID_ARGUMENT, result.failure?.kind)
            assertSame(original, n.snapshot()); assertEquals(status, n.authorityStatus()); assertTrue(n.hasActiveStroke)
        }
        assertTrue(n.endStroke(3.0, 4.0, .6f, 2)); assertEquals(2, n.pointCount(0))
        // Only the original DOWN reserved identities; invalid metadata cannot consume another edit id.
        assertEquals(CommandId("edit-session-op-1"), n.authorityStatus().nextUndoOperationId)
    }
    @Test fun rejectedDocumentEditPreservesActiveInputAndExposesExactCapacity() {
        val n = note(DocumentBudget(maxPages = 1)); val before = n.snapshot()
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1))
        val result = n.edit(PreviewEdit.InsertPage)
        assertFalse(result.accepted); assertEquals(PreviewFailureKind.DOCUMENT_REJECTED, result.failure?.kind)
        assertEquals(DocumentRejection.PAGE_CAPACITY, result.failure?.documentReason)
        assertSame(before, n.snapshot()); assertTrue(n.hasActiveStroke)
        assertTrue(n.endStroke(3.0, 4.0, .6f, 2)); assertEquals(2, n.pointCount(0))
    }
    @Test fun emptyHistoryAndMissingPagesAreSpecificAndDoNotCancelInk() {
        val n = note(); assertTrue(n.beginStroke(1.0, 2.0, .5f, 1))
        for (request in listOf(PreviewEdit.Undo, PreviewEdit.Redo)) {
            assertEquals(PreviewFailureKind.HISTORY_EMPTY, n.edit(request).failure?.kind); assertTrue(n.hasActiveStroke)
        }
        assertEquals(PreviewFailureKind.MISSING_PAGE, n.edit(PreviewEdit.SelectPage("absent")).failure?.kind)
        assertTrue(n.hasActiveStroke)
        val rejected = n.edit(PreviewEdit.MovePage("absent"))
        assertEquals(DocumentRejection.MISSING_PAGE, rejected.failure?.documentReason); assertTrue(n.hasActiveStroke)
        accepted(n, PreviewEdit.Rename("before")); assertTrue(n.hasActiveStroke) // no-op does not cancel
        accepted(n, PreviewEdit.Rename("after")); assertFalse(n.hasActiveStroke) // valid edit explicitly cancels
    }
    @Test fun pendingCompletedCommandRemainsOwnedAcrossRejectedEdits() {
        val n = note(DocumentBudget(journalEntries = 1)); accepted(n, PreviewEdit.Rename("filled"))
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 1)); assertFalse(n.endStroke(3.0, 4.0, .6f, 2))
        val pending = n.pendingStroke(); val before = n.snapshot()
        for (request in listOf(PreviewEdit.Rename("later"), PreviewEdit.SetPaper(400.0, 600.0, PaperKind.DOTS),
                PreviewEdit.InsertPage, PreviewEdit.CopyPage, PreviewEdit.DeletePage, PreviewEdit.MovePage(null), PreviewEdit.Undo, PreviewEdit.Redo)) {
            assertEquals(PreviewFailureKind.PENDING_STROKE, n.edit(request).failure?.kind)
            assertSame(pending, n.pendingStroke()); assertSame(before, n.snapshot())
        }
        assertFalse(n.retire()); assertFalse(n.retryPendingStroke()); assertSame(pending, n.pendingStroke())
    }
    @Test fun closedSessionRejectsAllRequestsBeforeInvalidPayloadValidation() {
        val n = note(); assertTrue(n.retire()); val before = n.snapshot()
        for (request in listOf(PreviewEdit.Rename("\uD800"), PreviewEdit.SetPaper(Double.NaN, 1.0, PaperKind.BLANK),
            PreviewEdit.SelectPen(-1, Double.NaN), PreviewEdit.SelectPage(""), PreviewEdit.MovePage(""), PreviewEdit.InsertPage,
            PreviewEdit.CopyPage, PreviewEdit.DeletePage, PreviewEdit.Undo, PreviewEdit.Redo)) {
            assertEquals(PreviewFailureKind.CLOSED, n.edit(request).failure?.kind); assertSame(before, n.snapshot())
        }
    }
    @Test fun ownerViolationIsNotHiddenAsRecoverableParameterFailure() {
        var allowed = true; val n = note { check(allowed) { "foreign owner" } }; val before = n.snapshot()
        allowed = false
        assertFailsWith<IllegalStateException> { n.edit(PreviewEdit.Rename("after")) }
        allowed = true; assertSame(before, n.snapshot()); accepted(n, PreviewEdit.Rename("after"))
    }
}
