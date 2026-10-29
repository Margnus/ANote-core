package dev.notecore.document

import dev.notecore.canvas.prototype.SceneId

internal enum class PdfRenderRole { CURRENT, PREFETCH }
internal data class PdfRenderSelection(val pageId: PageId, val sourceSha256: String, val sourceIndex: Int, val role: PdfRenderRole)
internal data class PdfRenderPlan(
    val spaceId: String, val documentId: SceneId, val documentRevision: Long, val metadataRevision: Long,
    val currentPage: PageId, val prefetchLimit: Int, val tasks: List<PdfRenderSelection>,
)
internal enum class PdfRenderSelectionFailure { INVALID_PREFETCH_LIMIT, MISSING_PAGE, STALE_PLAN }
internal data class PdfRenderSelectionResult(
    val plan: PdfRenderPlan? = null, val failure: PdfRenderSelectionFailure? = null,
    val catalogFailure: PdfPageCatalogFailure? = null,
)

/** Pure admission selection for the existing future renderer consumer; does not start or cancel work. */
internal object PdfRenderSelectionRules {
    fun plan(root: DocumentRoot, catalog: PdfPageCatalog, space: String, metadataRevision: Long,
             current: PageId, prefetchLimit: Int = 2): PdfRenderSelectionResult {
        if (prefetchLimit !in 0..2) return PdfRenderSelectionResult(failure = PdfRenderSelectionFailure.INVALID_PREFETCH_LIMIT)
        val projection = PdfPageCatalogRules.project(root, catalog, space, metadataRevision)
        projection.failure?.let { return PdfRenderSelectionResult(catalogFailure = it) }
        val index = projection.pages.indexOfFirst { it.pageId == current }
        if (index < 0) return PdfRenderSelectionResult(failure = PdfRenderSelectionFailure.MISSING_PAGE)
        val tasks = mutableListOf<PdfRenderSelection>()
        fun append(index: Int, role: PdfRenderRole): Boolean {
            val page = projection.pages.getOrNull(index) ?: return false
            val hash = page.sourceSha256 ?: return false
            tasks.add(PdfRenderSelection(page.pageId, hash, page.sourceNumber!! - 1, role))
            return true
        }
        append(index, PdfRenderRole.CURRENT)
        var remaining = prefetchLimit
        for (neighbor in listOf(index + 1, index - 1)) {
            if (remaining > 0 && append(neighbor, PdfRenderRole.PREFETCH)) remaining--
        }
        return PdfRenderSelectionResult(plan = PdfRenderPlan(space, root.id, root.revision,
            metadataRevision, current, prefetchLimit, tasks.toList()))
    }

    /** Must still be followed by original owner/token/budget checks at real dispatch and publication. */
    fun validate(root: DocumentRoot, catalog: PdfPageCatalog, space: String, current: PageId,
                 candidate: PdfRenderPlan): PdfRenderSelectionResult {
        val fresh = plan(root, catalog, space, catalog.revision, current, candidate.prefetchLimit)
        if (fresh.plan == null) return fresh
        return if (fresh.plan == candidate) fresh else PdfRenderSelectionResult(failure = PdfRenderSelectionFailure.STALE_PLAN)
    }
}
