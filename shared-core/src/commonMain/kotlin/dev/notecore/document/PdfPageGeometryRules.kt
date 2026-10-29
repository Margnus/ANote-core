package dev.notecore.document

import dev.notecore.canvas.prototype.Affine2D
import kotlin.math.abs

/** Effective PDF user-space box: bottom/top use upward PDF y, not BoundsD's screen naming. */
internal data class PdfCropBox(val left: Double, val bottom: Double, val right: Double, val top: Double)
internal data class PdfPageGeometry(
    val crop: PdfCropBox, val rotation: Int, val userUnit: Double,
    val width: Double, val height: Double, val pdfToPage: Affine2D, val pageToPdf: Affine2D,
)
internal enum class PdfPageGeometryFailure { INVALID_BOX, INVALID_ROTATION, INVALID_USER_UNIT, SIZE_LIMIT, UNREPRESENTABLE_MATRIX }
internal data class PdfPageGeometryResult(val geometry: PdfPageGeometry? = null, val failure: PdfPageGeometryFailure? = null)

internal object PdfPageGeometryRules {
    fun resolve(crop: PdfCropBox, rotation: Int, userUnit: Double = 1.0): PdfPageGeometryResult {
        if (listOf(crop.left, crop.bottom, crop.right, crop.top).any { !it.isFinite() || abs(it) > 1e9 } ||
            crop.left >= crop.right || crop.bottom >= crop.top) return PdfPageGeometryResult(failure = PdfPageGeometryFailure.INVALID_BOX)
        if (rotation % 90 != 0) return PdfPageGeometryResult(failure = PdfPageGeometryFailure.INVALID_ROTATION)
        if (!userUnit.isFinite() || userUnit <= 0) return PdfPageGeometryResult(failure = PdfPageGeometryFailure.INVALID_USER_UNIT)
        val normalized = (rotation % 360 + 360) % 360
        val w = (crop.right - crop.left) * userUnit
        val h = (crop.top - crop.bottom) * userUnit
        if (!w.isFinite() || !h.isFinite() || w !in 1.0..14400.0 || h !in 1.0..14400.0) {
            return PdfPageGeometryResult(failure = PdfPageGeometryFailure.SIZE_LIMIT)
        }
        val u = userUnit
        val coefficients = when (normalized) {
            0 -> listOf(u, 0.0, 0.0, -u, -crop.left * u, crop.top * u)
            90 -> listOf(0.0, u, u, 0.0, -crop.bottom * u, -crop.left * u)
            180 -> listOf(-u, 0.0, 0.0, u, crop.right * u, -crop.bottom * u)
            else -> listOf(0.0, -u, -u, 0.0, crop.top * u, crop.right * u)
        }
        if (coefficients.any { !it.isFinite() }) return PdfPageGeometryResult(failure = PdfPageGeometryFailure.UNREPRESENTABLE_MATRIX)
        val forward = Affine2D(coefficients[0], coefficients[1], coefficients[2], coefficients[3], coefficients[4], coefficients[5])
        val inverse = forward.inverseOrNull() ?: return PdfPageGeometryResult(failure = PdfPageGeometryFailure.UNREPRESENTABLE_MATRIX)
        val swapped = normalized == 90 || normalized == 270
        return PdfPageGeometryResult(PdfPageGeometry(crop, normalized, u, if (swapped) h else w,
            if (swapped) w else h, forward, inverse))
    }
}
