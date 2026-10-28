package dev.notecore.library

internal enum class LibraryBatchSelectionRejection { EMPTY, DUPLICATE, CAPACITY, NOT_FOUND }
internal sealed interface LibraryBatchFailure {
    data class Selection(val reason: LibraryBatchSelectionRejection) : LibraryBatchFailure
    data class Folder(val reason: LibraryFolderRejection) : LibraryBatchFailure
    data class View(val reason: LibraryViewRejection) : LibraryBatchFailure
    data class Trash(val reason: LibraryTrashRejection) : LibraryBatchFailure
}
/** All rows must be checked and committed together against this one metadata revision. */
internal data class LibraryBatchPatch<T>(val spaceId: String, val expectedRevision: Long, val rows: List<T>)
internal data class LibraryBatchPlan<T>(val patch: LibraryBatchPatch<T>? = null, val failure: LibraryBatchFailure? = null)

internal object LibraryBatchRules {
    const val MAX_SELECTION = 1000
    private fun selection(ids: List<String>): LibraryBatchFailure? = when {
        ids.isEmpty() -> LibraryBatchFailure.Selection(LibraryBatchSelectionRejection.EMPTY)
        ids.size > MAX_SELECTION -> LibraryBatchFailure.Selection(LibraryBatchSelectionRejection.CAPACITY)
        ids.toSet().size != ids.size -> LibraryBatchFailure.Selection(LibraryBatchSelectionRejection.DUPLICATE)
        else -> null
    }
    private fun missing() = LibraryBatchFailure.Selection(LibraryBatchSelectionRejection.NOT_FOUND)

    fun move(s: LibraryFolderSnapshot, space: String, revision: Long,
             ids: List<String>, destination: String?): LibraryBatchPlan<LibraryNoteMovePatch> {
        selection(ids)?.let { return LibraryBatchPlan(failure = it) }
        LibraryFolderRules.validateSnapshot(s, space, revision)?.let {
            return LibraryBatchPlan(failure = LibraryBatchFailure.Folder(it))
        }
        val notes = s.notes.associateBy { it.noteId }
        if (ids.any { it !in notes }) return LibraryBatchPlan(failure = missing())
        if (destination != null && s.folders.none { it.id == destination }) {
            return LibraryBatchPlan(failure = LibraryBatchFailure.Folder(LibraryFolderRejection.PARENT_NOT_FOUND))
        }
        val rows = ids.map { id -> notes.getValue(id).let { before ->
            LibraryNoteMovePatch(space, revision, before, before.copy(folderId = destination))
        } }
        return LibraryBatchPlan(patch = LibraryBatchPatch(space, revision, rows))
    }

    fun favorite(s: LibraryViewSnapshot, space: String, revision: Long,
                 ids: List<String>, value: Boolean): LibraryBatchPlan<LibraryViewPatch> {
        selection(ids)?.let { return LibraryBatchPlan(failure = it) }
        LibraryViewRules.validate(s, space, revision)?.let {
            return LibraryBatchPlan(failure = LibraryBatchFailure.View(it))
        }
        val notes = s.notes.associateBy { it.noteId }
        if (ids.any { it !in notes }) return LibraryBatchPlan(failure = missing())
        val rows = ids.map { id -> notes.getValue(id).let { before ->
            LibraryViewPatch(space, revision, before, before.copy(favorite = value), s.preference, s.preference)
        } }
        return LibraryBatchPlan(patch = LibraryBatchPatch(space, revision, rows))
    }

    fun recycle(s: LibraryTrashSnapshot, space: String, revision: Long,
                ids: List<String>, deletedAtMillis: Long): LibraryBatchPlan<LibraryTrashPatch> {
        selection(ids)?.let { return LibraryBatchPlan(failure = it) }
        LibraryTrashRules.snapshotError(s, space, revision)?.let {
            return LibraryBatchPlan(failure = LibraryBatchFailure.Folder(it))
        }
        if (LibraryTrashRules.invalidTrash(s)) return LibraryBatchPlan(
            failure = LibraryBatchFailure.Trash(LibraryTrashRejection.INVALID_TRASH))
        val trashIds = s.trash.map { it.noteId }.toSet()
        if (ids.any { it in trashIds }) return LibraryBatchPlan(
            failure = LibraryBatchFailure.Trash(LibraryTrashRejection.ALREADY_TRASHED))
        val notes = s.active.notes.associateBy { it.noteId }
        if (ids.any { it !in notes }) return LibraryBatchPlan(failure = missing())
        if (deletedAtMillis < 0) return LibraryBatchPlan(failure = LibraryBatchFailure.Trash(LibraryTrashRejection.INVALID_TIME))
        val rows = ids.map { id -> notes.getValue(id).let { before ->
            LibraryTrashPatch(space, revision, before, null, null, LibraryTrashNote(before.noteId, before.folderId, deletedAtMillis))
        } }
        return LibraryBatchPlan(patch = LibraryBatchPatch(space, revision, rows))
    }
}
