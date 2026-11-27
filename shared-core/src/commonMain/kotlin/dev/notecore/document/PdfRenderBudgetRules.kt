package dev.notecore.document

/** Intended dimensions of a packed ARGB raster for a selected document page. */
internal data class PdfRenderRasterRequest(val pageId: PageId, val width: Int, val height: Int)

internal data class BudgetedPdfRender(
    val task: PdfRenderSelection,
    val raster: PdfRenderRasterRequest,
    val packedBytes: Long,
)

internal enum class PdfRenderBudgetFailure { INVALID_BUDGET, INVALID_RASTERS, CURRENT_PAGE_EXCEEDS_BUDGET }

internal sealed interface PdfRenderBudgetResult {
    data class SelectionRejected(val result: PdfRenderSelectionResult) : PdfRenderBudgetResult
    data class Rejected(val reason: PdfRenderBudgetFailure) : PdfRenderBudgetResult
    data class Admitted(
        val selection: PdfRenderPlan,
        val tasks: List<BudgetedPdfRender>,
        val deferredPrefetch: List<PdfRenderSelection>,
        val packedBytes: Long,
    ) : PdfRenderBudgetResult
}

/**
 * Plans packed raster payloads against the available budget reported by the caller.
 * The renderer owner must reserve actual resources before dispatch and check publication eligibility.
 */
internal object PdfRenderBudgetRules {
    private const val ARGB_BYTES_PER_PIXEL = 4L

    fun admit(
        root: DocumentRoot,
        catalog: PdfPageCatalog,
        space: String,
        currentPage: PageId,
        candidate: PdfRenderPlan,
        rasters: List<PdfRenderRasterRequest>,
        availableBytes: Long,
    ): PdfRenderBudgetResult {
        val checked = PdfRenderSelectionRules.validate(root, catalog, space, currentPage, candidate)
        val selection = checked.plan ?: return PdfRenderBudgetResult.SelectionRejected(checked)
        if (availableBytes < 0) return PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.INVALID_BUDGET)
        if (rasters.size != selection.tasks.size) {
            return PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.INVALID_RASTERS)
        }
        val rasterByPage = rasters.associateBy { it.pageId }
        if (selection.tasks.any { it.pageId !in rasterByPage } ||
            rasters.any { it.width <= 0 || it.height <= 0 }
        ) {
            return PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.INVALID_RASTERS)
        }

        val admitted = mutableListOf<BudgetedPdfRender>()
        val deferred = mutableListOf<PdfRenderSelection>()
        var remainingBytes = availableBytes
        for (task in selection.tasks) {
            val raster = rasterByPage.getValue(task.pageId)
            val pixels = raster.width.toLong() * raster.height
            // Compare before multiplying so oversized rasters cannot wrap to a small byte count.
            if (pixels > remainingBytes / ARGB_BYTES_PER_PIXEL) {
                if (task.role == PdfRenderRole.CURRENT) {
                    return PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.CURRENT_PAGE_EXCEEDS_BUDGET)
                }
                deferred.add(task)
                continue
            }
            val bytes = pixels * ARGB_BYTES_PER_PIXEL
            admitted.add(BudgetedPdfRender(task, raster, bytes))
            remainingBytes -= bytes
        }
        return PdfRenderBudgetResult.Admitted(
            selection, admitted.toList(), deferred.toList(), availableBytes - remainingBytes,
        )
    }
}
