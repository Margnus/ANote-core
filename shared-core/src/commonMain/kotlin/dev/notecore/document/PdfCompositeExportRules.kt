package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import kotlin.math.ceil

internal enum class PdfCompositeTarget { PDF_DOCUMENT, PAGE_IMAGES }
internal data class PdfPageRange(val first: Int, val last: Int)
internal data class PdfCompositeExportRequest(
    val operationId: CommandId,
    val target: PdfCompositeTarget,
    val ranges: List<PdfPageRange>,
    val pixelsPerPageUnit: Double,
    val maxPagePixels: Long,
)
internal data class PdfCompositeExportDisclosure(
    val editableObjects: Boolean = false,
    val searchableText: Boolean = false,
    val sourceReadOnly: Boolean = true,
)
internal data class PdfCompositePageTask(
    val outputNumber: Int,
    val displayNumber: Int,
    val page: PageDescriptor,
    val background: PdfAnnotationGeometry?,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val argbByteUpperBound: Long,
)
internal data class PdfCompositeExportPlan(
    val root: DocumentRoot,
    val catalog: PdfPageCatalog,
    val request: PdfCompositeExportRequest,
    val disclosure: PdfCompositeExportDisclosure,
    val tasks: List<PdfCompositePageTask>,
) {
    val operationId: CommandId get() = request.operationId
    val documentRevision: Long get() = root.revision
    val metadataRevision: Long get() = catalog.revision
}
internal enum class PdfCompositeExportFailure {
    INVALID_OPERATION, EMPTY_SELECTION, INVALID_RANGES, RANGE_CAPACITY, PAGE_CAPACITY,
    INVALID_QUALITY, INVALID_PIXEL_BUDGET, PIXEL_CAPACITY, MISSING_SOURCE_METADATA,
    SOURCE_GEOMETRY_REJECTED, CANCELLED, STALE_PLAN,
}
internal data class PdfCompositeExportResult(
    val plan: PdfCompositeExportPlan? = null,
    val failure: PdfCompositeExportFailure? = null,
    val catalogFailure: PdfPageCatalogFailure? = null,
    val annotationFailure: PdfAnnotationGeometryFailure? = null,
    val geometryFailure: PdfPageGeometryFailure? = null,
)

internal object PdfCompositeExportRules {
    private const val MAX_EXPORT_PAGES = 4096
    private const val MAX_EXPORT_RANGES = 4096

    fun plan(
        root: DocumentRoot,
        catalog: PdfPageCatalog,
        space: String,
        metadataRevision: Long,
        request: PdfCompositeExportRequest,
        metadata: (PdfPageRef) -> PdfSourcePageGeometry?,
    ): PdfCompositeExportResult {
        if (!request.operationId.value.hasValidUnicode()) {
            return PdfCompositeExportResult(failure = PdfCompositeExportFailure.INVALID_OPERATION)
        }
        if (request.ranges.isEmpty()) return PdfCompositeExportResult(failure = PdfCompositeExportFailure.EMPTY_SELECTION)
        if (request.ranges.size > MAX_EXPORT_RANGES) {
            return PdfCompositeExportResult(failure = PdfCompositeExportFailure.RANGE_CAPACITY)
        }
        if (request.ranges.any { it.first < 1 || it.last < it.first || it.last > root.pageCount }) {
            return PdfCompositeExportResult(failure = PdfCompositeExportFailure.INVALID_RANGES)
        }
        if (!request.pixelsPerPageUnit.isFinite() || request.pixelsPerPageUnit !in 0.25..4.0) {
            return PdfCompositeExportResult(failure = PdfCompositeExportFailure.INVALID_QUALITY)
        }
        if (request.maxPagePixels <= 0 || request.maxPagePixels > Long.MAX_VALUE / 4) {
            return PdfCompositeExportResult(failure = PdfCompositeExportFailure.INVALID_PIXEL_BUDGET)
        }
        val normalized = mutableListOf<PdfPageRange>()
        for (range in request.ranges.sortedBy { it.first }) {
            val previous = normalized.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                normalized[normalized.lastIndex] = previous.copy(last = maxOf(previous.last, range.last))
            } else {
                normalized.add(range)
            }
        }
        if (normalized.sumOf { it.last - it.first + 1 } > MAX_EXPORT_PAGES) {
            return PdfCompositeExportResult(failure = PdfCompositeExportFailure.PAGE_CAPACITY)
        }
        val projection = PdfPageCatalogRules.project(root, catalog, space, metadataRevision)
        projection.failure?.let { return PdfCompositeExportResult(catalogFailure = it) }
        val references = catalog.references.associateBy { it.pageId }
        val tasks = mutableListOf<PdfCompositePageTask>()
        for (position in projection.pages) {
            if (normalized.none { position.displayNumber in it.first..it.last }) continue
            val page = root.page(position.displayNumber - 1)
            val reference = references[position.pageId]
            val background = if (reference == null) null else {
                val source = metadata(reference)
                    ?: return PdfCompositeExportResult(failure = PdfCompositeExportFailure.MISSING_SOURCE_METADATA)
                val bound = PdfAnnotationGeometryRules.bind(root, catalog, space, metadataRevision, page.id, source)
                bound.binding ?: return PdfCompositeExportResult(
                    failure = PdfCompositeExportFailure.SOURCE_GEOMETRY_REJECTED,
                    catalogFailure = bound.catalogFailure,
                    annotationFailure = bound.failure,
                    geometryFailure = bound.geometryFailure,
                )
                bound.binding
            }
            val width = ceil(page.width * request.pixelsPerPageUnit)
            val height = ceil(page.height * request.pixelsPerPageUnit)
            if (!width.isFinite() || !height.isFinite() || width < 1 || height < 1 ||
                width > Int.MAX_VALUE || height > Int.MAX_VALUE) {
                return PdfCompositeExportResult(failure = PdfCompositeExportFailure.PIXEL_CAPACITY)
            }
            val pixels = width.toLong() * height.toLong()
            if (pixels > request.maxPagePixels) {
                return PdfCompositeExportResult(failure = PdfCompositeExportFailure.PIXEL_CAPACITY)
            }
            tasks.add(PdfCompositePageTask(tasks.size + 1, position.displayNumber, page, background,
                width.toInt(), height.toInt(), pixels * 4))
        }
        if (tasks.isEmpty()) return PdfCompositeExportResult(failure = PdfCompositeExportFailure.EMPTY_SELECTION)
        val snapshotCatalog = catalog.copy(sources = catalog.sources.toList(), references = catalog.references.toList())
        return PdfCompositeExportResult(plan = PdfCompositeExportPlan(root, snapshotCatalog,
            request.copy(ranges = normalized.toList()), PdfCompositeExportDisclosure(), tasks.toList()))
    }

    fun revalidate(candidate: PdfCompositeExportPlan, activeOperation: CommandId): PdfCompositeExportResult {
        if (candidate.operationId != activeOperation) {
            return PdfCompositeExportResult(failure = PdfCompositeExportFailure.CANCELLED)
        }
        val backgrounds = candidate.tasks.mapNotNull { it.background }.associateBy { it.page.id }
        val fresh = plan(candidate.root, candidate.catalog, candidate.catalog.spaceId,
            candidate.catalog.revision, candidate.request) { reference ->
            backgrounds[reference.pageId]?.let {
                PdfSourcePageGeometry(it.source, reference.sourceIndex,
                    it.geometry.crop, it.geometry.rotation, it.geometry.userUnit)
            }
        }
        if (fresh.plan == null || fresh.plan == candidate) return fresh
        return PdfCompositeExportResult(failure = PdfCompositeExportFailure.STALE_PLAN)
    }
}
