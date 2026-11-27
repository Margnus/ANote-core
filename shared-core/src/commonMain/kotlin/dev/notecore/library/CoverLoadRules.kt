package dev.notecore.library

internal data class CoverPageMetadata(
    val noteId: String,
    val firstPageRevision: Long,
)

internal data class CoverLoadRequest(
    val targetWidth: Int,
    val targetHeight: Int,
    val scale: Double,
    val maxCoverBytes: Long,
    val maxBatchBytes: Long,
    val maxTasks: Int,
)

internal data class CoverLoadTask(
    val key: CoverCacheKey,
    val argbByteUpperBound: Long,
)

internal data class CoverLoadPlan(
    val page: LibraryPage,
    val metadata: List<CoverPageMetadata>,
    val request: CoverLoadRequest,
    val tasks: List<CoverLoadTask>,
    val totalArgbByteUpperBound: Long,
)

internal data class CoverLoadCompletion(
    val plan: CoverLoadPlan,
    val task: CoverLoadTask,
)

internal enum class CoverLoadFailure {
    INVALID_PAGE, INVALID_REQUEST, INVALID_METADATA, CACHE_REJECTED, TASK_CAPACITY, PIXEL_CAPACITY,
    CANCELLED, STALE_QUERY, STALE_REQUEST, STALE_PAGE, STALE_PLAN,
}

internal data class CoverLoadPlanResult(
    val plan: CoverLoadPlan? = null,
    val failure: CoverLoadFailure? = null,
    val cacheFailure: CoverCacheFailure? = null,
)

internal data class CoverLoadDelivery(
    val task: CoverLoadTask? = null,
    val failure: CoverLoadFailure? = null,
)

internal object CoverLoadRules {
    const val MAX_TASKS = 200

    private fun validRequest(request: CoverLoadRequest): Boolean {
        if (request.maxTasks !in 1..MAX_TASKS || request.maxCoverBytes <= 0 || request.maxBatchBytes <= 0) return false
        return CoverCacheRules.derive("cover-request", 0, request.targetWidth,
            request.targetHeight, request.scale).key != null
    }

    private fun checkedPage(page: LibraryPage, active: LibraryPageQuery): LibraryPageResult =
        LibraryPageRules.deliver(LibraryPageResult(page), active)

    private fun deliveryFailure(failure: LibraryPageFailure): CoverLoadFailure = when (failure) {
        LibraryPageFailure.CANCELLED -> CoverLoadFailure.CANCELLED
        LibraryPageFailure.STALE_QUERY -> CoverLoadFailure.STALE_QUERY
        else -> CoverLoadFailure.STALE_PAGE
    }

    fun prepare(page: LibraryPage, metadata: List<CoverPageMetadata>,
                request: CoverLoadRequest): CoverLoadPlanResult {
        if (!validRequest(request)) return CoverLoadPlanResult(failure = CoverLoadFailure.INVALID_REQUEST)
        val checked = checkedPage(page, page.query)
        if (checked.page != page) return CoverLoadPlanResult(failure = CoverLoadFailure.INVALID_PAGE)
        if (metadata.size != page.rows.size) return CoverLoadPlanResult(failure = CoverLoadFailure.INVALID_METADATA)
        val ids = metadata.map { it.noteId }
        if (ids.toSet().size != ids.size || ids.toSet() != page.rows.map { it.noteId }.toSet()) {
            return CoverLoadPlanResult(failure = CoverLoadFailure.INVALID_METADATA)
        }
        if (page.rows.size > request.maxTasks) {
            return CoverLoadPlanResult(failure = CoverLoadFailure.TASK_CAPACITY)
        }
        val byId = metadata.associateBy { it.noteId }
        val ordered = page.rows.map { byId.getValue(it.noteId) }
        val tasks = mutableListOf<CoverLoadTask>()
        var usedBytes = 0L
        for (item in ordered) {
            val derived = CoverCacheRules.derive(item.noteId, item.firstPageRevision,
                request.targetWidth, request.targetHeight, request.scale)
            val key = derived.key ?: return CoverLoadPlanResult(
                failure = CoverLoadFailure.CACHE_REJECTED, cacheFailure = derived.failure)
            val bytes = key.bucketWidth.toLong() * key.bucketHeight * 4L
            if (bytes > request.maxCoverBytes || bytes > request.maxBatchBytes - usedBytes) {
                return CoverLoadPlanResult(failure = CoverLoadFailure.PIXEL_CAPACITY)
            }
            tasks.add(CoverLoadTask(key, bytes))
            usedBytes += bytes
        }
        return CoverLoadPlanResult(plan = CoverLoadPlan(page, ordered.toList(), request, tasks.toList(), usedBytes))
    }

    fun deliver(candidate: CoverLoadCompletion, currentPage: LibraryPage?,
                activeQuery: LibraryPageQuery?, currentRequest: CoverLoadRequest): CoverLoadDelivery {
        if (currentPage == null || activeQuery == null) {
            return CoverLoadDelivery(failure = CoverLoadFailure.CANCELLED)
        }
        val active = checkedPage(currentPage, activeQuery)
        active.failure?.let { return CoverLoadDelivery(failure = deliveryFailure(it)) }
        val own = checkedPage(candidate.plan.page, activeQuery)
        own.failure?.let { return CoverLoadDelivery(failure = deliveryFailure(it)) }
        val activePage = active.page!!
        if (own.page != activePage) return CoverLoadDelivery(failure = CoverLoadFailure.STALE_PAGE)
        if (candidate.plan.request != currentRequest) {
            return CoverLoadDelivery(failure = CoverLoadFailure.STALE_REQUEST)
        }
        val fresh = prepare(activePage, candidate.plan.metadata, currentRequest)
        if (fresh.plan != candidate.plan || candidate.task !in fresh.plan.tasks) {
            return CoverLoadDelivery(failure = CoverLoadFailure.STALE_PLAN)
        }
        return CoverLoadDelivery(task = candidate.task)
    }
}
