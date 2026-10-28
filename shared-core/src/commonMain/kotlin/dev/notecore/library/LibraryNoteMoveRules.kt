package dev.notecore.library

/** Location-only metadata patch. Never changes document identity, content revision or save target. */
internal data class LibraryNoteMovePatch(
    val spaceId: String,
    val expectedRevision: Long,
    val before: LibraryNoteLocation,
    val after: LibraryNoteLocation,
)
internal data class LibraryNoteMovePlan(
    val patch: LibraryNoteMovePatch? = null,
    val rejection: LibraryFolderRejection? = null,
)

internal object LibraryNoteMoveRules {
    fun plan(snapshot: LibraryFolderSnapshot, spaceId: String, expectedRevision: Long,
             noteId: String, destinationId: String?): LibraryNoteMovePlan {
        LibraryFolderRules.validateSnapshot(snapshot, spaceId, expectedRevision)?.let {
            return LibraryNoteMovePlan(rejection = it)
        }
        val before = snapshot.notes.singleOrNull { it.noteId == noteId }
            ?: return LibraryNoteMovePlan(rejection = LibraryFolderRejection.NOT_FOUND)
        if (destinationId != null && snapshot.folders.none { it.id == destinationId }) {
            return LibraryNoteMovePlan(rejection = LibraryFolderRejection.PARENT_NOT_FOUND)
        }
        return LibraryNoteMovePlan(patch = LibraryNoteMovePatch(
            spaceId, expectedRevision, before, before.copy(folderId = destinationId)))
    }
}
