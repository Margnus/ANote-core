package dev.notecore.library

import kotlin.test.*

class LibraryOperationRulesTest {
    private val query = LibrarySearchQuery("search", 7, "local", 15, "notes")

    @Test fun nullAndInvalidOperationsPreserveFailurePrecedence() {
        val invalid = listOf(query.copy(operationId = " "), query.copy(operationId = "x".repeat(129)),
            query.copy(operationId = "\uD800"), query.copy(queryVersion = -1))
        assertEquals(LibraryOperationFailure.CANCELLED,
            LibraryOperationRules.deliveryFailure(invalid.first(), null))
        for (bad in invalid) {
            assertTrue(LibraryOperationRules.invalid(bad))
            assertEquals(LibraryOperationFailure.INVALID_OPERATION,
                LibraryOperationRules.deliveryFailure(bad, query))
            assertEquals(LibraryOperationFailure.INVALID_OPERATION,
                LibraryOperationRules.deliveryFailure(query, bad))
        }
        assertFalse(LibraryOperationRules.invalid(query))
    }

    @Test fun versionMismatchPrecedesOperationCancellationAndOtherChanges() {
        val changed = query.copy(queryVersion = 8, operationId = "other")
        assertEquals(LibraryOperationFailure.STALE_QUERY,
            LibraryOperationRules.deliveryFailure(query, changed))
        assertEquals(LibraryOperationFailure.CANCELLED,
            LibraryOperationRules.deliveryFailure(query, query.copy(operationId = "other", input = "changed")))
        assertNull(LibraryOperationRules.deliveryFailure(query, query))
    }

    @Test fun sameIdAndVersionStillRequireExactRequestScope() {
        for (active in listOf(query.copy(input = "changed"), query.copy(spaceId = "other"),
                              query.copy(expectedRevision = 16))) {
            assertEquals(LibraryOperationFailure.STALE_QUERY,
                LibraryOperationRules.deliveryFailure(query, active))
        }
        assertEquals(LibrarySearchQuery("search", 7, "local", 15, "notes"), query)
    }

    @Test fun cancellationRequiresBothMatchingOperationIdAndVersion() {
        assertSame(query, LibraryOperationRules.cancel(query, "other", 7))
        assertSame(query, LibraryOperationRules.cancel(query, "search", 8))
        assertSame(query, LibrarySearchRules.cancelQuery(query, "other", 7))
        assertNull(LibraryOperationRules.cancel(query, "search", 7))
        assertNull(LibrarySearchRules.cancelQuery(query, "search", 7))
    }

    @Test fun pageWithoutPayloadKeepsOriginalCancellationAndFailureBehavior() {
        val failed = LibraryPageResult(failure = LibraryPageFailure.INVALID_QUERY)
        assertEquals(LibraryPageFailure.CANCELLED, LibraryPageRules.deliver(failed, null).failure)
        assertSame(failed, LibraryPageRules.deliver(failed, LibraryPageQuery(query, LibrarySort.NAME, false)))
    }
}
