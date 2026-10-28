package dev.notecore.library

import dev.notecore.document.hasValidUnicode

internal data class LibraryTrashNote(val noteId: String, val originalFolderId: String?, val deletedAtMillis: Long)
internal data class LibraryTrashSnapshot(val active: LibraryFolderSnapshot, val trash: List<LibraryTrashNote>)
internal enum class LibraryTrashRejection {
    INVALID_TRASH, ALREADY_TRASHED, NOT_FOUND, NOT_TRASHED, INVALID_TIME, SCOPE_CHANGED,
}
internal data class LibraryTrashPatch(
    val spaceId: String, val expectedRevision: Long,
    val beforeActive: LibraryNoteLocation?, val afterActive: LibraryNoteLocation?,
    val beforeTrash: LibraryTrashNote?, val afterTrash: LibraryTrashNote?,
)
internal data class LibraryTrashPlan(
    val patch: LibraryTrashPatch? = null,
    val rejection: LibraryTrashRejection? = null, val snapshotRejection: LibraryFolderRejection? = null,
)
/** UI must display this exact scope and obtain explicit confirmation before submitting it. */
internal data class LibraryEmptyTrashScope(val spaceId: String, val expectedRevision: Long, val rows: List<LibraryTrashNote>)
internal data class LibraryEmptyTrashPlan(
    val scope: LibraryEmptyTrashScope? = null,
    val rejection: LibraryTrashRejection? = null, val snapshotRejection: LibraryFolderRejection? = null,
)

internal object LibraryTrashRules {
    private fun validId(id: String) = id.isNotBlank() && id.length <= 128 && id.hasValidUnicode()
    fun snapshotError(s: LibraryTrashSnapshot, space: String, revision: Long): LibraryFolderRejection? {
        LibraryFolderRules.validateSnapshot(s.active, space, revision)?.let { return it }
        if (s.trash.size > LibraryFolderRules.MAX_NOTES - s.active.notes.size) return LibraryFolderRejection.CAPACITY
        return null
    }
    fun invalidTrash(s: LibraryTrashSnapshot): Boolean {
        val activeIds = s.active.notes.map { it.noteId }.toSet()
        return s.trash.map { it.noteId }.toSet().size != s.trash.size || s.trash.any {
            !validId(it.noteId) || it.noteId in activeIds || it.deletedAtMillis < 0 ||
                (it.originalFolderId != null && !validId(it.originalFolderId))
        }
    }

    fun recycle(s: LibraryTrashSnapshot, space: String, revision: Long,
                noteId: String, deletedAtMillis: Long): LibraryTrashPlan {
        snapshotError(s, space, revision)?.let { return LibraryTrashPlan(snapshotRejection = it) }
        if (invalidTrash(s)) return LibraryTrashPlan(rejection = LibraryTrashRejection.INVALID_TRASH)
        if (s.trash.any { it.noteId == noteId }) return LibraryTrashPlan(rejection = LibraryTrashRejection.ALREADY_TRASHED)
        val before = s.active.notes.singleOrNull { it.noteId == noteId }
            ?: return LibraryTrashPlan(rejection = LibraryTrashRejection.NOT_FOUND)
        if (deletedAtMillis < 0) return LibraryTrashPlan(rejection = LibraryTrashRejection.INVALID_TIME)
        return LibraryTrashPlan(patch = LibraryTrashPatch(space, revision, before, null, null,
            LibraryTrashNote(before.noteId, before.folderId, deletedAtMillis)))
    }

    fun restore(s: LibraryTrashSnapshot, space: String, revision: Long, noteId: String): LibraryTrashPlan {
        snapshotError(s, space, revision)?.let { return LibraryTrashPlan(snapshotRejection = it) }
        if (invalidTrash(s)) return LibraryTrashPlan(rejection = LibraryTrashRejection.INVALID_TRASH)
        val before = s.trash.singleOrNull { it.noteId == noteId }
            ?: return LibraryTrashPlan(rejection = LibraryTrashRejection.NOT_TRASHED)
        val folder = before.originalFolderId?.takeIf { id -> s.active.folders.any { it.id == id } }
        return LibraryTrashPlan(patch = LibraryTrashPatch(space, revision, null,
            LibraryNoteLocation(before.noteId, folder), before, null))
    }

    fun inspectEmpty(s: LibraryTrashSnapshot, space: String, revision: Long): LibraryEmptyTrashPlan {
        snapshotError(s, space, revision)?.let { return LibraryEmptyTrashPlan(snapshotRejection = it) }
        if (invalidTrash(s)) return LibraryEmptyTrashPlan(rejection = LibraryTrashRejection.INVALID_TRASH)
        return LibraryEmptyTrashPlan(scope = LibraryEmptyTrashScope(space, revision, s.trash.sortedBy { it.noteId }))
    }

    /** Removes only the enumerated metadata references; grants no file/resource deletion rights. */
    fun planEmpty(s: LibraryTrashSnapshot, space: String, revision: Long,
                  confirmed: LibraryEmptyTrashScope): LibraryEmptyTrashPlan {
        val current = inspectEmpty(s, space, revision)
        if (current.scope == null) return current
        if (confirmed != current.scope) return LibraryEmptyTrashPlan(rejection = LibraryTrashRejection.SCOPE_CHANGED)
        return current
    }
}
