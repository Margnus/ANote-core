package dev.notecore.library

import dev.notecore.document.hasValidUnicode

internal data class LibraryPageQuery(
    val operation: LibrarySearchQuery,
    val sort: LibrarySort,
    val favoritesOnly: Boolean,
)
internal data class LibraryPageCursor(val query: LibraryPageQuery, val anchor: LibraryViewNote)
internal data class LibraryPage(
    val query: LibraryPageQuery,
    val snapshot: LibraryViewSnapshot,
    val cursor: LibraryPageCursor?,
    val limit: Int,
    val rows: List<LibraryViewNote>,
    val nextCursor: LibraryPageCursor?,
    val startAnchor: LibraryPageCursor? = null,
)
internal enum class LibraryPageFailure {
    INVALID_QUERY, WRONG_QUERY_CURSOR, INVALID_CURSOR, STALE_QUERY, CANCELLED, STALE_RESULT,
}
internal data class LibraryPageResult(
    val page: LibraryPage? = null,
    val failure: LibraryPageFailure? = null,
    val catalogFailure: LibraryViewRejection? = null,
)

internal object LibraryPageRules {
    private fun valid(query: LibraryPageQuery, limit: Int) = !LibrarySearchRules.invalidOperation(query.operation) &&
        query.operation.input.isEmpty() && limit in 1..200

    private fun freeze(snapshot: LibraryViewSnapshot, sort: LibrarySort) = snapshot.copy(notes = snapshot.notes.toList(),
        preference = snapshot.preference.copy(sort = sort))

    private fun slice(query: LibraryPageQuery, snapshot: LibraryViewSnapshot, cursor: LibraryPageCursor?,
                      limit: Int, ordered: List<String>, start: Int): LibraryPage {
        val notes = snapshot.notes.associateBy { it.noteId }
        val selected = ordered.drop(start).take(limit + 1)
        val rows = selected.take(limit).map { notes.getValue(it) }
        val next = if (selected.size > limit) LibraryPageCursor(query, rows.last()) else null
        return LibraryPage(query, snapshot, cursor, limit, rows, next)
    }

    fun first(snapshot: LibraryViewSnapshot, query: LibraryPageQuery, limit: Int): LibraryPageResult {
        if (!valid(query, limit)) return LibraryPageResult(failure = LibraryPageFailure.INVALID_QUERY)
        val frozen = freeze(snapshot, query.sort)
        val projection = LibraryViewRules.project(frozen, query.operation.spaceId,
            query.operation.expectedRevision, query.favoritesOnly)
        projection.rejection?.let { return LibraryPageResult(catalogFailure = it) }
        return LibraryPageResult(slice(query, frozen, null, limit, projection.noteIds, 0))
    }

    fun next(snapshot: LibraryViewSnapshot, query: LibraryPageQuery,
             cursor: LibraryPageCursor, limit: Int): LibraryPageResult {
        if (!valid(query, limit)) return LibraryPageResult(failure = LibraryPageFailure.INVALID_QUERY)
        if (query != cursor.query) return LibraryPageResult(failure = LibraryPageFailure.WRONG_QUERY_CURSOR)
        val frozen = freeze(snapshot, query.sort)
        val projection = LibraryViewRules.project(frozen, query.operation.spaceId,
            query.operation.expectedRevision, query.favoritesOnly)
        projection.rejection?.let { return LibraryPageResult(catalogFailure = it) }
        val index = projection.noteIds.indexOf(cursor.anchor.noteId)
        if (index < 0) return LibraryPageResult(failure = LibraryPageFailure.INVALID_CURSOR)
        val anchor = frozen.notes.single { it.noteId == cursor.anchor.noteId }
        if (anchor != cursor.anchor) return LibraryPageResult(failure = LibraryPageFailure.INVALID_CURSOR)
        return LibraryPageResult(slice(query, frozen, cursor, limit, projection.noteIds, index + 1))
    }

    fun deliver(candidate: LibraryPageResult, active: LibrarySearchQuery?): LibraryPageResult {
        if (active == null) return LibraryPageResult(failure = LibraryPageFailure.CANCELLED)
        val page = candidate.page ?: return candidate
        if (LibrarySearchRules.invalidOperation(page.query.operation) || LibrarySearchRules.invalidOperation(active)) {
            return LibraryPageResult(failure = LibraryPageFailure.INVALID_QUERY)
        }
        if (page.query.operation.queryVersion != active.queryVersion) {
            return LibraryPageResult(failure = LibraryPageFailure.STALE_QUERY)
        }
        if (page.query.operation.operationId != active.operationId) {
            return LibraryPageResult(failure = LibraryPageFailure.CANCELLED)
        }
        if (page.query.operation != active) return LibraryPageResult(failure = LibraryPageFailure.STALE_QUERY)
        val fresh = when {
            page.cursor != null -> next(page.snapshot, page.query, page.cursor, page.limit)
            page.startAnchor != null -> relocate(page.snapshot, page.query,
                page.startAnchor.anchor.noteId, null, page.limit)
            else -> first(page.snapshot, page.query, page.limit)
        }
        return if (fresh == candidate) candidate else LibraryPageResult(failure = LibraryPageFailure.STALE_RESULT)
    }

    /** Relocates a fresh traversal at a stable noteId. A missing or now-filtered anchor falls
     * back to the explicit index (negative refused, overflow clamped to the tail, null means head). */
    fun relocate(snapshot: LibraryViewSnapshot, query: LibraryPageQuery, previousAnchorId: String,
                 fallbackIndex: Int?, limit: Int): LibraryPageResult {
        if (!valid(query, limit)) return LibraryPageResult(failure = LibraryPageFailure.INVALID_QUERY)
        if (previousAnchorId.isBlank() || previousAnchorId.length > 128 || !previousAnchorId.hasValidUnicode()) {
            return LibraryPageResult(failure = LibraryPageFailure.INVALID_CURSOR)
        }
        if (fallbackIndex != null && fallbackIndex < 0) return LibraryPageResult(failure = LibraryPageFailure.INVALID_QUERY)
        val frozen = freeze(snapshot, query.sort)
        val projection = LibraryViewRules.project(frozen, query.operation.spaceId,
            query.operation.expectedRevision, query.favoritesOnly)
        projection.rejection?.let { return LibraryPageResult(catalogFailure = it) }
        val notes = frozen.notes.associateBy { it.noteId }
        if (projection.noteIds.isEmpty()) {
            return LibraryPageResult(LibraryPage(query, frozen, null, limit, emptyList(), null))
        }
        val startIndex = projection.noteIds.indexOf(previousAnchorId)
        val start = if (startIndex >= 0) startIndex
        else (fallbackIndex ?: 0).coerceAtMost(projection.noteIds.lastIndex)
        val selected = projection.noteIds.drop(start).take(limit + 1)
        val rows = selected.take(limit).map { notes.getValue(it) }
        val next = if (selected.size > limit) LibraryPageCursor(query, rows.last()) else null
        val startAnchor = LibraryPageCursor(query, rows.first())
        return LibraryPageResult(LibraryPage(query, frozen, null, limit, rows, next, startAnchor))
    }
}
