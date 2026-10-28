package dev.notecore.library

import dev.notecore.document.hasValidUnicode

/** Internal metadata rules. No storage, ID allocation, content authority or deletion side effects. */
internal data class LibraryFolder(val id: String, val title: String, val parentId: String?)
internal data class LibraryNoteLocation(val noteId: String, val folderId: String?)
internal data class LibraryFolderSnapshot(
    val spaceId: String,
    val revision: Long,
    val folders: List<LibraryFolder>,
    val notes: List<LibraryNoteLocation>,
    val complete: Boolean,
)
internal sealed interface LibraryFolderChange {
    data class Create(val id: String, val title: String, val parentId: String?) : LibraryFolderChange
    data class Rename(val id: String, val title: String) : LibraryFolderChange
    data class Move(val id: String, val parentId: String?) : LibraryFolderChange
    data class DeleteEmpty(val id: String) : LibraryFolderChange
}
internal enum class LibraryFolderRejection {
    WRONG_SPACE, STALE_REVISION, INCOMPLETE_SNAPSHOT, INVALID_GRAPH, CAPACITY,
    INVALID_ID, INVALID_TITLE, ID_EXISTS, NOT_FOUND, PARENT_NOT_FOUND, CYCLE, DEPTH_LIMIT, NOT_EMPTY,
}
/** Apply this one-row patch only with the same space/revision inside the future metadata transaction.
 * Validation is not a reservation: a concurrent child/note insertion must invalidate the whole patch. */
internal data class LibraryFolderPatch(
    val spaceId: String,
    val expectedRevision: Long,
    val before: LibraryFolder?,
    val after: LibraryFolder?,
)
internal data class LibraryFolderPlan(val patch: LibraryFolderPatch? = null, val rejection: LibraryFolderRejection? = null)

internal object LibraryFolderRules {
    const val MAX_DEPTH = 5 // Library root is depth zero; the first folder is depth one.
    const val MAX_FOLDERS = 10_000 // Candidate planner admission, not a production heap budget.
    const val MAX_NOTES = 100_000
    private fun validId(id: String) = id.isNotBlank() && id.length <= 128 && id.hasValidUnicode()
    private fun validTitle(title: String) = title.isNotBlank() && title.length <= 120 && title.hasValidUnicode()

    fun plan(snapshot: LibraryFolderSnapshot, spaceId: String, expectedRevision: Long,
             change: LibraryFolderChange): LibraryFolderPlan {
        fun reject(reason: LibraryFolderRejection) = LibraryFolderPlan(rejection = reason)
        if (!validId(spaceId) || spaceId != snapshot.spaceId) return reject(LibraryFolderRejection.WRONG_SPACE)
        if (snapshot.revision < 0 || expectedRevision != snapshot.revision) return reject(LibraryFolderRejection.STALE_REVISION)
        if (!snapshot.complete) return reject(LibraryFolderRejection.INCOMPLETE_SNAPSHOT)
        if (snapshot.folders.size > MAX_FOLDERS || snapshot.notes.size > MAX_NOTES) return reject(LibraryFolderRejection.CAPACITY)
        val folders = snapshot.folders.associateBy { it.id }
        if (folders.size != snapshot.folders.size || snapshot.notes.map { it.noteId }.toSet().size != snapshot.notes.size ||
            folders.values.any { !validId(it.id) || !validTitle(it.title) || (it.parentId != null && it.parentId !in folders) } ||
            snapshot.notes.any { !validId(it.noteId) || (it.folderId != null && it.folderId !in folders) }) {
            return reject(LibraryFolderRejection.INVALID_GRAPH)
        }
        // Bounded parent walks also reject pre-existing cycles/orphans, including unrelated subtrees.
        fun depth(id: String, rows: Map<String, LibraryFolder>): Int? {
            var current: String? = id
            var count = 0
            while (current != null) {
                if (++count > MAX_DEPTH) return null
                current = (rows[current] ?: return null).parentId
            }
            return count
        }
        if (folders.keys.any { depth(it, folders) == null }) return reject(LibraryFolderRejection.INVALID_GRAPH)
        val id = when (change) {
            is LibraryFolderChange.Create -> change.id
            is LibraryFolderChange.Rename -> change.id
            is LibraryFolderChange.Move -> change.id
            is LibraryFolderChange.DeleteEmpty -> change.id
        }
        if (!validId(id)) return reject(LibraryFolderRejection.INVALID_ID)
        val before = folders[id]
        if (change is LibraryFolderChange.Create) {
            if (before != null) return reject(LibraryFolderRejection.ID_EXISTS)
            if (folders.size == MAX_FOLDERS) return reject(LibraryFolderRejection.CAPACITY)
        } else if (before == null) return reject(LibraryFolderRejection.NOT_FOUND)
        val after = when (change) {
            is LibraryFolderChange.Create -> LibraryFolder(id, change.title, change.parentId)
            is LibraryFolderChange.Rename -> before!!.copy(title = change.title)
            is LibraryFolderChange.Move -> before!!.copy(parentId = change.parentId)
            is LibraryFolderChange.DeleteEmpty -> null
        }
        if (after != null) {
            if (!validTitle(after.title)) return reject(LibraryFolderRejection.INVALID_TITLE)
            if (after.parentId != null && after.parentId !in folders && after.parentId != id) return reject(LibraryFolderRejection.PARENT_NOT_FOUND)
            var ancestor = after.parentId
            while (ancestor != null) {
                if (ancestor == id) return reject(LibraryFolderRejection.CYCLE)
                ancestor = folders.getValue(ancestor).parentId
            }
            val proposed = folders + (id to after)
            // Reparenting can overflow a descendant although the moved folder itself fits.
            if (proposed.keys.any { depth(it, proposed) == null }) return reject(LibraryFolderRejection.DEPTH_LIMIT)
        } else if (folders.values.any { it.parentId == id } || snapshot.notes.any { it.folderId == id }) {
            return reject(LibraryFolderRejection.NOT_EMPTY)
        }
        return LibraryFolderPlan(patch = LibraryFolderPatch(spaceId, expectedRevision, before, after))
    }
}
