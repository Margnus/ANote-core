package dev.notecore.document

import kotlin.test.*

class PreviewNotebookCreationTest {
    @Test fun invalidRawIdentityTitleAndNonceReturnFailureBeforeCreatingAuthority() {
        var checks = 0
        fun refuse(id: String = "new", title: String = "title", nonce: String = "session", failure: PreviewOpenFailure) {
            val before = checks
            val result = PagedPreviewNotebook.createValidated(id, title, nonce, ownerCheck = { checks++ })
            assertNull(result.notebook); assertEquals(failure, result.failure); assertNull(result.documentReason)
            assertEquals(before + 1, checks)
        }
        for (id in listOf("", " \t", "x".repeat(129), "\uD800")) refuse(id = id, failure = PreviewOpenFailure.INVALID_DOCUMENT_ID)
        for (title in listOf("x".repeat(121), "\uDC00")) refuse(title = title, failure = PreviewOpenFailure.INVALID_TITLE)
        for (nonce in listOf("", "x".repeat(49), "bad nonce", "\uD800")) refuse(nonce = nonce, failure = PreviewOpenFailure.INVALID_SESSION_NONCE)
    }
    @Test fun invalidRawPageCannotReachPageDescriptorPrecondition() {
        for (size in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 14_400.0001)) {
            for (horizontal in listOf(true, false)) {
                val result = PagedPreviewNotebook.createValidated("new", "title", "session",
                    width = if (horizontal) size else 595.0, height = if (horizontal) 842.0 else size, ownerCheck = {})
                assertNull(result.notebook); assertEquals(PreviewOpenFailure.INVALID_PAGE, result.failure)
            }
        }
    }
    @Test fun validBoundaryCreationPreservesUnicodeAndUsesOriginalEditableAuthority() {
        val id = "x".repeat(125) + "笔\uD83D\uDE80"
        val title = "t".repeat(118) + "\uD83D\uDE80"
        val note = assertNotNull(PagedPreviewNotebook.createValidated(id, title, "s".repeat(48), 1.0, 14_400.0, PaperKind.GRID, ownerCheck = {}).notebook)
        assertEquals(id, note.documentId); assertEquals(title, note.title); assertEquals(0, note.totalStrokeCount)
        assertEquals(0L, note.changeVersion); assertEquals(1.0, note.currentPage.width); assertEquals(14_400.0, note.currentPage.height)
        assertEquals(PaperKind.GRID, note.currentPage.paper)
        assertEquals("s".repeat(48) + "-page-0", note.currentPageId.value)
        assertTrue(note.beginStroke(.5, 2.0, .25f, 1)); assertTrue(note.endStroke(.75, 3.0, .75f, 2))
        assertEquals(1L, note.changeVersion); assertEquals(1, note.authorityStatus().undoCount)
        val read = assertNotNull(PreviewDocumentReader.read(note.capture().encode()).root)
        assertEquals(title, read.title); assertEquals(id, read.id.value); assertEquals(note.stroke(0).samples, read.scene.objects().single().samples)
        assertNotNull(PagedPreviewNotebook.createValidated("empty-title", "", "s", ownerCheck = {}).notebook)
    }
    @Test fun capacityIsTypedAndOwnerFailuresNeverBecomeContentRefusal() {
        val unsupported = PagedPreviewNotebook.createValidated("new", "title", "session", budget = DocumentBudget(maxPages = 129), ownerCheck = {})
        assertNull(unsupported.notebook); assertEquals(PreviewOpenFailure.UNSUPPORTED_BUDGET, unsupported.failure)
        val capacity = PagedPreviewNotebook.createValidated("new", "title", "session", budget = DocumentBudget(liveBytes = 0), ownerCheck = {})
        assertNull(capacity.notebook); assertEquals(PreviewOpenFailure.DOCUMENT_CAPACITY, capacity.failure)
        assertEquals(DocumentRejection.LIVE_BYTE_CAPACITY, capacity.documentReason)
        assertFailsWith<IllegalStateException> { PagedPreviewNotebook.createValidated("", "", "", ownerCheck = { error("wrong owner") }) }
        var calls = 0
        assertFailsWith<IllegalArgumentException> {
            PagedPreviewNotebook.createValidated("new", "title", "session", ownerCheck = { if (++calls == 2) throw IllegalArgumentException("owner callback failed") })
        }
        assertNotNull(PagedPreviewNotebook.createValidated("new", "title", "session", ownerCheck = {}).notebook)
    }
}
