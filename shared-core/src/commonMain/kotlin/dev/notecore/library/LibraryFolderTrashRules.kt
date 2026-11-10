package dev.notecore.library

import dev.notecore.document.hasValidUnicode

/** One deleted subtree; notes remain in the original trash table, identified here without copying content. */
internal data class LibraryTrashedFolderGroup(
    val rootId: String, val deletedAtMillis: Long, val folders: List<LibraryFolder>, val noteIds: List<String>,
)
internal enum class LibraryFolderTrashRejection { NOT_FOUND, NOT_TRASHED, INVALID_TIME, RESTORE_CONFLICT, DEPTH_LIMIT }
/** Every directory, note and group row belongs to one future space/revision transaction. No IO receipt. */
internal data class LibraryFolderTrashPatch(
    val spaceId: String, val expectedRevision: Long,
    val folders: List<LibraryFolderPatch>, val notes: List<LibraryTrashPatch>,
    val beforeGroup: LibraryTrashedFolderGroup?, val afterGroup: LibraryTrashedFolderGroup?,
)
internal data class LibraryFolderTrashPlan(
    val patch: LibraryFolderTrashPatch? = null, val rejection: LibraryFolderTrashRejection? = null,
    val snapshotRejection: LibraryFolderRejection? = null, val trashRejection: LibraryTrashRejection? = null,
)

internal object LibraryFolderTrashRules {
    /** Called only after the original aggregate capacity admission. Never recurses into trash validation. */
    internal fun invalidGroups(s: LibraryTrashSnapshot): Boolean {
        val folderIds = s.active.folders.map { it.id }.toMutableSet()
        val groupedNotes = mutableSetOf<String>()
        val trash = s.trash.associateBy { it.noteId }
        for (group in s.folderGroups) {
            if (group.deletedAtMillis < 0 || group.folders.isEmpty() || group.noteIds.size > LibraryFolderRules.MAX_NOTES) return true
            val root = group.folders.singleOrNull { it.id == group.rootId } ?: return true
            if (root.parentId != null && (root.parentId.isBlank() || root.parentId.length > 128 || !root.parentId.hasValidUnicode())) return true
            val ids = group.folders.map { it.id }.toSet()
            if (root.parentId in ids || group.folders.any { it.id != root.id && it.parentId !in ids }) return true
            // Root's historical parent may have disappeared. Normalize only for structural validation.
            val normalized = group.folders.map { if (it.id == root.id) it.copy(parentId = null) else it }
            val shape = LibraryFolderSnapshot(s.active.spaceId, s.active.revision, normalized, emptyList(), true)
            if (LibraryFolderRules.validateSnapshot(shape, shape.spaceId, shape.revision) != null) return true
            if (group.folders.any { !folderIds.add(it.id) }) return true
            if (group.noteIds.any { id ->
                val note = trash[id]
                !groupedNotes.add(id) || note == null || note.folderGroupId != group.rootId ||
                    note.originalFolderId !in ids || note.deletedAtMillis != group.deletedAtMillis
            }) return true
        }
        return s.trash.any { it.folderGroupId != null && it.noteId !in groupedNotes }
    }

    private fun invalid(s: LibraryTrashSnapshot, space: String, revision: Long): LibraryFolderTrashPlan? {
        LibraryTrashRules.snapshotError(s, space, revision)?.let { return LibraryFolderTrashPlan(snapshotRejection = it) }
        if (LibraryTrashRules.invalidTrash(s)) return LibraryFolderTrashPlan(trashRejection = LibraryTrashRejection.INVALID_TRASH)
        return null
    }

    fun recycle(s: LibraryTrashSnapshot, space: String, revision: Long, rootId: String,
                deletedAtMillis: Long): LibraryFolderTrashPlan {
        invalid(s, space, revision)?.let { return it }
        if (s.active.folders.none { it.id == rootId }) return LibraryFolderTrashPlan(rejection = LibraryFolderTrashRejection.NOT_FOUND)
        if (deletedAtMillis < 0) return LibraryFolderTrashPlan(rejection = LibraryFolderTrashRejection.INVALID_TIME)
        val children = s.active.folders.groupBy { it.parentId }
        val ids = linkedSetOf(rootId)
        var frontier = listOf(rootId)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { children[it].orEmpty().map { child -> child.id } }
            ids.addAll(frontier)
        }
        val folders = s.active.folders.filter { it.id in ids }
        val notes = s.active.notes.filter { it.folderId in ids }
        val group = LibraryTrashedFolderGroup(rootId, deletedAtMillis, folders.toList(), notes.map { it.noteId })
        return LibraryFolderTrashPlan(patch = LibraryFolderTrashPatch(space, revision,
            folders.map { LibraryFolderPatch(space, revision, it, null) },
            notes.map { LibraryTrashPatch(space, revision, it, null, null,
                LibraryTrashNote(it.noteId, it.folderId, deletedAtMillis, rootId)) }, null, group))
    }

    fun restore(s: LibraryTrashSnapshot, space: String, revision: Long, rootId: String): LibraryFolderTrashPlan {
        invalid(s, space, revision)?.let { return it }
        val group = s.folderGroups.singleOrNull { it.rootId == rootId }
            ?: return LibraryFolderTrashPlan(rejection = LibraryFolderTrashRejection.NOT_TRASHED)
        val activeIds = s.active.folders.map { it.id }.toSet()
        val restored = group.folders.map { folder ->
            if (folder.id == rootId && folder.parentId !in activeIds) folder.copy(parentId = null) else folder
        }
        val trash = s.trash.associateBy { it.noteId }
        val notes = group.noteIds.map { trash.getValue(it) }
        val proposed = s.active.copy(folders = s.active.folders + restored,
            notes = s.active.notes + notes.map { LibraryNoteLocation(it.noteId, it.originalFolderId) })
        val error = LibraryFolderRules.validateSnapshot(proposed, space, revision)
        if (error != null) return LibraryFolderTrashPlan(rejection =
            if (error == LibraryFolderRejection.INVALID_GRAPH) LibraryFolderTrashRejection.DEPTH_LIMIT
            else LibraryFolderTrashRejection.RESTORE_CONFLICT)
        return LibraryFolderTrashPlan(patch = LibraryFolderTrashPatch(space, revision,
            restored.map { LibraryFolderPatch(space, revision, null, it) },
            notes.map { LibraryTrashPatch(space, revision, null, LibraryNoteLocation(it.noteId, it.originalFolderId), it, null) },
            group.copy(folders = group.folders.toList(), noteIds = group.noteIds.toList()), null))
    }
}
