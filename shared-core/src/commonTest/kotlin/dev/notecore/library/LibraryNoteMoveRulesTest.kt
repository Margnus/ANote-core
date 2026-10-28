package dev.notecore.library

import kotlin.test.*

class LibraryNoteMoveRulesTest {
    private val snapshot = LibraryFolderSnapshot("local", 9,
        listOf(LibraryFolder("a", "同名", null), LibraryFolder("b", "同名", "a")),
        listOf(LibraryNoteLocation("editing", "a"), LibraryNoteLocation("other", null)), true)
    private fun plan(s: LibraryFolderSnapshot = snapshot, target: String? = "b") =
        LibraryNoteMoveRules.plan(s, "local", 9, "editing", target)

    @Test fun movingEditingNoteChangesOnlyItsLocationAndCanReturnToRoot() {
        val original = snapshot.copy(notes = snapshot.notes.toList())
        val patch = assertNotNull(plan().patch)
        assertEquals("local", patch.spaceId); assertEquals(9, patch.expectedRevision)
        assertEquals(snapshot.notes.first(), patch.before)
        assertEquals(LibraryNoteLocation("editing", "b"), patch.after)
        val moved = snapshot.copy(revision = 10, notes = listOf(patch.after, snapshot.notes.last()))
        val root = assertNotNull(LibraryNoteMoveRules.plan(moved, "local", 10, "editing", null).patch)
        assertEquals("editing", root.after.noteId); assertNull(root.after.folderId)
        assertEquals(original, snapshot)
        assertEquals(snapshot.notes.last(), moved.notes.last())
        assertEquals(snapshot.folders, moved.folders)
    }

    @Test fun sameDestinationIsAnIdentityPreservingNoOp() {
        val patch = assertNotNull(plan(target = "a").patch)
        assertEquals(patch.before, patch.after)
    }

    @Test fun unknownNoteAndDestinationNeverProducePatch() {
        assertEquals(LibraryFolderRejection.NOT_FOUND,
            LibraryNoteMoveRules.plan(snapshot, "local", 9, "missing", "a").rejection)
        assertEquals(LibraryFolderRejection.PARENT_NOT_FOUND, plan(target = "missing").rejection)
        assertNull(plan(target = "missing").patch)
    }

    @Test fun staleIncompleteAndCrossSpaceSnapshotsAreRejected() {
        for ((state, reason) in listOf(
            snapshot.copy(revision = 10) to LibraryFolderRejection.STALE_REVISION,
            snapshot.copy(complete = false) to LibraryFolderRejection.INCOMPLETE_SNAPSHOT,
            snapshot.copy(spaceId = "remote") to LibraryFolderRejection.WRONG_SPACE)) {
            val result = plan(state)
            assertNull(result.patch); assertEquals(reason, result.rejection)
        }
    }

    @Test fun unrelatedCorruptReferencesAndCyclesBlockMove() {
        for (state in listOf(
            snapshot.copy(notes = snapshot.notes + LibraryNoteLocation("other", "b")),
            snapshot.copy(notes = snapshot.notes + LibraryNoteLocation("orphan", "missing")),
            snapshot.copy(folders = snapshot.folders + LibraryFolder("cycle", "cycle", "cycle")))) {
            assertEquals(LibraryFolderRejection.INVALID_GRAPH, plan(state).rejection)
            assertNull(plan(state).patch)
        }
    }

    @Test fun stalePlanCannotBeRevalidatedAfterConcurrentFolderDeletion() {
        assertNotNull(plan().patch)
        val deleted = snapshot.copy(revision = 10, folders = snapshot.folders.filterNot { it.id == "b" })
        assertEquals(LibraryFolderRejection.STALE_REVISION, plan(deleted).rejection)
        assertEquals(LibraryFolderRejection.PARENT_NOT_FOUND,
            LibraryNoteMoveRules.plan(deleted, "local", 10, "editing", "b").rejection)
    }
}
