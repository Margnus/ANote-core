package dev.notecore.document

import dev.notecore.canvas.prototype.SceneId

/** Effective raw geometry read from the identified source and physical page. */
internal data class PdfSourcePageGeometry(
    val source: PdfSourcePages,
    val sourceIndex: Int,
    val crop: PdfCropBox,
    val rotation: Int,
    val userUnit: Double,
)

internal data class PdfAnnotationGeometry(
    val spaceId: String,
    val documentId: SceneId,
    val documentRevision: Long,
    val metadataRevision: Long,
    val reference: PdfPageRef,
    val source: PdfSourcePages,
    val page: PageDescriptor,
    val geometry: PdfPageGeometry,
)

internal enum class PdfAnnotationGeometryFailure { MISSING_PAGE, SOURCE_MISMATCH, PAGE_SIZE_MISMATCH, STALE_BINDING }

internal data class PdfAnnotationGeometryResult(
    val binding: PdfAnnotationGeometry? = null,
    val failure: PdfAnnotationGeometryFailure? = null,
    val catalogFailure: PdfPageCatalogFailure? = null,
    val geometryFailure: PdfPageGeometryFailure? = null,
)

/** Binds raw PDF user space to the canonical annotation layer's top-left, y-down page space. */
internal object PdfAnnotationGeometryRules {
    fun bind(
        root: DocumentRoot, catalog: PdfPageCatalog, space: String, metadataRevision: Long,
        pageId: PageId, metadata: PdfSourcePageGeometry,
    ): PdfAnnotationGeometryResult {
        PdfPageCatalogRules.validateSource(root, catalog, space, metadataRevision, metadata.source)?.let {
            return PdfAnnotationGeometryResult(catalogFailure = it)
        }
        val page = root.findPage(pageId)
            ?: return PdfAnnotationGeometryResult(failure = PdfAnnotationGeometryFailure.MISSING_PAGE)
        val reference = catalog.references.firstOrNull { it.pageId == pageId }
            ?: return PdfAnnotationGeometryResult(catalogFailure = PdfPageCatalogFailure.NOT_SOURCE_PAGE)
        if (reference.sourceSha256 != metadata.source.sha256 || reference.sourceIndex != metadata.sourceIndex) {
            return PdfAnnotationGeometryResult(failure = PdfAnnotationGeometryFailure.SOURCE_MISMATCH)
        }
        val resolved = PdfPageGeometryRules.resolve(metadata.crop, metadata.rotation, metadata.userUnit)
        val geometry = resolved.geometry ?: return PdfAnnotationGeometryResult(geometryFailure = resolved.failure)
        // A source-backed descriptor uses the resolved dimensions directly. Fitting a
        // different descriptor here would introduce a second scale for stored ink.
        if (page.width != geometry.width || page.height != geometry.height) {
            return PdfAnnotationGeometryResult(failure = PdfAnnotationGeometryFailure.PAGE_SIZE_MISMATCH)
        }
        return PdfAnnotationGeometryResult(binding = PdfAnnotationGeometry(space, root.id, root.revision,
            metadataRevision, reference, metadata.source, page, geometry))
    }

    /** Rebind against the current snapshots and newly supplied effective metadata before consumption. */
    fun revalidate(
        root: DocumentRoot, catalog: PdfPageCatalog, space: String, pageId: PageId,
        metadata: PdfSourcePageGeometry, candidate: PdfAnnotationGeometry,
    ): PdfAnnotationGeometryResult {
        val fresh = bind(root, catalog, space, catalog.revision, pageId, metadata)
        if (fresh.binding == null || fresh.binding == candidate) return fresh
        return PdfAnnotationGeometryResult(failure = PdfAnnotationGeometryFailure.STALE_BINDING)
    }
}
