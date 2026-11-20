package dev.notecore.library

import dev.notecore.document.hasValidUnicode

internal enum class LibraryOperationFailure { INVALID_OPERATION, STALE_QUERY, CANCELLED }

internal object LibraryOperationRules {
    fun invalid(query: LibrarySearchQuery) = query.operationId.isBlank() ||
        query.operationId.length > 128 || !query.operationId.hasValidUnicode() || query.queryVersion < 0

    fun deliveryFailure(candidate: LibrarySearchQuery, active: LibrarySearchQuery?): LibraryOperationFailure? {
        if (active == null) return LibraryOperationFailure.CANCELLED
        if (invalid(candidate) || invalid(active)) return LibraryOperationFailure.INVALID_OPERATION
        if (candidate.queryVersion != active.queryVersion) return LibraryOperationFailure.STALE_QUERY
        if (candidate.operationId != active.operationId) return LibraryOperationFailure.CANCELLED
        if (candidate != active) return LibraryOperationFailure.STALE_QUERY
        return null
    }

    fun cancel(active: LibrarySearchQuery, operationId: String, queryVersion: Long): LibrarySearchQuery? =
        if (active.operationId == operationId && active.queryVersion == queryVersion) null else active
}
