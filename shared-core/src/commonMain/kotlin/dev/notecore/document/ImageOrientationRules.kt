package dev.notecore.document

import dev.notecore.canvas.prototype.Affine2D

internal data class ImageOrientationMapping(
    val sourceWidth: Int, val sourceHeight: Int, val orientation: Int,
    val width: Int, val height: Int, val sourceToDisplay: Affine2D, val displayToSource: Affine2D,
)
internal enum class ImageOrientationFailure { INVALID_DIMENSIONS, UNKNOWN_ORIENTATION, INVALID_BUDGET, PIXEL_CAPACITY }
internal data class ImageOrientationResult(val mapping: ImageOrientationMapping? = null, val failure: ImageOrientationFailure? = null)

internal object ImageOrientationRules {
    fun resolve(width: Int, height: Int, orientation: Int, maxSourcePixels: Long = 32_000_000): ImageOrientationResult {
        if (width <= 0 || height <= 0) return ImageOrientationResult(failure = ImageOrientationFailure.INVALID_DIMENSIONS)
        if (orientation !in 1..8) return ImageOrientationResult(failure = ImageOrientationFailure.UNKNOWN_ORIENTATION)
        if (maxSourcePixels <= 0) return ImageOrientationResult(failure = ImageOrientationFailure.INVALID_BUDGET)
        if (width.toLong() * height.toLong() > maxSourcePixels) return ImageOrientationResult(failure = ImageOrientationFailure.PIXEL_CAPACITY)
        val w = width.toDouble(); val h = height.toDouble()
        val forward = when (orientation) {
            1 -> Affine2D()
            2 -> Affine2D(a = -1.0, tx = w)
            3 -> Affine2D(a = -1.0, d = -1.0, tx = w, ty = h)
            4 -> Affine2D(d = -1.0, ty = h)
            5 -> Affine2D(a = 0.0, b = 1.0, c = 1.0, d = 0.0)
            6 -> Affine2D(a = 0.0, b = 1.0, c = -1.0, d = 0.0, tx = h)
            7 -> Affine2D(a = 0.0, b = -1.0, c = -1.0, d = 0.0, tx = h, ty = w)
            else -> Affine2D(a = 0.0, b = -1.0, c = 1.0, d = 0.0, ty = w)
        }
        val swapped = orientation >= 5
        return ImageOrientationResult(ImageOrientationMapping(width, height, orientation,
            if (swapped) height else width, if (swapped) width else height, forward, checkNotNull(forward.inverseOrNull())))
    }
}
