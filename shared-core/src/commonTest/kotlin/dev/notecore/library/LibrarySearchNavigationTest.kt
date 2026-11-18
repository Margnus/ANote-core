package dev.notecore.library

import kotlin.test.*

class LibrarySearchNavigationTest {
    private val directory = LibraryFolderSnapshot("local", 15,
        listOf(LibraryFolder("folder", "旅行 Notes", null), LibraryFolder("child", "旅行 Notes", "folder")),
        listOf(LibraryNoteLocation("note", "folder"), LibraryNoteLocation("second", null), LibraryNoteLocation("empty", null)), true)
    private val catalog = LibraryViewSnapshot("local", 15, true, listOf(
        LibraryViewNote("note", "杭州旅行 NOTES \uD83D\uDCD3", 1, null, false),
        LibraryViewNote("second", "旅行 Notes", 2, 4, true),
        LibraryViewNote("empty", "", 0, null, false)), LibraryViewPreference(LibrarySort.NAME, LibraryLayout.LIST))
    private val snapshot = LibrarySearchSnapshot(directory, catalog)

    private fun search(query: String, s: LibrarySearchSnapshot = snapshot) =
        LibrarySearchRules.search(s, "local", 15, query).hits

    private fun navigate(s: LibrarySearchSnapshot, trash: LibraryTrashSnapshot, hit: LibrarySearchHit) =
        LibrarySearchRules.navigate(s, trash, hit)

    @Test fun exactActiveFolderAndNoteHitsNavigateWithCurrentLocation() {
        val trash = LibraryTrashSnapshot(directory, emptyList())
        val hits = search("旅行")
        assertTrue(hits.isNotEmpty())
        for (hit in hits) assertEquals(hit, navigate(snapshot, trash, hit).hit)
        val unrelated = trash.copy(trash = listOf(LibraryTrashNote("deleted", null, 1)))
        assertEquals(hits.single { it.target.kind == LibrarySearchKind.NOTE && it.target.id == "note" },
            navigate(snapshot, unrelated, hits.single { it.target.id == "note" }).hit)
    }

    @Test fun sameRevisionReplacementOrTamperedHitIsRejectedAsStale() {
        val hit = search("杭州").single()
        val renamed = snapshot.copy(catalog = catalog.copy(notes = catalog.notes.map {
            if (it.noteId == "note") it.copy(title = "replacement") else it
        }))
        assertEquals(LibrarySearchFailure.Query.STALE_HIT,
            navigate(renamed, LibraryTrashSnapshot(directory, emptyList()), hit).failure)
        val movedDirectory = directory.copy(notes = directory.notes.map {
            if (it.noteId == "note") it.copy(folderId = "child") else it
        })
        val moved = LibrarySearchSnapshot(movedDirectory, catalog)
        assertEquals(LibrarySearchFailure.Query.STALE_HIT,
            navigate(moved, LibraryTrashSnapshot(movedDirectory, emptyList()), hit).failure)
        assertEquals(LibrarySearchFailure.Query.STALE_HIT,
            navigate(snapshot, LibraryTrashSnapshot(directory, emptyList()), hit.copy(title = "tampered")).failure)
        val folderHit = search("旅行").single { it.target.id == "folder" }
        val renamedDirectory = directory.copy(folders = directory.folders.map {
            if (it.id == "folder") it.copy(title = "replacement") else it
        })
        assertEquals(LibrarySearchFailure.Query.STALE_HIT,
            navigate(LibrarySearchSnapshot(renamedDirectory, catalog),
                LibraryTrashSnapshot(renamedDirectory, emptyList()), folderHit).failure)
    }

    @Test fun recycledNoteOrFolderIsReportedInsteadOfOpeningOrReturningGenericSuccess() {
        val noteHit = search("杭州").single()
        val activeWithoutNote = LibrarySearchSnapshot(directory.copy(notes = directory.notes.filterNot { it.noteId == "note" }),
            catalog.copy(notes = catalog.notes.filterNot { it.noteId == "note" }))
        val trash = LibraryTrashSnapshot(activeWithoutNote.directory,
            listOf(LibraryTrashNote("note", "folder", 20)))
        assertEquals(LibrarySearchFailure.Query.TRASHED, navigate(activeWithoutNote, trash, noteHit).failure)
        val missing = navigate(activeWithoutNote, trash.copy(trash = emptyList()), noteHit)
        assertNull(missing.hit); assertEquals(LibrarySearchFailure.Query.NOT_FOUND, missing.failure)

        val folderHit = search("旅行").single { it.target.id == "folder" }
        val activeWithoutFolders = LibrarySearchSnapshot(directory.copy(folders = emptyList(),
            notes = directory.notes.map { it.copy(folderId = null) }), catalog)
        val groups = listOf(LibraryTrashedFolderGroup("folder", 21,
            listOf(LibraryFolder("folder", "旅行 Notes", null), LibraryFolder("child", "旅行 Notes", "folder")), emptyList()))
        val folderTrash = LibraryTrashSnapshot(activeWithoutFolders.directory, emptyList(), groups)
        assertEquals(LibrarySearchFailure.Query.TRASHED, navigate(activeWithoutFolders, folderTrash, folderHit).failure)
    }

    @Test fun mismatchedOrCorruptTrashScopeRejectsNavigation() {
        val hit = search("杭州").single()
        val stale = LibraryTrashSnapshot(directory.copy(revision = 16), emptyList())
        assertEquals(LibrarySearchFailure.Directory(LibraryFolderRejection.STALE_REVISION), navigate(snapshot, stale, hit).failure)
        val changed = directory.copy(folders = directory.folders.map {
            if (it.id == "folder") it.copy(title = "different") else it
        })
        assertEquals(LibrarySearchFailure.Query.TRASH_SCOPE_MISMATCH,
            navigate(snapshot, LibraryTrashSnapshot(changed, emptyList()), hit).failure)
        val overlap = LibraryTrashSnapshot(directory, listOf(LibraryTrashNote("note", "folder", 1)))
        assertEquals(LibrarySearchFailure.Query.INVALID_TRASH, navigate(snapshot, overlap, hit).failure)
        assertNull(navigate(snapshot, overlap, hit).hit)
    }

    @Test fun sameValueFolderAndNoteIdsKeepIndependentTrashOutcomes() {
        val active = LibrarySearchSnapshot(directory.copy(folders = emptyList(),
            notes = directory.notes.map { it.copy(folderId = null) } + LibraryNoteLocation("folder", null)),
            catalog.copy(notes = catalog.notes + catalog.notes[1].copy(noteId = "folder")))
        val group = LibraryTrashedFolderGroup("folder", 22,
            listOf(LibraryFolder("folder", "旅行 Notes", null)), emptyList())
        val trash = LibraryTrashSnapshot(active.directory, emptyList(), listOf(group))
        val noteCandidates = search("Notes", active).filter { it.target.kind == LibrarySearchKind.NOTE && it.target.id == "folder" }
        assertEquals(1, noteCandidates.size)
        val noteHit = noteCandidates.first()
        assertEquals(noteHit, navigate(active, trash, noteHit).hit)
        val folderCandidates = search("旅行").filter { it.target.kind == LibrarySearchKind.FOLDER && it.target.id == "folder" }
        assertEquals(1, folderCandidates.size)
        val folderHit = folderCandidates.first()
        assertEquals(LibrarySearchFailure.Query.TRASHED, navigate(active, trash, folderHit).failure)
    }
}
