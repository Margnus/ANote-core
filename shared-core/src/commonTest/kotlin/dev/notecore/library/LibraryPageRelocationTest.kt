package dev.notecore.library

import kotlin.test.*

class LibraryPageRelocationTest {
    private val notes = listOf(
        LibraryViewNote("n0", "😀", 0, null, false),
        LibraryViewNote("n1", "中", Long.MAX_VALUE, 0, true),
        LibraryViewNote("n2", "中", 5, Long.MAX_VALUE, true),
        LibraryViewNote("n3", "A", 5, 2, false),
        LibraryViewNote("n4", "\uE000", 5, 2, true),
        LibraryViewNote("n5", "B", 5, 2, true),
    )
    private val catalog = LibraryViewSnapshot("local", 15, true, notes,
        LibraryViewPreference(LibrarySort.NAME, LibraryLayout.LIST))

    private fun query(sort: LibrarySort, favorites: Boolean = false, version: Long = 8) = LibraryPageQuery(
        LibrarySearchQuery("paging", version, "local", 15, ""), sort, favorites)

    @Test fun relocationStartsAtStableAnchorAndContinuesThroughNextCursor() {
        val q = query(LibrarySort.NAME)
        val relocated = assertNotNull(LibraryPageRules.relocate(catalog, q, "n2", null, 2).page)
        assertEquals(listOf("n2", "n0"), relocated.rows.map { it.noteId })
        assertEquals("n2", relocated.startAnchor?.anchor?.noteId)
        assertNull(relocated.cursor)
        assertEquals("n0", relocated.nextCursor?.anchor?.noteId)
        val continued = assertNotNull(LibraryPageRules.next(catalog, q, relocated.nextCursor!!, 2).page)
        assertEquals(listOf("n4"), continued.rows.map { it.noteId })
        assertNull(continued.nextCursor)
        assertEquals(0, relocated.rows.indexOf(relocated.startAnchor!!.anchor))
        assertEquals(LibrarySort.NAME, catalog.preference.sort)
    }

    @Test fun missingAnchorFallsBackToExplicitIndexTailOrHeadWithoutForgedIdentity() {
        val q = query(LibrarySort.NAME)
        val byIndex = assertNotNull(LibraryPageRules.relocate(catalog, q, "gone", 2, 2).page)
        assertEquals(listOf("n1", "n2"), byIndex.rows.map { it.noteId })
        assertEquals("n1", byIndex.startAnchor?.anchor?.noteId)
        val head = assertNotNull(LibraryPageRules.relocate(catalog, q, "gone", null, 2).page)
        assertEquals(listOf("n3", "n5"), head.rows.map { it.noteId })
        assertEquals("n3", head.startAnchor?.anchor?.noteId)
        val tail = assertNotNull(LibraryPageRules.relocate(catalog, q, "gone", 99, 2).page)
        assertEquals(listOf("n4"), tail.rows.map { it.noteId })
        assertNull(tail.nextCursor)
        assertEquals(LibraryPageFailure.INVALID_QUERY,
            LibraryPageRules.relocate(catalog, q, "gone", -1, 2).failure)
        for (bad in listOf("", " ", "\uD800", "x".repeat(129))) {
            assertEquals(LibraryPageFailure.INVALID_CURSOR,
                LibraryPageRules.relocate(catalog, q, bad, 0, 2).failure)
        }
        val empty = assertNotNull(LibraryPageRules.relocate(
            catalog.copy(notes = emptyList()), q, "gone", null, 2).page)
        assertTrue(empty.rows.isEmpty()); assertNull(empty.nextCursor); assertNull(empty.startAnchor)
    }

    @Test fun relocationBindsCurrentRevisionQuerySortAndEligibilityFilters() {
        val updated = query(LibrarySort.UPDATED)
        val page = assertNotNull(LibraryPageRules.relocate(catalog, updated, "n1", null, 2).page)
        assertEquals(listOf("n1", "n2"), page.rows.map { it.noteId })
        assertEquals(LibrarySort.NAME, catalog.preference.sort)
        assertEquals(LibraryViewRejection.STALE_REVISION,
            LibraryPageRules.relocate(catalog.copy(revision = 16), updated, "n1", null, 2).catalogFailure)
        val favorite = query(LibrarySort.NAME, favorites = true)
        val ineligible = LibraryPageRules.relocate(catalog, favorite, "n3", 0, 2)
        val fallback = assertNotNull(ineligible.page)
        assertEquals(listOf("n5", "n1"), fallback.rows.map { it.noteId })
        assertEquals("n5", fallback.startAnchor?.anchor?.noteId)
        val recent = query(LibrarySort.RECENT)
        val noOpen = LibraryPageRules.relocate(catalog, recent, "n0", null, 2)
        assertEquals(listOf("n2", "n3"), assertNotNull(noOpen.page).rows.map { it.noteId })
    }

    @Test fun deliveredRelocationRecomputesIdempotentlyAndRejectsTampering() {
        val q = query(LibrarySort.NAME)
        val candidate = LibraryPageRules.relocate(catalog, q, "n2", null, 2)
        assertEquals(candidate, LibraryPageRules.deliver(candidate, q.operation))
        assertEquals(candidate, LibraryPageRules.deliver(candidate, q.operation))
        val page = assertNotNull(candidate.page)
        assertEquals(LibraryPageFailure.STALE_QUERY,
            LibraryPageRules.deliver(candidate, q.operation.copy(queryVersion = 9)).failure)
        assertEquals(LibraryPageFailure.CANCELLED,
            LibraryPageRules.deliver(candidate, q.operation.copy(operationId = "other")).failure)
        assertNull(LibrarySearchRules.cancelQuery(q.operation, "paging", 8))
        assertEquals(LibraryPageFailure.CANCELLED, LibraryPageRules.deliver(candidate, null).failure)
        for (tampered in listOf(
            candidate.copy(page = page.copy(rows = page.rows.drop(1))),
            candidate.copy(page = page.copy(startAnchor = page.startAnchor!!.copy(
                anchor = page.startAnchor.anchor.copy(title = "forged")))),
            candidate.copy(page = page.copy(snapshot = page.snapshot.copy(notes = page.snapshot.notes.map {
                if (it.noteId == "n2") it.copy(title = "replacement") else it
            }))))) {
            assertEquals(LibraryPageFailure.STALE_RESULT, LibraryPageRules.deliver(tampered, q.operation).failure)
            assertNull(LibraryPageRules.deliver(tampered, q.operation).page)
        }
        assertEquals(candidate, LibraryPageRules.deliver(candidate, q.operation))
    }

    @Test fun relocationContinuesAcrossNewerValidRevisionWithFreshQuery() {
        val stale = assertNotNull(LibraryPageRules.first(catalog, query(LibrarySort.NAME, version = 7), 2).page)
        val mutated = catalog.copy(revision = 16, notes = notes.filterNot { it.noteId == "n1" })
        assertEquals(LibraryViewRejection.STALE_REVISION,
            LibraryPageRules.next(mutated, query(LibrarySort.NAME, version = 7), stale.nextCursor!!, 2).catalogFailure)
        val freshQuery = query(LibrarySort.NAME, version = 8).let {
            it.copy(operation = it.operation.copy(expectedRevision = 16))
        }
        val relocated = assertNotNull(LibraryPageRules.relocate(mutated, freshQuery, "n2", null, 2).page)
        assertEquals(listOf("n2", "n0"), relocated.rows.map { it.noteId })
        assertEquals(16, relocated.query.operation.expectedRevision)
        assertEquals(relocated, LibraryPageRules.deliver(LibraryPageResult(relocated), freshQuery.operation).page)
        val continued = assertNotNull(LibraryPageRules.next(mutated, freshQuery, relocated.nextCursor!!, 2).page)
        assertEquals(listOf("n4"), continued.rows.map { it.noteId })
        assertEquals(5, mutated.notes.size)
    }
}
