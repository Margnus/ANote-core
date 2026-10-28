package dev.notecore.library

import dev.notecore.document.hasValidUnicode

internal enum class LibrarySort { NAME, UPDATED, RECENT }
internal enum class LibraryLayout { LIST, GRID }
internal data class LibraryViewPreference(val sort: LibrarySort, val layout: LibraryLayout)
internal data class LibraryViewNote(
    val noteId: String, val title: String, val updatedAtMillis: Long,
    val openedAtMillis: Long?, val favorite: Boolean,
)
/** An owner-stable, complete metadata enumeration, never a page of a larger query. */
internal data class LibraryViewSnapshot(
    val spaceId: String, val revision: Long, val complete: Boolean,
    val notes: List<LibraryViewNote>, val preference: LibraryViewPreference,
)
internal enum class LibraryViewRejection {
    WRONG_SPACE, STALE_REVISION, INCOMPLETE_SNAPSHOT, CAPACITY, INVALID_METADATA,
    INVALID_ANCHOR, NOT_FOUND, INVALID_TIME,
}
internal data class LibraryViewAnchor(val noteId: String, val previousIndex: Int, val offset: Int)
internal data class LibraryViewProjection(
    val noteIds: List<String> = emptyList(), val anchor: LibraryViewAnchor? = null,
    val rejection: LibraryViewRejection? = null,
)
internal sealed interface LibraryViewChange {
    data class Favorite(val noteId: String, val value: Boolean) : LibraryViewChange
    /** Caller reports a successful open; failed/cancelled open must not issue this change. */
    data class Opened(val noteId: String, val atMillis: Long) : LibraryViewChange
    data class Preference(val value: LibraryViewPreference) : LibraryViewChange
}
internal data class LibraryViewPatch(
    val spaceId: String, val expectedRevision: Long,
    val beforeNote: LibraryViewNote?, val afterNote: LibraryViewNote?,
    val beforePreference: LibraryViewPreference, val afterPreference: LibraryViewPreference,
)
internal data class LibraryViewPlan(val patch: LibraryViewPatch? = null, val rejection: LibraryViewRejection? = null)

internal object LibraryViewRules {
    const val MAX_NOTES = 100_000 // Planner admission only, not database pagination or heap acceptance.
    private fun validId(id: String) = id.isNotBlank() && id.length <= 128 && id.hasValidUnicode()
    fun validate(s: LibraryViewSnapshot, spaceId: String, revision: Long): LibraryViewRejection? {
        if (!validId(spaceId) || s.spaceId != spaceId) return LibraryViewRejection.WRONG_SPACE
        if (s.revision < 0 || s.revision != revision) return LibraryViewRejection.STALE_REVISION
        if (!s.complete) return LibraryViewRejection.INCOMPLETE_SNAPSHOT
        if (s.notes.size > MAX_NOTES) return LibraryViewRejection.CAPACITY
        if (s.notes.map { it.noteId }.toSet().size != s.notes.size || s.notes.any {
            !validId(it.noteId) || it.title.length > 120 || !it.title.hasValidUnicode() ||
                it.updatedAtMillis < 0 || (it.openedAtMillis != null && it.openedAtMillis < 0)
        }) return LibraryViewRejection.INVALID_METADATA
        return null
    }

    fun project(s: LibraryViewSnapshot, spaceId: String, revision: Long,
                favoritesOnly: Boolean = false, anchor: LibraryViewAnchor? = null): LibraryViewProjection {
        validate(s, spaceId, revision)?.let { return LibraryViewProjection(rejection = it) }
        if (anchor != null && (!validId(anchor.noteId) || anchor.previousIndex < 0 || anchor.offset < 0)) {
            return LibraryViewProjection(rejection = LibraryViewRejection.INVALID_ANCHOR)
        }
        val comparator = when (s.preference.sort) {
            LibrarySort.NAME -> compareBy<LibraryViewNote> { it.title }.thenBy { it.noteId }
            LibrarySort.UPDATED -> compareByDescending<LibraryViewNote> { it.updatedAtMillis }.thenBy { it.noteId }
            LibrarySort.RECENT -> compareByDescending<LibraryViewNote> { it.openedAtMillis }.thenBy { it.noteId }
        }
        val ids = s.notes.asSequence().filter { !favoritesOnly || it.favorite }
            .filter { s.preference.sort != LibrarySort.RECENT || it.openedAtMillis != null }
            .sortedWith(comparator).map { it.noteId }.toList()
        val relocated = anchor?.let {
            val index = ids.indexOf(it.noteId)
            when {
                index >= 0 -> it.copy(previousIndex = index)
                ids.isEmpty() -> null
                else -> it.previousIndex.coerceAtMost(ids.lastIndex).let { fallback ->
                    LibraryViewAnchor(ids[fallback], fallback, 0)
                }
            }
        }
        return LibraryViewProjection(ids, relocated)
    }

    fun plan(s: LibraryViewSnapshot, spaceId: String, revision: Long, change: LibraryViewChange): LibraryViewPlan {
        validate(s, spaceId, revision)?.let { return LibraryViewPlan(rejection = it) }
        if (change is LibraryViewChange.Preference) return LibraryViewPlan(patch = LibraryViewPatch(
            spaceId, revision, null, null, s.preference, change.value))
        val id = when (change) {
            is LibraryViewChange.Favorite -> change.noteId
            is LibraryViewChange.Opened -> change.noteId
            is LibraryViewChange.Preference -> error("Handled above")
        }
        val before = s.notes.singleOrNull { it.noteId == id }
            ?: return LibraryViewPlan(rejection = LibraryViewRejection.NOT_FOUND)
        val after = when (change) {
            is LibraryViewChange.Favorite -> before.copy(favorite = change.value)
            is LibraryViewChange.Opened -> {
                if (change.atMillis < 0) return LibraryViewPlan(rejection = LibraryViewRejection.INVALID_TIME)
                before.copy(openedAtMillis = maxOf(before.openedAtMillis ?: 0, change.atMillis))
            }
            is LibraryViewChange.Preference -> error("Handled above")
        }
        return LibraryViewPlan(patch = LibraryViewPatch(spaceId, revision, before, after, s.preference, s.preference))
    }
}
