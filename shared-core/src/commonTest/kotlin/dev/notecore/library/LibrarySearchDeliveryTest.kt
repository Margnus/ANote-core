package dev.notecore.library

import kotlin.test.*

class LibrarySearchDeliveryTest {
    private val directory = LibraryFolderSnapshot("local", 15,
        listOf(LibraryFolder("folder", "旅行 Notes", null), LibraryFolder("child", "旅行 Notes", "folder")),
        listOf(LibraryNoteLocation("note", "folder"), LibraryNoteLocation("second", null), LibraryNoteLocation("empty", null)), true)
    private val catalog = LibraryViewSnapshot("local", 15, true, listOf(
        LibraryViewNote("note", "杭州旅行 NOTES \uD83D\uDCD3", 1, null, false),
        LibraryViewNote("second", "旅行 Notes", 2, 4, true),
        LibraryViewNote("empty", "", 0, null, false)), LibraryViewPreference(LibrarySort.NAME, LibraryLayout.LIST))
    private val snapshot = LibrarySearchSnapshot(directory, catalog)
    private val trash = LibraryTrashSnapshot(directory, listOf(LibraryTrashNote("deleted", null, 1)))
    private val query = LibrarySearchQuery("search", 7, "local", 15, "nOtEs")

    private fun prepare(s: LibrarySearchSnapshot = snapshot, t: LibraryTrashSnapshot = trash,
                        q: LibrarySearchQuery = query) = assertNotNull(LibrarySearchRules.prepareQuery(s, t, q).plan)

    @Test fun currentQueryDeliversExactInputAndRepeatedReviewIsIdempotent() {
        val candidate = prepare()
        assertEquals(LibrarySearchRules.search(snapshot, "local", 15, "nOtEs"), candidate.result)
        assertEquals(listOf("child", "folder", "second", "note"), candidate.result.hits.map { it.target.id })
        val first = LibrarySearchRules.deliverQuery(candidate, query)
        val second = LibrarySearchRules.deliverQuery(candidate, query)
        assertNull(first.failure)
        assertSame(candidate.result, first.result)
        assertEquals(first, second)
        assertSame(first.result, second.result)
        assertEquals("nOtEs", candidate.query.input)
        assertEquals("杭州旅行 NOTES \uD83D\uDCD3", catalog.notes[0].title)
        assertSame(candidate.result, LibrarySearchRules.deliverQuery(candidate, query).result)
    }

    @Test fun oldVersionOrChangedRequestCannotOverwriteCurrentQuery() {
        val candidate = prepare()
        val newer = query.copy(queryVersion = 8, input = "杭州")
        val stale = LibrarySearchRules.deliverQuery(candidate, newer)
        assertNull(stale.result)
        assertEquals(LibrarySearchDeliveryFailure.STALE_QUERY, stale.failure)
        for (active in listOf(query.copy(input = "杭州"), query.copy(expectedRevision = 16),
                              query.copy(spaceId = "other"))) {
            val result = LibrarySearchRules.deliverQuery(candidate, active)
            assertNull(result.result)
            assertEquals(LibrarySearchDeliveryFailure.STALE_QUERY, result.failure)
        }
        assertSame(candidate.result, LibrarySearchRules.deliverQuery(candidate, query).result)
        assertEquals("nOtEs", query.input)
    }

    @Test fun cancellationRequiresBothMatchingOperationIdAndVersion() {
        val candidate = prepare()
        assertSame(query, LibrarySearchRules.cancelQuery(query, "other", 7))
        assertSame(query, LibrarySearchRules.cancelQuery(query, "search", 8))
        assertSame(candidate.result, LibrarySearchRules.deliverQuery(candidate, query).result)
        val wrongOperation = LibrarySearchRules.deliverQuery(candidate, query.copy(operationId = "other"))
        assertNull(wrongOperation.result)
        assertEquals(LibrarySearchDeliveryFailure.CANCELLED, wrongOperation.failure)
        val cancelled = LibrarySearchRules.cancelQuery(query, "search", 7)
        assertNull(cancelled)
        val late = LibrarySearchRules.deliverQuery(candidate, cancelled)
        assertNull(late.result)
        assertEquals(LibrarySearchDeliveryFailure.CANCELLED, late.failure)
        assertSame(candidate.result, LibrarySearchRules.deliverQuery(candidate, query).result)
    }

    @Test fun tamperedHitsFailureMetadataOrTrashCannotBeDelivered() {
        val candidate = prepare()
        val hit = candidate.result.hits.single { it.target.kind == LibrarySearchKind.NOTE && it.target.id == "note" }
        val changed = listOf(
            candidate.copy(result = candidate.result.copy(hits = candidate.result.hits.drop(1))),
            candidate.copy(result = candidate.result.copy(hits = candidate.result.hits.reversed())),
            candidate.copy(result = candidate.result.copy(hits = candidate.result.hits.map {
                if (it == hit) it.copy(title = "tampered") else it
            })),
            candidate.copy(result = candidate.result.copy(failure = LibrarySearchFailure.Query.RESULT_CAPACITY)),
            candidate.copy(snapshot = snapshot.copy(catalog = catalog.copy(notes = catalog.notes.map {
                if (it.noteId == "note") it.copy(title = "replacement") else it
            }))),
            candidate.copy(trash = trash.copy(trash = trash.trash + LibraryTrashNote("note", "folder", 20))),
        )
        for (tampered in changed) {
            val result = LibrarySearchRules.deliverQuery(tampered, query)
            assertNull(result.result)
            assertEquals(LibrarySearchDeliveryFailure.STALE_RESULT, result.failure)
        }
        assertSame(candidate.result, LibrarySearchRules.deliverQuery(candidate, query).result)
        assertEquals(4, candidate.result.hits.size)
    }

    @Test fun preparedPlanOwnsSnapshotListsAndPreservesOriginalQueryInput() {
        val folders = directory.folders.toMutableList()
        val locations = directory.notes.toMutableList()
        val notes = catalog.notes.toMutableList()
        val trashRows = trash.trash.toMutableList()
        val active = directory.copy(folders = folders, notes = locations)
        val candidate = prepare(LibrarySearchSnapshot(active, catalog.copy(notes = notes)),
            LibraryTrashSnapshot(active, trashRows), query.copy(input = "  nOtEs  "))
        folders.clear(); locations.clear(); notes.clear(); trashRows.clear()
        assertTrue(candidate.result.hits.isEmpty())
        assertEquals("  nOtEs  ", candidate.query.input)
        assertEquals(2, candidate.snapshot.directory.folders.size)
        assertEquals(3, candidate.snapshot.directory.notes.size)
        assertEquals(3, candidate.snapshot.catalog.notes.size)
        assertEquals(1, candidate.trash.trash.size)
        assertSame(candidate.result, LibrarySearchRules.deliverQuery(candidate, candidate.query).result)
    }

    @Test fun invalidOperationsAndCorruptScopesNeverExposePartialHits() {
        for (invalid in listOf(query.copy(operationId = " "), query.copy(operationId = "x".repeat(129)),
                               query.copy(operationId = "\uD800"), query.copy(queryVersion = -1))) {
            val preparation = LibrarySearchRules.prepareQuery(snapshot, trash, invalid)
            assertNull(preparation.plan)
            assertEquals(LibrarySearchDeliveryFailure.INVALID_OPERATION, preparation.failure)
        }
        val candidate = prepare()
        val invalidDelivery = LibrarySearchRules.deliverQuery(candidate.copy(query = query.copy(queryVersion = -1)), query)
        assertNull(invalidDelivery.result)
        assertEquals(LibrarySearchDeliveryFailure.INVALID_OPERATION, invalidDelivery.failure)
        val corrupt = prepare(snapshot.copy(catalog = catalog.copy(notes = catalog.notes.drop(1))))
        assertEquals(LibrarySearchFailure.Query.IDENTITY_MISMATCH, corrupt.result.failure)
        assertTrue(corrupt.result.hits.isEmpty())
        val delivered = LibrarySearchRules.deliverQuery(corrupt, query)
        assertEquals(LibrarySearchFailure.Query.IDENTITY_MISMATCH, delivered.result?.failure)
        assertTrue(delivered.result!!.hits.isEmpty())
        assertNull(delivered.failure)
    }
}
