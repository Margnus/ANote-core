package dev.notecore.library

import kotlin.test.*

class LibraryFolderRulesTest {
    private fun state(folders: List<LibraryFolder> = emptyList(), notes: List<LibraryNoteLocation> = emptyList()) =
        LibraryFolderSnapshot("local", 17, folders, notes, complete = true)
    private fun plan(snapshot: LibraryFolderSnapshot, change: LibraryFolderChange) =
        LibraryFolderRules.plan(snapshot, "local", 17, change)
    private fun apply(snapshot: LibraryFolderSnapshot, change: LibraryFolderChange): LibraryFolderSnapshot {
        val patch = assertNotNull(plan(snapshot, change).patch)
        assertEquals("local", patch.spaceId); assertEquals(17, patch.expectedRevision)
        val rows = snapshot.folders.filterNot { it.id == patch.before?.id } + listOfNotNull(patch.after)
        return snapshot.copy(folders = rows)
    }
    private fun chain(count: Int, prefix: String = "f") = (1..count).map {
        LibraryFolder("$prefix$it", "第${it}级", if (it == 1) null else "$prefix${it - 1}")
    }
    private fun refused(snapshot: LibraryFolderSnapshot, change: LibraryFolderChange, reason: LibraryFolderRejection) {
        val original = snapshot.copy(folders = snapshot.folders.toList(), notes = snapshot.notes.toList())
        val result = plan(snapshot, change)
        assertNull(result.patch); assertEquals(reason, result.rejection); assertEquals(original, snapshot)
    }

    @Test fun createRenameMoveDeletePreservesNoteIdentityAndOtherRows() {
        val original = state(notes = listOf(LibraryNoteLocation("note", null)))
        var current = apply(original, LibraryFolderChange.Create("a", "旅行", null))
        current = apply(current, LibraryFolderChange.Create("b", "工作", null))
        current = apply(current, LibraryFolderChange.Rename("a", "旅行\uD83D\uDCD3"))
        current = apply(current, LibraryFolderChange.Move("a", "b"))
        assertEquals(LibraryFolder("a", "旅行\uD83D\uDCD3", "b"), current.folders.single { it.id == "a" })
        assertEquals(original.notes, current.notes)
        current = apply(current, LibraryFolderChange.DeleteEmpty("a"))
        assertEquals(listOf(LibraryFolder("b", "工作", null)), current.folders)
        assertTrue(original.folders.isEmpty())
    }

    @Test fun exactlyFiveLevelsAllowedAndSixthRejected() {
        val five = state(chain(5))
        refused(five, LibraryFolderChange.Create("six", "six", "f5"), LibraryFolderRejection.DEPTH_LIMIT)
        assertNotNull(plan(state(chain(4)), LibraryFolderChange.Create("f5", "fifth", "f4")).patch)
        assertNotNull(plan(five, LibraryFolderChange.Rename("f5", "still five")).patch)
    }

    @Test fun subtreeMoveChecksDeepestDescendantAndMoveBackToRootWorks() {
        val snapshot = state(chain(3, "a") + chain(3, "b"))
        refused(snapshot, LibraryFolderChange.Move("a1", "b3"), LibraryFolderRejection.DEPTH_LIMIT)
        val moved = apply(snapshot, LibraryFolderChange.Move("a1", "b2"))
        assertEquals("a2", moved.folders.single { it.id == "a3" }.parentId)
        assertNotNull(plan(moved, LibraryFolderChange.Move("a1", null)).patch)
    }

    @Test fun selfParentAndDescendantParentCyclesRefused() {
        val snapshot = state(chain(5))
        refused(snapshot, LibraryFolderChange.Move("f1", "f1"), LibraryFolderRejection.CYCLE)
        refused(snapshot, LibraryFolderChange.Move("f1", "f5"), LibraryFolderRejection.CYCLE)
        refused(snapshot, LibraryFolderChange.Create("new", "self", "new"), LibraryFolderRejection.CYCLE)
    }

    @Test fun incompleteWrongSpaceAndStaleSnapshotsNeverProducePatch() {
        val snapshot = state(chain(1))
        val change = LibraryFolderChange.DeleteEmpty("f1")
        refused(snapshot.copy(complete = false), change, LibraryFolderRejection.INCOMPLETE_SNAPSHOT)
        assertEquals(LibraryFolderRejection.WRONG_SPACE, LibraryFolderRules.plan(snapshot, "other", 17, change).rejection)
        assertEquals(LibraryFolderRejection.STALE_REVISION, LibraryFolderRules.plan(snapshot, "local", 16, change).rejection)
        assertEquals(LibraryFolderRejection.STALE_REVISION, LibraryFolderRules.plan(snapshot.copy(revision = -1), "local", -1, change).rejection)
    }

    @Test fun nonemptyFolderCannotDeleteChildrenOrNotesImplicitly() {
        refused(state(chain(2)), LibraryFolderChange.DeleteEmpty("f1"), LibraryFolderRejection.NOT_EMPTY)
        refused(state(chain(1), listOf(LibraryNoteLocation("note", "f1"))), LibraryFolderChange.DeleteEmpty("f1"), LibraryFolderRejection.NOT_EMPTY)
        assertNotNull(plan(state(chain(1), listOf(LibraryNoteLocation("note", null))), LibraryFolderChange.DeleteEmpty("f1")).patch)
    }

    @Test fun corruptGraphRefusedEvenWhenRequestedBranchLooksValid() {
        for (snapshot in listOf(
            state(listOf(LibraryFolder("a", "a", "missing"))),
            state(listOf(LibraryFolder("a", "a", "b"), LibraryFolder("b", "b", "a"))),
            state(chain(6)), state(chain(1) + chain(1)),
            state(chain(1), listOf(LibraryNoteLocation("note", "missing"))),
            state(chain(1), listOf(LibraryNoteLocation("note", null), LibraryNoteLocation("note", null))),
        )) refused(snapshot, LibraryFolderChange.Create("unrelated", "valid", null), LibraryFolderRejection.INVALID_GRAPH)
    }

    @Test fun unicodeTitlesArePreservedButMalformedAndBlankTitlesRefused() {
        val snapshot = state(chain(1))
        for (title in listOf("", "  \n", "x".repeat(121), "\uD800", "\uDC00"))
            refused(snapshot, LibraryFolderChange.Rename("f1", title), LibraryFolderRejection.INVALID_TITLE)
        for (title in listOf("x".repeat(120), "  同名  ", "e\u0301", "\uD83D\uDCD3"))
            assertEquals(title, plan(snapshot, LibraryFolderChange.Rename("f1", title)).patch?.after?.title)
        assertNotNull(plan(snapshot, LibraryFolderChange.Create("other", "第1级", null)).patch)
    }

    @Test fun missingIdsParentsAndReusedIdsHaveExplicitFailures() {
        val snapshot = state(chain(1))
        refused(snapshot, LibraryFolderChange.Create("f1", "duplicate", null), LibraryFolderRejection.ID_EXISTS)
        refused(snapshot, LibraryFolderChange.Move("absent", null), LibraryFolderRejection.NOT_FOUND)
        refused(snapshot, LibraryFolderChange.Move("f1", "absent"), LibraryFolderRejection.PARENT_NOT_FOUND)
        refused(snapshot, LibraryFolderChange.Create("", "valid", null), LibraryFolderRejection.INVALID_ID)
    }

    @Test fun boundedPlannerRejectsOversizedSnapshotAndCreationAtCapacity() {
        val full = state((0 until LibraryFolderRules.MAX_FOLDERS).map { LibraryFolder("id$it", "folder", null) })
        refused(full, LibraryFolderChange.Create("extra", "extra", null), LibraryFolderRejection.CAPACITY)
        assertNotNull(plan(full, LibraryFolderChange.Rename("id0", "renamed")).patch)
        refused(full.copy(folders = full.folders + LibraryFolder("extra", "extra", null)),
            LibraryFolderChange.DeleteEmpty("id0"), LibraryFolderRejection.CAPACITY)
    }
}
