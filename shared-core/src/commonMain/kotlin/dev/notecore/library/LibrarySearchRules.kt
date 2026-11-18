package dev.notecore.library

import dev.notecore.document.hasValidUnicode

internal data class LibrarySearchSnapshot(val directory: LibraryFolderSnapshot, val catalog: LibraryViewSnapshot)
internal enum class LibrarySearchKind { FOLDER, NOTE }
internal data class LibrarySearchTarget(
    val spaceId: String, val expectedRevision: Long, val kind: LibrarySearchKind, val id: String,
)
internal data class LibrarySearchHit(val target: LibrarySearchTarget, val title: String, val folderId: String?)
internal sealed interface LibrarySearchFailure {
    data class Directory(val reason: LibraryFolderRejection) : LibrarySearchFailure
    data class Catalog(val reason: LibraryViewRejection) : LibrarySearchFailure
    enum class Query : LibrarySearchFailure {
        IDENTITY_MISMATCH, INVALID_QUERY, RESULT_CAPACITY, NOT_FOUND,
        STALE_HIT, TRASH_SCOPE_MISMATCH, INVALID_TRASH, TRASHED,
    }
}
internal data class LibrarySearchResult(val hits: List<LibrarySearchHit> = emptyList(), val failure: LibrarySearchFailure? = null)
internal data class LibrarySearchResolution(val hit: LibrarySearchHit? = null, val failure: LibrarySearchFailure? = null)

internal object LibrarySearchRules {
    const val MAX_RESULTS = 1000
    private fun validate(s: LibrarySearchSnapshot, space: String, revision: Long): LibrarySearchFailure? {
        LibraryFolderRules.validateSnapshot(s.directory, space, revision)?.let { return LibrarySearchFailure.Directory(it) }
        LibraryViewRules.validate(s.catalog, space, revision)?.let { return LibrarySearchFailure.Catalog(it) }
        if (s.directory.notes.map { it.noteId }.toSet() != s.catalog.notes.map { it.noteId }.toSet()) {
            return LibrarySearchFailure.Query.IDENTITY_MISMATCH
        }
        return null
    }
    private fun sameDirectory(left: LibraryFolderSnapshot, right: LibraryFolderSnapshot): Boolean =
        left.spaceId == right.spaceId && left.revision == right.revision && left.complete == right.complete &&
            left.folders.toSet() == right.folders.toSet() && left.notes.toSet() == right.notes.toSet()

    // Deliberately locale-independent ASCII case folding; all other UTF-16 units stay exact.
    private fun fold(value: String) = buildString(value.length) {
        value.forEach { append(if (it in 'A'..'Z') it + 32 else it) }
    }

    fun search(s: LibrarySearchSnapshot, space: String, revision: Long, query: String): LibrarySearchResult {
        validate(s, space, revision)?.let { return LibrarySearchResult(failure = it) }
        if (query.length > 120 || !query.hasValidUnicode()) return LibrarySearchResult(failure = LibrarySearchFailure.Query.INVALID_QUERY)
        if (query.isBlank()) return LibrarySearchResult()
        val needle = fold(query)
        val locations = s.directory.notes.associateBy { it.noteId }
        val hits = mutableListOf<LibrarySearchHit>()
        for (folder in s.directory.folders) {
            if (fold(folder.title).contains(needle)) {
                if (hits.size == MAX_RESULTS) return LibrarySearchResult(failure = LibrarySearchFailure.Query.RESULT_CAPACITY)
                hits.add(LibrarySearchHit(LibrarySearchTarget(space, revision, LibrarySearchKind.FOLDER, folder.id), folder.title, folder.parentId))
            }
        }
        for (note in s.catalog.notes) {
            if (fold(note.title).contains(needle)) {
                if (hits.size == MAX_RESULTS) return LibrarySearchResult(failure = LibrarySearchFailure.Query.RESULT_CAPACITY)
                hits.add(LibrarySearchHit(LibrarySearchTarget(space, revision, LibrarySearchKind.NOTE, note.noteId),
                    note.title, locations.getValue(note.noteId).folderId))
            }
        }
        return LibrarySearchResult(hits.sortedWith(compareBy<LibrarySearchHit> { it.target.kind.ordinal }
            .thenBy { it.title }.thenBy { it.target.id }))
    }

    fun resolve(s: LibrarySearchSnapshot, target: LibrarySearchTarget): LibrarySearchResolution {
        validate(s, target.spaceId, target.expectedRevision)?.let { return LibrarySearchResolution(failure = it) }
        val hit = when (target.kind) {
            LibrarySearchKind.FOLDER -> s.directory.folders.singleOrNull { it.id == target.id }?.let {
                LibrarySearchHit(target, it.title, it.parentId)
            }
            LibrarySearchKind.NOTE -> s.catalog.notes.singleOrNull { it.noteId == target.id }?.let { note ->
                LibrarySearchHit(target, note.title, s.directory.notes.single { it.noteId == target.id }.folderId)
            }
        }
        return LibrarySearchResolution(hit, if (hit == null) LibrarySearchFailure.Query.NOT_FOUND else null)
    }

    fun navigate(s: LibrarySearchSnapshot, trash: LibraryTrashSnapshot, candidate: LibrarySearchHit): LibrarySearchResolution {
        val target = candidate.target
        validate(s, target.spaceId, target.expectedRevision)?.let { return LibrarySearchResolution(failure = it) }
        LibraryTrashRules.snapshotError(trash, target.spaceId, target.expectedRevision)?.let {
            return LibrarySearchResolution(failure = LibrarySearchFailure.Directory(it))
        }
        if (!sameDirectory(s.directory, trash.active)) {
            return LibrarySearchResolution(failure = LibrarySearchFailure.Query.TRASH_SCOPE_MISMATCH)
        }
        if (LibraryTrashRules.invalidTrash(trash)) {
            return LibrarySearchResolution(failure = LibrarySearchFailure.Query.INVALID_TRASH)
        }
        val trashed = when (target.kind) {
            LibrarySearchKind.NOTE -> trash.trash.any { it.noteId == target.id }
            LibrarySearchKind.FOLDER -> trash.folderGroups.any { group -> group.folders.any { it.id == target.id } }
        }
        if (trashed) return LibrarySearchResolution(failure = LibrarySearchFailure.Query.TRASHED)
        val resolved = resolve(s, target)
        if (resolved.hit == null) return resolved
        return if (resolved.hit == candidate) resolved else LibrarySearchResolution(failure = LibrarySearchFailure.Query.STALE_HIT)
    }
}
