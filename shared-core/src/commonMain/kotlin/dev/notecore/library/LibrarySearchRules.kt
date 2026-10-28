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
    enum class Query : LibrarySearchFailure { IDENTITY_MISMATCH, INVALID_QUERY, RESULT_CAPACITY, NOT_FOUND }
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
}
