package dev.notecore.library

import kotlin.test.*

class LibraryPageRulesTest {
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

    private fun query(sort: LibrarySort, favorites: Boolean = false) = LibraryPageQuery(
        LibrarySearchQuery("paging", 7, "local", 15, ""), sort, favorites)

    private fun collect(query: LibraryPageQuery): Pair<List<String>, Int> {
        val ids = mutableListOf<String>()
        var pages = 0
        var result = LibraryPageRules.first(catalog, query, 2)
        while (true) {
            val page = assertNotNull(result.page)
            assertTrue(page.rows.size <= 2)
            ids += page.rows.map { it.noteId }
            val cursor = page.nextCursor ?: break
            result = LibraryPageRules.next(catalog, query, cursor, 2)
            check(++pages < 50)
        }
        return ids to pages
    }

    @Test fun allSortAndFavoriteCombinationsTraverseWithoutDuplicatesOrOmissions() {
        val expected = mapOf(
            LibrarySort.NAME to listOf("n3", "n5", "n1", "n2", "n0", "n4"),
            LibrarySort.UPDATED to listOf("n1", "n2", "n3", "n4", "n5", "n0"),
            LibrarySort.RECENT to listOf("n2", "n3", "n4", "n5", "n1"),
        )
        for ((sort, ordered) in expected) {
            assertEquals(ordered, collect(query(sort)).first, "$sort")
            val favorite = collect(query(sort, true)).first
            assertEquals(ordered.filter { notes.single { note -> note.noteId == it }.favorite }, favorite, "$sort favorite")
            assertEquals(favorite.size, favorite.toSet().size)
        }
        val first = assertNotNull(LibraryPageRules.first(catalog, query(LibrarySort.NAME), 2).page)
        assertEquals("n5", first.nextCursor?.anchor?.noteId)
        assertEquals(query(LibrarySort.NAME), first.nextCursor?.query)
    }

    @Test fun continuationRequiresExactQueryRevisionAndCompleteAnchorRow() {
        val query = query(LibrarySort.NAME)
        val first = assertNotNull(LibraryPageRules.first(catalog, query, 2).page)
        val cursor = assertNotNull(first.nextCursor)
        val next = assertNotNull(LibraryPageRules.next(catalog, query, cursor, 2).page)
        assertEquals(listOf("n1", "n2"), next.rows.map { it.noteId })
        assertEquals(LibraryPageFailure.WRONG_QUERY_CURSOR,
            LibraryPageRules.next(catalog, query(LibrarySort.UPDATED), cursor, 2).failure)
        assertEquals(LibraryViewRejection.STALE_REVISION,
            LibraryPageRules.next(catalog.copy(revision = 16), query, cursor, 2).catalogFailure)
        for (changed in listOf(cursor.copy(anchor = cursor.anchor.copy(title = "forged")),
            cursor.copy(anchor = cursor.anchor.copy(favorite = false)),
            cursor.copy(anchor = cursor.anchor.copy(noteId = "missing")))) {
            assertEquals(LibraryPageFailure.INVALID_CURSOR, LibraryPageRules.next(catalog, query, changed, 2).failure)
        }
        val missing = catalog.copy(notes = catalog.notes.filterNot { it.noteId == cursor.anchor.noteId })
        assertEquals(LibraryPageFailure.INVALID_CURSOR, LibraryPageRules.next(missing, query, cursor, 2).failure)
        val favoriteQuery = query(LibrarySort.NAME, true)
        val ineligible = LibraryPageCursor(favoriteQuery, notes.single { it.noteId == "n3" })
        assertEquals(LibraryPageFailure.INVALID_CURSOR, LibraryPageRules.next(catalog, favoriteQuery, ineligible, 2).failure)
        val recentQuery = query(LibrarySort.RECENT)
        val noOpen = LibraryPageCursor(recentQuery, notes.single { it.noteId == "n0" })
        assertEquals(LibraryPageFailure.INVALID_CURSOR, LibraryPageRules.next(catalog, recentQuery, noOpen, 2).failure)
        assertEquals(LibraryPageFailure.INVALID_QUERY,
            LibraryPageRules.first(catalog, query.copy(operation = query.operation.copy(input = "title")), 2).failure)
        for (limit in listOf(0, 201)) assertEquals(LibraryPageFailure.INVALID_QUERY,
            LibraryPageRules.first(catalog, query, limit).failure)
    }

    @Test fun deliveryRequiresCurrentOperationAndCancellationOnlyMatchesExactIdentity() {
        val query = query(LibrarySort.NAME)
        val candidate = LibraryPageRules.first(catalog, query, 2)
        assertEquals(candidate, LibraryPageRules.deliver(candidate, query.operation))
        assertEquals(LibraryPageFailure.STALE_QUERY,
            LibraryPageRules.deliver(candidate, query.operation.copy(queryVersion = 8)).failure)
        assertEquals(LibraryPageFailure.CANCELLED,
            LibraryPageRules.deliver(candidate, query.operation.copy(operationId = "other")).failure)
        assertEquals(LibraryPageFailure.STALE_QUERY,
            LibraryPageRules.deliver(candidate, query.operation.copy(input = "changed")).failure)
        assertSame(query.operation, LibrarySearchRules.cancelQuery(query.operation, "other", 7))
        assertSame(query.operation, LibrarySearchRules.cancelQuery(query.operation, "paging", 8))
        assertNull(LibrarySearchRules.cancelQuery(query.operation, "paging", 7))
        assertEquals(LibraryPageFailure.CANCELLED, LibraryPageRules.deliver(candidate, null).failure)
        val invalid = candidate.copy(page = candidate.page!!.copy(
            query = candidate.page.query.copy(operation = query.operation.copy(queryVersion = -1))))
        assertEquals(LibraryPageFailure.INVALID_QUERY, LibraryPageRules.deliver(invalid, query.operation).failure)
        assertEquals(candidate, LibraryPageRules.deliver(candidate, query.operation))
    }

    @Test fun tamperedRowsCursorOrMetadataCannotBeDelivered() {
        val query = query(LibrarySort.NAME)
        val candidate = LibraryPageRules.first(catalog, query, 2)
        val page = assertNotNull(candidate.page)
        val changed = listOf(
            candidate.copy(page = page.copy(rows = page.rows.drop(1))),
            candidate.copy(page = page.copy(rows = page.rows.reversed())),
            candidate.copy(page = page.copy(nextCursor = page.nextCursor!!.copy(
                anchor = page.nextCursor.anchor.copy(title = "forged")))),
            candidate.copy(page = page.copy(snapshot = page.snapshot.copy(notes = page.snapshot.notes.map {
                if (it.noteId == "n5") it.copy(title = "replacement") else it
            }))),
        )
        for (tampered in changed) {
            assertNull(LibraryPageRules.deliver(tampered, query.operation).page)
            assertEquals(LibraryPageFailure.STALE_RESULT, LibraryPageRules.deliver(tampered, query.operation).failure)
        }
        assertEquals(candidate, LibraryPageRules.deliver(candidate, query.operation))
    }

    @Test fun preparedPageOwnsSnapshotAndResultLists() {
        val query = query(LibrarySort.NAME)
        val mutable = notes.toMutableList()
        val candidate = LibraryPageRules.first(catalog.copy(notes = mutable), query, 2)
        val page = assertNotNull(candidate.page)
        mutable.clear()
        assertEquals(6, page.snapshot.notes.size)
        assertEquals(2, page.rows.size)
        assertEquals("n5", page.nextCursor?.anchor?.noteId)
        assertEquals(candidate, LibraryPageRules.deliver(candidate, query.operation))
        assertEquals(6, notes.size)
    }
}
