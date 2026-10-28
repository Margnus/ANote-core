package dev.notecore.library

import kotlin.test.*

class LibrarySearchRulesTest {
    private val directory = LibraryFolderSnapshot("local", 15,
        listOf(LibraryFolder("folder", "旅行 Notes", null), LibraryFolder("child", "旅行 Notes", "folder")),
        listOf(LibraryNoteLocation("note", "folder"), LibraryNoteLocation("second", null), LibraryNoteLocation("empty", null)), true)
    private val catalog = LibraryViewSnapshot("local", 15, true, listOf(
        LibraryViewNote("note", "杭州旅行 NOTES \uD83D\uDCD3", 1, null, false),
        LibraryViewNote("second", "旅行 Notes", 2, 4, true),
        LibraryViewNote("empty", "", 0, null, false)), LibraryViewPreference(LibrarySort.NAME, LibraryLayout.LIST))
    private val snapshot = LibrarySearchSnapshot(directory, catalog)
    private fun search(query: String, s: LibrarySearchSnapshot = snapshot) = LibrarySearchRules.search(s, "local", 15, query)

    @Test fun chineseAndEnglishSubstringSearchKeepTypedStableTargets() {
        for (query in listOf("旅行", "nOtEs")) {
            val result = search(query)
            assertNull(result.failure)
            assertEquals(listOf("child", "folder", "second", "note"), result.hits.map { it.target.id })
            assertEquals(listOf(LibrarySearchKind.FOLDER, LibrarySearchKind.FOLDER, LibrarySearchKind.NOTE, LibrarySearchKind.NOTE),
                result.hits.map { it.target.kind })
            result.hits.forEach { assertEquals("local", it.target.spaceId); assertEquals(15, it.target.expectedRevision) }
        }
        assertEquals(listOf("note"), search("杭州").hits.map { it.target.id })
        assertEquals(listOf("note"), search("\uD83D\uDCD3").hits.map { it.target.id })
        assertTrue(search("不存在").hits.isEmpty())
    }

    @Test fun sortingDoesNotDependOnMetadataEnumerationOrViewPreference() {
        val reversed = LibrarySearchSnapshot(directory.copy(folders = directory.folders.reversed(), notes = directory.notes.reversed()),
            catalog.copy(notes = catalog.notes.reversed(), preference = LibraryViewPreference(LibrarySort.RECENT, LibraryLayout.GRID)))
        assertEquals(search("Notes"), search("Notes", reversed))
    }

    @Test fun resolveFindsIdentityAndCurrentLocationInsteadOfMatchingDuplicateTitle() {
        val hits = search("旅行").hits
        for (hit in hits) assertEquals(hit, LibrarySearchRules.resolve(snapshot, hit.target).hit)
        assertEquals("folder", hits.single { it.target.id == "note" }.folderId)
        assertNull(hits.single { it.target.id == "second" }.folderId)
        val missing = LibrarySearchTarget("local", 15, LibrarySearchKind.NOTE, "deleted")
        assertEquals(LibrarySearchFailure.Query.NOT_FOUND, LibrarySearchRules.resolve(snapshot, missing).failure)
    }

    @Test fun sameStringFolderAndNoteIdsRemainDistinctTargets() {
        val s = LibrarySearchSnapshot(directory.copy(notes = directory.notes + LibraryNoteLocation("folder", null)),
            catalog.copy(notes = catalog.notes + catalog.notes[1].copy(noteId = "folder")))
        val results = search("Notes", s).hits.filter { it.target.id == "folder" }
        assertEquals(2, results.size)
        assertEquals(setOf(LibrarySearchKind.FOLDER, LibrarySearchKind.NOTE), results.map { it.target.kind }.toSet())
        for (hit in results) assertEquals(hit, LibrarySearchRules.resolve(s, hit.target).hit)
    }

    @Test fun staleClickAfterMoveOrRecycleRequiresRefresh() {
        val hit = search("杭州").hits.single()
        val moved = LibrarySearchSnapshot(directory.copy(revision = 16,
            notes = directory.notes.map { if (it.noteId == "note") it.copy(folderId = "child") else it }), catalog.copy(revision = 16))
        val resolution = LibrarySearchRules.resolve(moved, hit.target)
        assertNull(resolution.hit)
        assertEquals(LibrarySearchFailure.Directory(LibraryFolderRejection.STALE_REVISION), resolution.failure)
        val fresh = LibrarySearchRules.search(moved, "local", 16, "杭州").hits.single()
        assertEquals("child", LibrarySearchRules.resolve(moved, fresh.target).hit!!.folderId)
        val recycled = LibrarySearchSnapshot(directory.copy(notes = directory.notes.filterNot { it.noteId == "note" }),
            catalog.copy(notes = catalog.notes.filterNot { it.noteId == "note" }))
        assertTrue(search("杭州", recycled).hits.isEmpty())
        assertEquals(LibrarySearchFailure.Query.NOT_FOUND, LibrarySearchRules.resolve(recycled, hit.target).failure)
    }

    @Test fun trashOrMissingTitleRowsCannotBeSilentlyMixedIntoActiveSearch() {
        for (notes in listOf(catalog.notes + catalog.notes[0].copy(noteId = "trashed"), catalog.notes.drop(1))) {
            val s = snapshot.copy(catalog = catalog.copy(notes = notes))
            assertEquals(LibrarySearchFailure.Query.IDENTITY_MISMATCH, search("旅行", s).failure)
            assertTrue(search("旅行", s).hits.isEmpty())
            assertNull(LibrarySearchRules.resolve(s, LibrarySearchTarget("local", 15, LibrarySearchKind.NOTE, "note")).hit)
        }
    }

    @Test fun invalidOrPartialScopeRejectsEvenBlankQueries() {
        val states = listOf(snapshot.copy(directory = directory.copy(complete = false)),
            snapshot.copy(catalog = catalog.copy(complete = false)),
            snapshot.copy(catalog = catalog.copy(spaceId = "other")),
            snapshot.copy(catalog = catalog.copy(revision = 16)),
            snapshot.copy(directory = directory.copy(folders = emptyList())))
        for (s in states) { assertNotNull(search("", s).failure); assertTrue(search("", s).hits.isEmpty()) }
        assertNotNull(LibrarySearchRules.search(snapshot, "other", 15, "旅行").failure)
    }

    @Test fun blankMalformedAndLiteralWhitespaceQueriesHaveExplicitSemantics() {
        assertEquals(LibrarySearchResult(), search("   "))
        assertEquals(LibrarySearchResult(), search(""))
        for (query in listOf("\uD800", "a".repeat(121))) {
            assertEquals(LibrarySearchFailure.Query.INVALID_QUERY, search(query).failure)
        }
        assertTrue(search(" 旅行 ").hits.isEmpty())
        assertEquals("杭州旅行 NOTES \uD83D\uDCD3", catalog.notes[0].title)
    }

    @Test fun resultBudgetAllowsExactLimitAndRejectsOverflowWithoutPartialHits() {
        fun large(count: Int): LibrarySearchSnapshot {
            val rows = List(count) { LibraryViewNote("n$it", "match", 0, null, false) }
            return LibrarySearchSnapshot(directory.copy(folders = emptyList(), notes = rows.map { LibraryNoteLocation(it.noteId, null) }),
                catalog.copy(notes = rows))
        }
        assertEquals(LibrarySearchRules.MAX_RESULTS, search("match", large(LibrarySearchRules.MAX_RESULTS)).hits.size)
        val overflow = search("match", large(LibrarySearchRules.MAX_RESULTS + 1))
        assertEquals(LibrarySearchFailure.Query.RESULT_CAPACITY, overflow.failure); assertTrue(overflow.hits.isEmpty())
    }
}
