package dev.notecore.library

import kotlin.test.*

class LibraryTrashRulesTest {
    private val original = LibraryTrashSnapshot(LibraryFolderSnapshot("local", 7,
        listOf(LibraryFolder("folder", "旅行", null)),
        listOf(LibraryNoteLocation("note", "folder"), LibraryNoteLocation("other", null)), true), emptyList())
    private fun recycle(s: LibraryTrashSnapshot = original, id: String = "note") =
        LibraryTrashRules.recycle(s, "local", s.active.revision, id, 123)
    private fun apply(s: LibraryTrashSnapshot, patch: LibraryTrashPatch): LibraryTrashSnapshot {
        assertEquals(s.active.spaceId, patch.spaceId); assertEquals(s.active.revision, patch.expectedRevision)
        return s.copy(active = s.active.copy(revision = s.active.revision + 1,
            notes = s.active.notes.filterNot { it.noteId == patch.beforeActive?.noteId } + listOfNotNull(patch.afterActive)),
            trash = s.trash.filterNot { it.noteId == patch.beforeTrash?.noteId } + listOfNotNull(patch.afterTrash))
    }
    private fun deleted() = apply(original, assertNotNull(recycle().patch))

    @Test fun recycleAndRestoreKeepIdentityOriginalFolderAndOtherRows() {
        val captured = original.copy(active = original.active.copy(notes = original.active.notes.toList()))
        val patch = assertNotNull(recycle().patch)
        assertEquals(LibraryNoteLocation("note", "folder"), patch.beforeActive)
        assertNull(patch.afterActive); assertNull(patch.beforeTrash)
        assertEquals(LibraryTrashNote("note", "folder", 123), patch.afterTrash)
        val trash = apply(original, patch)
        val restore = assertNotNull(LibraryTrashRules.restore(trash, "local", 8, "note").patch)
        assertEquals(patch.afterTrash, restore.beforeTrash); assertNull(restore.afterTrash)
        val restored = apply(trash, restore)
        assertEquals(original.active.notes.toSet(), restored.active.notes.toSet())
        assertEquals(original.active.folders, restored.active.folders)
        assertTrue(restored.trash.isEmpty()); assertEquals(captured, original)
    }

    @Test fun missingOriginalFolderRestoresToRootButSameTitleOtherFolderIsNotUsed() {
        val trash = deleted().let { it.copy(active = it.active.copy(
            folders = listOf(LibraryFolder("different", "旅行", null)))) }
        val patch = assertNotNull(LibraryTrashRules.restore(trash, "local", 8, "note").patch)
        assertEquals(LibraryNoteLocation("note", null), patch.afterActive)
        assertEquals("folder", patch.beforeTrash!!.originalFolderId)
    }

    @Test fun rootNoteRemainsRootAfterRecycleAndRestore() {
        val trash = apply(original, assertNotNull(recycle(id = "other").patch))
        assertNull(trash.trash.single().originalFolderId)
        val patch = assertNotNull(LibraryTrashRules.restore(trash, "local", 8, "other").patch)
        assertEquals(LibraryNoteLocation("other", null), patch.afterActive)
    }

    @Test fun repeatedAndUnknownActionsAreExplicitAndNeverMutateSnapshot() {
        val trash = deleted()
        assertEquals(LibraryTrashRejection.ALREADY_TRASHED, recycle(trash).rejection)
        assertEquals(LibraryTrashRejection.NOT_FOUND, recycle(id = "missing").rejection)
        assertEquals(LibraryTrashRejection.NOT_TRASHED, LibraryTrashRules.restore(original, "local", 7, "note").rejection)
        assertEquals(LibraryTrashRejection.INVALID_TIME, LibraryTrashRules.recycle(original, "local", 7, "note", -1).rejection)
        assertEquals(deleted(), trash)
    }

    @Test fun allOperationsRefuseWrongSpaceStaleAndPartialSnapshots() {
        for ((space, revision, state, reason) in listOf(
            Scope("other", 7, original, LibraryFolderRejection.WRONG_SPACE),
            Scope("local", 6, original, LibraryFolderRejection.STALE_REVISION),
            Scope("local", 7, original.copy(active = original.active.copy(complete = false)), LibraryFolderRejection.INCOMPLETE_SNAPSHOT))) {
            assertEquals(reason, LibraryTrashRules.recycle(state, space, revision, "note", 1).snapshotRejection)
            assertEquals(reason, LibraryTrashRules.restore(state, space, revision, "note").snapshotRejection)
            assertEquals(reason, LibraryTrashRules.inspectEmpty(state, space, revision).snapshotRejection)
        }
    }
    private data class Scope(val space: String, val revision: Long, val state: LibraryTrashSnapshot, val reason: LibraryFolderRejection)

    @Test fun duplicateAndOverlappingIdentitiesMalformedHistoryAreRejected() {
        val row = LibraryTrashNote("deleted", "missing", 0)
        for (rows in listOf(listOf(row, row), listOf(row.copy(noteId = "note")),
            listOf(row.copy(originalFolderId = "\uD800")), listOf(row.copy(deletedAtMillis = -1)),
            listOf(row.copy(noteId = "")))) {
            val state = original.copy(trash = rows)
            assertEquals(LibraryTrashRejection.INVALID_TRASH, recycle(state).rejection)
            assertEquals(LibraryTrashRejection.INVALID_TRASH, LibraryTrashRules.restore(state, "local", 7, "deleted").rejection)
            assertEquals(LibraryTrashRejection.INVALID_TRASH, LibraryTrashRules.inspectEmpty(state, "local", 7).rejection)
        }
    }

    @Test fun corruptActiveFolderAndTotalCapacityRejectBeforePlanning() {
        val corrupt = original.copy(active = original.active.copy(folders = emptyList()))
        assertEquals(LibraryFolderRejection.INVALID_GRAPH, recycle(corrupt).snapshotRejection)
        val over = original.copy(trash = List(LibraryFolderRules.MAX_NOTES - 1) { LibraryTrashNote("x$it", null, 0) })
        assertEquals(LibraryFolderRejection.CAPACITY, recycle(over).snapshotRejection)
        assertNull(recycle(over).patch)
    }

    @Test fun emptyScopeIsSortedDetachedAndExplicitlyVersionBound() {
        val rows = mutableListOf(LibraryTrashNote("z", null, 2), LibraryTrashNote("a", "gone", 1))
        val state = original.copy(trash = rows)
        val scope = assertNotNull(LibraryTrashRules.inspectEmpty(state, "local", 7).scope)
        assertEquals("local", scope.spaceId); assertEquals(7, scope.expectedRevision)
        assertEquals(listOf("a", "z"), scope.rows.map { it.noteId })
        assertNotNull(LibraryTrashRules.planEmpty(state, "local", 7, scope).scope)
        rows.clear()
        assertEquals(2, scope.rows.size)
        assertEquals(LibraryTrashRejection.SCOPE_CHANGED, LibraryTrashRules.planEmpty(state, "local", 7, scope).rejection)
    }

    @Test fun changedScopeCannotConfirmNewTrashRowsOrCrossSpaceHistory() {
        val state = deleted()
        val scope = assertNotNull(LibraryTrashRules.inspectEmpty(state, "local", 8).scope)
        val changed = listOf(
            state.copy(trash = state.trash + LibraryTrashNote("new", null, 124)),
            state.copy(trash = state.trash.map { it.copy(deletedAtMillis = 124) }),
            state.copy(trash = emptyList()))
        for (s in changed) assertEquals(LibraryTrashRejection.SCOPE_CHANGED,
            LibraryTrashRules.planEmpty(s, "local", 8, scope).rejection)
        for (candidate in listOf(scope.copy(spaceId = "other"), scope.copy(expectedRevision = 7))) {
            assertEquals(LibraryTrashRejection.SCOPE_CHANGED, LibraryTrashRules.planEmpty(state, "local", 8, candidate).rejection)
        }
        val newer = state.copy(active = state.active.copy(revision = 9))
        assertEquals(LibraryFolderRejection.STALE_REVISION, LibraryTrashRules.planEmpty(newer, "local", 8, scope).snapshotRejection)
        assertEquals(LibraryTrashRejection.SCOPE_CHANGED, LibraryTrashRules.planEmpty(newer, "local", 9, scope).rejection)
    }

    @Test fun alreadyEmptyScopeIsSafeNoOpAndOriginalContentsRemainUntouched() {
        val scope = assertNotNull(LibraryTrashRules.inspectEmpty(original, "local", 7).scope)
        assertTrue(scope.rows.isEmpty())
        assertEquals(scope, LibraryTrashRules.planEmpty(original, "local", 7, scope).scope)
        assertEquals(2, original.active.notes.size); assertEquals(1, original.active.folders.size)
    }
}
