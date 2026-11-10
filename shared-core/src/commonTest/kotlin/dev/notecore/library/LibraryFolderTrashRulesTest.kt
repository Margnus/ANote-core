package dev.notecore.library

import kotlin.test.*

class LibraryFolderTrashRulesTest {
    private val folders = listOf(LibraryFolder("parent", "父目录", null), LibraryFolder("root", "项目", "parent"),
        LibraryFolder("child", "子目录", "root"), LibraryFolder("empty", "空目录", "root"), LibraryFolder("other", "其它", null))
    private val notes = listOf(LibraryNoteLocation("a", "root"), LibraryNoteLocation("b", "child"), LibraryNoteLocation("outside", "other"))
    private val original = LibraryTrashSnapshot(LibraryFolderSnapshot("local", 7, folders, notes, true),
        listOf(LibraryTrashNote("old", "child", 1)))
    private fun recycle(s: LibraryTrashSnapshot = original, root: String = "root") =
        LibraryFolderTrashRules.recycle(s, "local", s.active.revision, root, 20)

    /** Test projection only: production must validate and apply all before/after rows in one real transaction. */
    private fun apply(s: LibraryTrashSnapshot, p: LibraryFolderTrashPatch): LibraryTrashSnapshot {
        assertEquals(s.active.spaceId, p.spaceId); assertEquals(s.active.revision, p.expectedRevision)
        val folders = s.active.folders.associateBy { it.id }.toMutableMap()
        val notes = s.active.notes.associateBy { it.noteId }.toMutableMap()
        val trash = s.trash.associateBy { it.noteId }.toMutableMap()
        for (row in p.folders) {
            val id = row.before?.id ?: row.after!!.id
            assertEquals(row.before, folders[id]); folders.remove(id); row.after?.let { folders[id] = it }
        }
        for (row in p.notes) {
            val id = row.beforeActive?.noteId ?: row.beforeTrash?.noteId ?: row.afterActive!!.noteId
            assertEquals(row.beforeActive, notes[id]); assertEquals(row.beforeTrash, trash[id])
            notes.remove(id); trash.remove(id)
            row.afterActive?.let { notes[id] = it }; row.afterTrash?.let { trash[id] = it }
        }
        val groups = s.folderGroups.toMutableList()
        p.beforeGroup?.let { assertTrue(groups.remove(it)) }; p.afterGroup?.let { groups.add(it) }
        return LibraryTrashSnapshot(s.active.copy(revision = s.active.revision + 1,
            folders = folders.values.toList(), notes = notes.values.toList()), trash.values.toList(), groups)
    }

    @Test fun nestedSubtreeRoundTripKeepsAllIdentitiesAndUnrelatedTrash() {
        val patch = assertNotNull(recycle().patch)
        assertEquals(setOf("root", "child", "empty"), patch.folders.map { it.before!!.id }.toSet())
        assertEquals(listOf("a", "b"), patch.notes.map { it.afterTrash!!.noteId })
        assertEquals(listOf("root", "child"), patch.notes.map { it.afterTrash!!.originalFolderId })
        assertTrue(patch.notes.all { it.afterTrash!!.deletedAtMillis == 20L })
        val deleted = apply(original, patch)
        assertEquals(setOf("parent", "other"), deleted.active.folders.map { it.id }.toSet())
        assertEquals(listOf(notes.last()), deleted.active.notes)
        val restored = apply(deleted, assertNotNull(LibraryFolderTrashRules.restore(deleted, "local", 8, "root").patch))
        assertEquals(folders.toSet(), restored.active.folders.toSet()); assertEquals(notes.toSet(), restored.active.notes.toSet())
        assertEquals(original.trash, restored.trash); assertTrue(restored.folderGroups.isEmpty())
        assertEquals(5, original.active.folders.size); assertEquals(3, original.active.notes.size)
    }

    @Test fun deletedParentFallsBackToRootAndSameTitleNeverChangesIdentity() {
        val deleted = apply(original, assertNotNull(recycle().patch))
        val gone = deleted.copy(active = deleted.active.copy(folders = deleted.active.folders.filterNot { it.id == "parent" } +
            LibraryFolder("new-parent", "父目录", null)))
        val patch = assertNotNull(LibraryFolderTrashRules.restore(gone, "local", 8, "root").patch)
        assertNull(patch.folders.single { it.after!!.id == "root" }.after!!.parentId)
        assertEquals("root", patch.folders.single { it.after!!.id == "child" }.after!!.parentId)
    }

    @Test fun movedParentThatWouldExceedFiveLevelsRefusesWholeRestore() {
        val deleted = apply(original, assertNotNull(recycle().patch))
        val chain = (1..4).map { LibraryFolder("d$it", "层$it", if (it == 1) null else "d${it-1}") }
        val deep = deleted.copy(active = deleted.active.copy(folders = chain +
            deleted.active.folders.map { if (it.id == "parent") it.copy(parentId = "d4") else it }))
        val result = LibraryFolderTrashRules.restore(deep, "local", 8, "root")
        assertNull(result.patch); assertEquals(LibraryFolderTrashRejection.DEPTH_LIMIT, result.rejection)
        assertEquals(3, deep.trash.size); assertEquals(1, deep.folderGroups.size)
    }

    @Test fun groupedNoteCannotBeRestoredSeparatelyAndClearScopeIncludesEmptyFolderGroups() {
        val deleted = apply(original, assertNotNull(recycle().patch))
        assertEquals(LibraryTrashRejection.GROUPED_NOTE, LibraryTrashRules.restore(deleted, "local", 8, "a").rejection)
        assertNotNull(LibraryTrashRules.restore(deleted, "local", 8, "old").patch)
        val empty = LibraryTrashSnapshot(original.active.copy(notes = emptyList()), emptyList())
        val state = apply(empty, assertNotNull(recycle(empty, "root").patch))
        val scope = assertNotNull(LibraryTrashRules.inspectEmpty(state, "local", 8).scope)
        assertTrue(scope.rows.isEmpty()); assertEquals(3, scope.folderGroups.single().folders.size)
        assertNotNull(LibraryTrashRules.planEmpty(state, "local", 8, scope).scope)
        val changed = state.copy(folderGroups = state.folderGroups.map { it.copy(deletedAtMillis = 21) })
        assertEquals(LibraryTrashRejection.SCOPE_CHANGED, LibraryTrashRules.planEmpty(changed, "local", 8, scope).rejection)
        assertEquals(LibraryTrashRejection.SCOPE_CHANGED,
            LibraryTrashRules.planEmpty(state, "local", 8, scope.copy(folderGroups = emptyList())).rejection)
    }

    @Test fun malformedGroupsAndActiveIdentityCollisionsRefuseEveryTrashEntry() {
        val state = apply(original, assertNotNull(recycle().patch)); val group = state.folderGroups.single()
        val bad = listOf(
            state.copy(folderGroups = listOf(group, group)),
            state.copy(folderGroups = listOf(group.copy(noteIds = listOf("missing")))),
            state.copy(folderGroups = listOf(group.copy(noteIds = listOf("a", "a")))),
            state.copy(folderGroups = listOf(group.copy(noteIds = listOf("a")))),
            state.copy(folderGroups = emptyList()),
            state.copy(folderGroups = listOf(group.copy(deletedAtMillis = 21))),
            state.copy(folderGroups = listOf(group.copy(folders = group.folders + LibraryFolder("orphan", "坏", null)))),
            state.copy(active = state.active.copy(folders = state.active.folders + LibraryFolder("root", "冲突", null)))
        )
        for (s in bad) {
            assertNull(LibraryFolderTrashRules.restore(s, "local", 8, "root").patch)
            assertNull(LibraryFolderTrashRules.recycle(s, "local", 8, "other", 30).patch)
            assertEquals(LibraryTrashRejection.INVALID_TRASH, LibraryTrashRules.inspectEmpty(s, "local", 8).rejection)
            assertEquals(LibraryTrashRejection.INVALID_TRASH, LibraryTrashRules.restore(s, "local", 8, "old").rejection)
            assertNull(LibraryBatchRules.recycle(s, "local", 8, listOf("outside"), 30).patch)
        }
    }

    @Test fun incompleteStaleForeignMissingAndInvalidTimeNeverProduceRows() {
        assertEquals(LibraryFolderRejection.INCOMPLETE_SNAPSHOT,
            recycle(original.copy(active = original.active.copy(complete = false))).snapshotRejection)
        assertEquals(LibraryFolderRejection.STALE_REVISION,
            LibraryFolderTrashRules.recycle(original, "local", 6, "root", 20).snapshotRejection)
        assertEquals(LibraryFolderRejection.WRONG_SPACE,
            LibraryFolderTrashRules.recycle(original, "foreign", 7, "root", 20).snapshotRejection)
        assertEquals(LibraryFolderTrashRejection.NOT_FOUND, recycle(root = "missing").rejection)
        assertEquals(LibraryFolderTrashRejection.INVALID_TIME,
            LibraryFolderTrashRules.recycle(original, "local", 7, "root", -1).rejection)
        assertEquals(LibraryFolderTrashRejection.NOT_TRASHED,
            LibraryFolderTrashRules.restore(original, "local", 7, "root").rejection)
    }

    @Test fun aggregateDirectoryCapacityIsCheckedBeforeGroupTraversal() {
        val tooLarge = LibraryTrashedFolderGroup("x", 1,
            List(LibraryFolderRules.MAX_FOLDERS) { LibraryFolder("x$it", "目录", null) }, emptyList())
        val state = original.copy(folderGroups = listOf(tooLarge))
        assertEquals(LibraryFolderRejection.CAPACITY, recycle(state).snapshotRejection)
        assertEquals(LibraryFolderRejection.CAPACITY, LibraryTrashRules.inspectEmpty(state, "local", 7).snapshotRejection)
        assertNull(LibraryFolderTrashRules.restore(state, "local", 7, "x").patch)
    }
}
