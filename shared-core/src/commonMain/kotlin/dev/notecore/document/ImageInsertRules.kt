package dev.notecore.document

import dev.notecore.canvas.prototype.Affine2D
import dev.notecore.canvas.prototype.CommandId
import dev.notecore.canvas.prototype.ObjectId
import dev.notecore.canvas.prototype.PointD
import dev.notecore.canvas.prototype.SceneId

internal enum class EncodedImageFormat { PNG, JPEG }

/** Identity and metadata verified by the original-resource reader. Contains no decoded or encoded bytes. */
internal data class OriginalImageRef(
    val sha256: String,
    val byteCount: Long,
    val format: EncodedImageFormat,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val exifOrientation: Int,
)

internal enum class DecodedImageOrientation { SOURCE, DISPLAY }

internal data class ImageInsertRequest(
    val operationId: CommandId,
    val documentId: SceneId,
    val baseRevision: Long,
    val pageId: PageId,
    val objectId: ObjectId,
    val topLeft: PointD,
    val pageUnitsPerPixel: Double,
)

internal enum class ImageInsertFailure {
    WRONG_DOCUMENT, STALE_ROOT, REVISION_EXHAUSTED, INVALID_ID, MISSING_PAGE, PAGE_CHANGED,
    DUPLICATE_OBJECT, INVALID_SOURCE, SOURCE_CHANGED, INVALID_PLACEMENT,
}

internal sealed interface ImageInsertResult {
    data class Rejected(val reason: ImageInsertFailure) : ImageInsertResult
    data class SourceGeometryRejected(val reason: ImageOrientationFailure) : ImageInsertResult
    data class Prepared(val plan: ImageInsertPlan) : ImageInsertResult
}

internal class ImageInsertPlan internal constructor(
    val request: ImageInsertRequest,
    val page: PageDescriptor,
    val original: OriginalImageRef,
    private val orientation: ImageOrientationMapping,
    private val displayToPage: Affine2D,
) {
    /** Orientation is supplied by the adapter's decode mode, even when the dimensions are unchanged. */
    fun decodedToPage(width: Int, height: Int, decodedOrientation: DecodedImageOrientation): Affine2D? {
        val source = decodedOrientation == DecodedImageOrientation.SOURCE
        val fullWidth = if (source) orientation.sourceWidth else orientation.width
        val fullHeight = if (source) orientation.sourceHeight else orientation.height
        if (width !in 1..fullWidth || height !in 1..fullHeight) return null
        val toFullRaster = Affine2D(a = fullWidth.toDouble() / width, d = fullHeight.toDouble() / height)
        val toDisplay = if (source) toFullRaster.then(orientation.sourceToDisplay) else toFullRaster
        return toDisplay.then(displayToPage)
    }
}

internal object ImageInsertRules {
    fun prepare(root: DocumentRoot, request: ImageInsertRequest, original: OriginalImageRef, maxSourcePixels: Long): ImageInsertResult {
        validateTarget(root, request)?.let { return ImageInsertResult.Rejected(it) }
        val page = root.findPage(request.pageId) ?: return ImageInsertResult.Rejected(ImageInsertFailure.MISSING_PAGE)
        if (original.byteCount <= 0 || original.sha256.length != 64 ||
            original.sha256.any { it !in '0'..'9' && it !in 'a'..'f' }
        ) return ImageInsertResult.Rejected(ImageInsertFailure.INVALID_SOURCE)

        val orientation = ImageOrientationRules.resolve(original.pixelWidth, original.pixelHeight, original.exifOrientation, maxSourcePixels)
        orientation.failure?.let { return ImageInsertResult.SourceGeometryRejected(it) }
        val mapping = checkNotNull(orientation.mapping)
        val scale = request.pageUnitsPerPixel
        val (left, top) = request.topLeft
        val right = left + scale * mapping.width
        val bottom = top + scale * mapping.height
        if (!right.isFinite() || !bottom.isFinite() || right <= left || bottom <= top) {
            return ImageInsertResult.Rejected(ImageInsertFailure.INVALID_PLACEMENT)
        }
        val displayToPage = Affine2D(a = scale, d = scale, tx = left, ty = top)
        if (!displayToPage.hasInverse()) return ImageInsertResult.Rejected(ImageInsertFailure.INVALID_PLACEMENT)
        return ImageInsertResult.Prepared(ImageInsertPlan(request, page, original, mapping, displayToPage))
    }

    fun revalidate(root: DocumentRoot, plan: ImageInsertPlan, observedOriginal: OriginalImageRef): ImageInsertFailure? {
        validateTarget(root, plan.request)?.let { return it }
        val page = root.findPage(plan.request.pageId) ?: return ImageInsertFailure.MISSING_PAGE
        if (page != plan.page) return ImageInsertFailure.PAGE_CHANGED
        if (observedOriginal != plan.original) return ImageInsertFailure.SOURCE_CHANGED
        return null
    }

    private fun validateTarget(root: DocumentRoot, request: ImageInsertRequest): ImageInsertFailure? = when {
        root.id != request.documentId -> ImageInsertFailure.WRONG_DOCUMENT
        root.revision != request.baseRevision -> ImageInsertFailure.STALE_ROOT
        root.revision == Long.MAX_VALUE -> ImageInsertFailure.REVISION_EXHAUSTED
        !request.pageId.value.hasValidUnicode() || !request.objectId.value.hasValidUnicode() ||
            !request.operationId.value.hasValidUnicode() || !request.documentId.value.hasValidUnicode() -> ImageInsertFailure.INVALID_ID
        root.scene.findObject(request.objectId) != null -> ImageInsertFailure.DUPLICATE_OBJECT
        else -> null
    }
}
