package dev.notecore.document

import kotlin.test.*

class DocumentBudgetCreationTest {
    private fun refused(result: DocumentBudgetCreation, reason: DocumentBudgetFailure) {
        assertNull(result.budget); assertEquals(reason, result.failure)
    }
    @Test fun invalidCountsReturnFieldFailureWithoutConstructingBudget() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, 4097, Int.MAX_VALUE))
            refused(DocumentBudget.validated(maxPages = value), DocumentBudgetFailure.MAX_PAGES)
        for (value in listOf(Int.MIN_VALUE, -1, 0, 100_001, Int.MAX_VALUE))
            refused(DocumentBudget.validated(maxObjects = value), DocumentBudgetFailure.MAX_OBJECTS)
        for (value in listOf(Int.MIN_VALUE, -1, 0, 16_385, Int.MAX_VALUE)) {
            refused(DocumentBudget.validated(journalEntries = value), DocumentBudgetFailure.JOURNAL_ENTRIES)
            refused(DocumentBudget.validated(historyEntries = value), DocumentBudgetFailure.HISTORY_ENTRIES)
        }
        refused(DocumentBudget.validated(maxPages = 0, inputBytes = -1), DocumentBudgetFailure.MAX_PAGES)
    }
    @Test fun negativeBytesReturnFieldFailureAndTrustedConstructorStillRejects() {
        for (value in listOf(Long.MIN_VALUE, -1L)) {
            refused(DocumentBudget.validated(liveBytes = value), DocumentBudgetFailure.LIVE_BYTES)
            refused(DocumentBudget.validated(journalBytes = value), DocumentBudgetFailure.JOURNAL_BYTES)
            refused(DocumentBudget.validated(identityBytes = value), DocumentBudgetFailure.IDENTITY_BYTES)
            refused(DocumentBudget.validated(historyBytes = value), DocumentBudgetFailure.HISTORY_BYTES)
            refused(DocumentBudget.validated(inputBytes = value), DocumentBudgetFailure.INPUT_BYTES)
        }
        assertFailsWith<IllegalArgumentException> { DocumentBudget(maxPages = 0) }
        assertFailsWith<IllegalArgumentException> { DocumentBudget(historyEntries = 16_385) }
        assertFailsWith<IllegalArgumentException> { DocumentBudget(inputBytes = -1) }
    }
    @Test fun validValuesKeepExactPolicyWithoutClampingOrReservingContent() {
        val defaults = DocumentBudget.validated()
        assertNull(defaults.failure); assertEquals(DocumentBudget(), defaults.budget)
        for (bytes in listOf(0L, Long.MAX_VALUE)) {
            val result = DocumentBudget.validated(4096, 100_000, bytes, 16_384, bytes, bytes, 16_384, bytes, bytes)
            assertNull(result.failure)
            assertEquals(DocumentBudget(4096, 100_000, bytes, 16_384, bytes, bytes, 16_384, bytes, bytes), result.budget)
        }
        assertNotNull(DocumentBudget.validated(maxPages = 1, maxObjects = 1, journalEntries = 1, historyEntries = 1).budget)
    }
    @Test fun validGeneralPolicyDoesNotBypassPreviewSupportCapacityOrOwner() {
        val unsupported = assertNotNull(DocumentBudget.validated(maxPages = 129).budget)
        val refused = PagedPreviewNotebook.createValidated("doc", "title", "session", budget = unsupported, ownerCheck = {})
        assertNull(refused.notebook); assertEquals(PreviewOpenFailure.UNSUPPORTED_BUDGET, refused.failure)
        val empty = assertNotNull(DocumentBudget.validated(liveBytes = 0).budget)
        val capacity = PagedPreviewNotebook.createValidated("doc", "title", "session", budget = empty, ownerCheck = {})
        assertNull(capacity.notebook); assertEquals(DocumentRejection.LIVE_BYTE_CAPACITY, capacity.documentReason)
        val policy = assertNotNull(DocumentBudget.validated(journalEntries = 4096, journalBytes = 32L * 1024 * 1024,
            identityBytes = 4L * 1024 * 1024).budget)
        var owned = true
        val note = assertNotNull(PagedPreviewNotebook.createValidated("doc", "title", "session", budget = policy,
            ownerCheck = { check(owned) }).notebook)
        assertTrue(note.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(note.endStroke(3.0, 4.0, .5f, 2))
        assertEquals(1, note.totalStrokeCount)
        owned = false
        assertFailsWith<IllegalStateException> { note.snapshot() }
    }
}
