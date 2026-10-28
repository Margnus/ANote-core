package dev.notecore.library

import kotlin.test.*

class LibraryBatchRulesTest {
    private val folders = LibraryFolderSnapshot("local", 4, listOf(LibraryFolder("f", "目录", null)),
        listOf(LibraryNoteLocation("a", null), LibraryNoteLocation("b", "f"), LibraryNoteLocation("other", null)), true)
    private val view = LibraryViewSnapshot("local", 4, true,
        listOf("a", "b", "other").map { LibraryViewNote(it, "同名", 5, 8, it == "b") },
        LibraryViewPreference(LibrarySort.RECENT, LibraryLayout.GRID))
    private val trash = LibraryTrashSnapshot(folders, emptyList())
    private val selected = listOf("b", "a")

    @Test fun moveMatchesSingleRulesAndKeepsOtherRowsAndRequestOrder() {
        val input = selected.toMutableList()
        val patch = assertNotNull(LibraryBatchRules.move(folders, "local", 4, input, "f").patch)
        assertEquals("local", patch.spaceId); assertEquals(4, patch.expectedRevision)
        assertEquals(selected, patch.rows.map { it.after.noteId })
        assertEquals(selected.map { LibraryNoteMoveRules.plan(folders, "local", 4, it, "f").patch }, patch.rows)
        val applied = folders.notes.associateBy { it.noteId }.toMutableMap()
        patch.rows.forEach { applied[it.before.noteId] = it.after }
        assertEquals(LibraryNoteLocation("other", null), applied["other"])
        assertEquals("f", applied.getValue("a").folderId)
        input.clear(); assertEquals(2, patch.rows.size)
        assertEquals(null, folders.notes.first().folderId)
    }

    @Test fun favoriteAndUnfavoriteMatchSingleRuleWithoutModifyingTimestamps() {
        for (value in listOf(true, false)) {
            val patch = assertNotNull(LibraryBatchRules.favorite(view, "local", 4, selected, value).patch)
            assertEquals(selected.map { LibraryViewRules.plan(view, "local", 4, LibraryViewChange.Favorite(it, value)).patch }, patch.rows)
            patch.rows.forEach { row ->
                assertEquals(row.beforeNote!!.copy(favorite = value), row.afterNote)
                assertEquals(view.preference, row.afterPreference)
            }
        }
        assertFalse(view.notes.first().favorite)
    }

    @Test fun recycleMatchesSingleRuleAndHasOnlySelectedIdentities() {
        val patch = assertNotNull(LibraryBatchRules.recycle(trash, "local", 4, selected, 100).patch)
        assertEquals(selected.map { LibraryTrashRules.recycle(trash, "local", 4, it, 100).patch }, patch.rows)
        assertEquals(selected, patch.rows.map { it.afterTrash!!.noteId })
        assertTrue(patch.rows.all { it.afterActive == null && it.beforeTrash == null })
        assertEquals(listOf("f", null), patch.rows.map { it.afterTrash!!.originalFolderId })
        assertEquals(3, trash.active.notes.size); assertTrue(trash.trash.isEmpty())
    }

    @Test fun badLastIdentityNeverLeaksAPartialBatch() {
        val ids = listOf("a", "b", "missing")
        val plans = listOf(LibraryBatchRules.move(folders, "local", 4, ids, null),
            LibraryBatchRules.favorite(view, "local", 4, ids, true),
            LibraryBatchRules.recycle(trash, "local", 4, ids, 100))
        plans.forEach {
            assertNull(it.patch)
            assertEquals(LibraryBatchFailure.Selection(LibraryBatchSelectionRejection.NOT_FOUND), it.failure)
        }
    }

    @Test fun invalidSelectionsAreRejectedByEveryOperation() {
        for ((ids, reason) in listOf(emptyList<String>() to LibraryBatchSelectionRejection.EMPTY,
            listOf("a", "a") to LibraryBatchSelectionRejection.DUPLICATE,
            List(LibraryBatchRules.MAX_SELECTION + 1) { "a$it" } to LibraryBatchSelectionRejection.CAPACITY)) {
            val plans = listOf(LibraryBatchRules.move(folders, "local", 4, ids, null),
                LibraryBatchRules.favorite(view, "local", 4, ids, true),
                LibraryBatchRules.recycle(trash, "local", 4, ids, 100))
            plans.forEach { assertNull(it.patch); assertEquals(LibraryBatchFailure.Selection(reason), it.failure) }
        }
    }

    @Test fun staleAndCrossSpaceRequestsNeverProduceRows() {
        for ((space, revision) in listOf("other" to 4L, "local" to 3L)) {
            assertNull(LibraryBatchRules.move(folders, space, revision, selected, null).patch)
            assertNull(LibraryBatchRules.favorite(view, space, revision, selected, true).patch)
            assertNull(LibraryBatchRules.recycle(trash, space, revision, selected, 100).patch)
        }
        assertNull(LibraryBatchRules.move(folders.copy(revision = 5), "local", 4, selected, null).patch)
    }

    @Test fun partialAndCorruptSnapshotsCannotBeUsedEvenForValidSelectedRows() {
        assertNull(LibraryBatchRules.move(folders.copy(complete = false), "local", 4, selected, null).patch)
        assertNull(LibraryBatchRules.favorite(view.copy(complete = false), "local", 4, selected, true).patch)
        assertNull(LibraryBatchRules.recycle(trash.copy(active = folders.copy(complete = false)), "local", 4, selected, 100).patch)
        val corrupt = folders.copy(notes = folders.notes + LibraryNoteLocation("broken", "missing"))
        assertEquals(LibraryBatchFailure.Folder(LibraryFolderRejection.INVALID_GRAPH),
            LibraryBatchRules.move(corrupt, "local", 4, selected, null).failure)
        val invalid = view.copy(notes = view.notes + view.notes.first().copy(noteId = "bad", updatedAtMillis = -1))
        assertEquals(LibraryBatchFailure.View(LibraryViewRejection.INVALID_METADATA),
            LibraryBatchRules.favorite(invalid, "local", 4, selected, true).failure)
    }

    @Test fun invalidDestinationTimestampAndPreviouslyRecycledSelectionRejectWholeBatch() {
        assertEquals(LibraryBatchFailure.Folder(LibraryFolderRejection.PARENT_NOT_FOUND),
            LibraryBatchRules.move(folders, "local", 4, selected, "missing").failure)
        assertEquals(LibraryBatchFailure.Trash(LibraryTrashRejection.INVALID_TIME),
            LibraryBatchRules.recycle(trash, "local", 4, selected, -1).failure)
        val state = trash.copy(active = folders.copy(notes = folders.notes.filterNot { it.noteId == "b" }),
            trash = listOf(LibraryTrashNote("b", "f", 99)))
        val result = LibraryBatchRules.recycle(state, "local", 4, listOf("a", "b"), 100)
        assertNull(result.patch); assertEquals(LibraryBatchFailure.Trash(LibraryTrashRejection.ALREADY_TRASHED), result.failure)
        val overlap = trash.copy(trash = state.trash)
        assertEquals(LibraryBatchFailure.Trash(LibraryTrashRejection.INVALID_TRASH),
            LibraryBatchRules.recycle(overlap, "local", 4, listOf("a"), 100).failure)
    }

    @Test fun maximumSelectionIsAdmittedAsOneVersionBoundBatch() {
        val rows = List(LibraryBatchRules.MAX_SELECTION) { LibraryNoteLocation("n$it", null) }
        val state = folders.copy(notes = rows)
        val patch = assertNotNull(LibraryBatchRules.move(state, "local", 4, rows.map { it.noteId }, null).patch)
        assertEquals(1000, patch.rows.size)
        assertTrue(patch.rows.all { it.expectedRevision == patch.expectedRevision && it.before == it.after })
        assertEquals(rows, state.notes)
    }
}
