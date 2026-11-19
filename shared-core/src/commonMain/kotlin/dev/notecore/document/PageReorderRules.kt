package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import dev.notecore.canvas.prototype.SceneId

internal enum class PageDropSide { BEFORE, AFTER }

internal sealed interface PageReorderResult {
    data class Planned(val command: DocumentCommand.MovePage) : PageReorderResult
    data class Rejected(val reason: DocumentRejection) : PageReorderResult
}

internal object PageReorderRules {
    fun plan(
        root: DocumentRoot,
        documentId: SceneId,
        baseRevision: Long,
        operationId: CommandId,
        source: PageId,
        target: PageId,
        side: PageDropSide,
    ): PageReorderResult {
        if (documentId != root.id) return PageReorderResult.Rejected(DocumentRejection.WRONG_DOCUMENT)
        if (baseRevision != root.revision) return PageReorderResult.Rejected(DocumentRejection.STALE_ROOT)
        if (!operationId.value.hasValidUnicode() || !source.value.hasValidUnicode() || !target.value.hasValidUnicode()) {
            return PageReorderResult.Rejected(DocumentRejection.INVALID_IDENTITY)
        }
        var sourceIndex = -1
        var targetIndex = -1
        for (index in 0 until root.pageCount) {
            val pageId = root.page(index).id
            if (pageId == source) sourceIndex = index
            if (pageId == target) targetIndex = index
            if (sourceIndex >= 0 && targetIndex >= 0) break
        }
        if (sourceIndex < 0 || targetIndex < 0) return PageReorderResult.Rejected(DocumentRejection.MISSING_PAGE)
        val insertionIndex = if (side == PageDropSide.BEFORE) targetIndex else targetIndex + 1
        if (insertionIndex == sourceIndex || insertionIndex == sourceIndex + 1) {
            return PageReorderResult.Rejected(DocumentRejection.NO_CHANGE)
        }
        return PageReorderResult.Planned(
            DocumentCommand.MovePage(operationId, documentId, baseRevision, source, root.pageAt(insertionIndex)?.id),
        )
    }
}
