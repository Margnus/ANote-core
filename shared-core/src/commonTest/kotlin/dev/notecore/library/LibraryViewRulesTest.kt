package dev.notecore.library

import kotlin.test.*

class LibraryViewRulesTest {
    private val preference = LibraryViewPreference(LibrarySort.NAME, LibraryLayout.LIST)
    private val notes = listOf(
        LibraryViewNote("z", "相同", Long.MAX_VALUE, 3, false),
        LibraryViewNote("b", "相同", 5, 8, true),
        LibraryViewNote("a", "相同", 5, 8, true),
        LibraryViewNote("n", "A", 0, null, false))
    private fun snapshot(sort: LibrarySort = LibrarySort.NAME) =
        LibraryViewSnapshot("local", 12, true, notes, preference.copy(sort = sort))
    private fun project(s: LibraryViewSnapshot = snapshot(), favorite: Boolean = false,
                        anchor: LibraryViewAnchor? = null) = LibraryViewRules.project(s, "local", 12, favorite, anchor)
    private fun plan(change: LibraryViewChange, s: LibraryViewSnapshot = snapshot()) =
        LibraryViewRules.plan(s, "local", 12, change)

    @Test fun stableSortKeysDoNotDependOnEnumerationOrder() {
        for ((sort, expected) in listOf(
            LibrarySort.NAME to listOf("n", "a", "b", "z"),
            LibrarySort.UPDATED to listOf("z", "a", "b", "n"),
            LibrarySort.RECENT to listOf("a", "b", "z"))) {
            val s = snapshot(sort)
            assertEquals(expected, project(s).noteIds)
            assertEquals(expected, project(s.copy(notes = notes.reversed())).noteIds)
        }
        assertEquals(notes, snapshot().notes)
    }

    @Test fun favoritesCanBeRemovedWithoutChangingContentOrSortKeys() {
        assertEquals(listOf("a", "b"), project(favorite = true).noteIds)
        val patch = assertNotNull(plan(LibraryViewChange.Favorite("a", false)).patch)
        assertEquals(notes[2], patch.beforeNote)
        assertEquals(notes[2].copy(favorite = false), patch.afterNote)
        assertEquals(patch.beforePreference, patch.afterPreference)
        val changed = snapshot().copy(notes = notes.map { if (it.noteId == "a") patch.afterNote!! else it })
        assertEquals(listOf("b"), project(changed, true).noteIds)
        val repeated = assertNotNull(plan(LibraryViewChange.Favorite("a", false), changed).patch)
        assertEquals(repeated.beforeNote, repeated.afterNote)
    }

    @Test fun openedTimestampDoesNotRegressOrChangeModificationTime() {
        for ((id, at, expected) in listOf(Triple("n", 0L, 0L), Triple("a", 2L, 8L), Triple("a", Long.MAX_VALUE, Long.MAX_VALUE))) {
            val patch = assertNotNull(plan(LibraryViewChange.Opened(id, at)).patch)
            assertEquals(patch.beforeNote!!.copy(openedAtMillis = expected), patch.afterNote)
        }
        assertEquals(LibraryViewRejection.INVALID_TIME, plan(LibraryViewChange.Opened("a", -1)).rejection)
    }

    @Test fun refreshKeepsIdentityAndOffsetAcrossReorderAndFavoriteFilter() {
        val anchor = LibraryViewAnchor("b", 2, 37)
        val recent = snapshot(LibrarySort.RECENT)
        assertEquals(LibraryViewAnchor("b", 1, 37), project(recent, anchor = anchor).anchor)
        val removedOther = recent.copy(notes = notes.filterNot { it.noteId == "a" })
        assertEquals(LibraryViewAnchor("b", 0, 37), project(removedOther, true, anchor).anchor)
    }

    @Test fun missingAnchorUsesNearestIndexAndEmptyProjectionHasNoAnchor() {
        assertEquals(LibraryViewAnchor("b", 2, 0), project(anchor = LibraryViewAnchor("deleted", 2, 44)).anchor)
        assertEquals(LibraryViewAnchor("z", 3, 0), project(anchor = LibraryViewAnchor("deleted", Int.MAX_VALUE, 44)).anchor)
        assertNull(project(snapshot().copy(notes = emptyList()), anchor = LibraryViewAnchor("deleted", 2, 44)).anchor)
        assertNull(project().anchor)
    }

    @Test fun preferencePatchContainsBothBeforeAndAfterAndNoNoteMutation() {
        val target = LibraryViewPreference(LibrarySort.UPDATED, LibraryLayout.GRID)
        val patch = assertNotNull(plan(LibraryViewChange.Preference(target)).patch)
        assertEquals("local", patch.spaceId); assertEquals(12, patch.expectedRevision)
        assertEquals(preference, patch.beforePreference); assertEquals(target, patch.afterPreference)
        assertNull(patch.beforeNote); assertNull(patch.afterNote)
        assertEquals(preference, snapshot().preference)
    }

    @Test fun invalidScopeAndIncompleteEnumerationRejectProjectionAndMutation() {
        for ((s, reason) in listOf(
            snapshot().copy(spaceId = "another") to LibraryViewRejection.WRONG_SPACE,
            snapshot().copy(revision = 13) to LibraryViewRejection.STALE_REVISION,
            snapshot().copy(complete = false) to LibraryViewRejection.INCOMPLETE_SNAPSHOT)) {
            assertEquals(reason, project(s).rejection)
            val result = plan(LibraryViewChange.Preference(preference), s)
            assertEquals(reason, result.rejection); assertNull(result.patch)
            assertTrue(project(s).noteIds.isEmpty())
        }
    }

    @Test fun invalidMetadataCannotSilentlyHideRows() {
        for (rows in listOf(notes + notes.first(),
            notes + notes.first().copy(noteId = "bad", title = "\uD800"),
            notes + notes.first().copy(noteId = "bad", updatedAtMillis = -1),
            notes + notes.first().copy(noteId = "bad", openedAtMillis = -1))) {
            val s = snapshot().copy(notes = rows)
            assertEquals(LibraryViewRejection.INVALID_METADATA, project(s).rejection)
            assertNull(plan(LibraryViewChange.Favorite("a", true), s).patch)
        }
        assertEquals(LibraryViewRejection.NOT_FOUND, plan(LibraryViewChange.Favorite("missing", true)).rejection)
        assertEquals(LibraryViewRejection.INVALID_ANCHOR, project(anchor = LibraryViewAnchor("a", -1, 0)).rejection)
        assertEquals(LibraryViewRejection.INVALID_ANCHOR, project(anchor = LibraryViewAnchor("a", 0, -1)).rejection)
    }

    @Test fun plannerRefusesOverCapacityBeforeReturningPartialRows() {
        val s = snapshot().copy(notes = List(LibraryViewRules.MAX_NOTES + 1) { notes.first() })
        assertEquals(LibraryViewRejection.CAPACITY, project(s).rejection)
        assertEquals(LibraryViewRejection.CAPACITY, plan(LibraryViewChange.Favorite("a", true), s).rejection)
    }

    @Test fun legacyEmptyAndSupplementaryUnicodeTitlesArePreserved() {
        val rows = listOf(notes[0].copy(title = ""), notes[1].copy(title = "\uD83D\uDCD3"))
        val s = snapshot().copy(notes = rows)
        assertEquals(listOf("z", "b"), project(s).noteIds)
        val patch = assertNotNull(plan(LibraryViewChange.Favorite("z", true), s).patch)
        assertEquals("", patch.afterNote!!.title)
        assertEquals(rows, s.notes)
    }
}
