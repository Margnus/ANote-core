package dev.notecore.document

internal data class ImageDecodePlan(
    val sourceWidth: Int, val sourceHeight: Int, val orientation: Int,
    val requiredWidth: Int, val requiredHeight: Int, val sampleSize: Int,
    val maxWidth: Int, val maxHeight: Int, val argbByteUpperBound: Long, val sourceLimited: Boolean,
)
internal enum class ImageDecodeFailure { INVALID_DIMENSIONS, UNKNOWN_ORIENTATION, INVALID_BUDGET, SOURCE_CAPACITY, DECODE_CAPACITY }
internal data class ImageDecodeResult(val plan: ImageDecodePlan? = null, val failure: ImageDecodeFailure? = null)

internal object ImageDecodeRules {
    fun plan(width: Int, height: Int, orientation: Int, requiredWidth: Int, requiredHeight: Int,
             maxSourcePixels: Long, maxDecodedBytes: Long): ImageDecodeResult {
        if (width <= 0 || height <= 0 || requiredWidth <= 0 || requiredHeight <= 0)
            return ImageDecodeResult(failure = ImageDecodeFailure.INVALID_DIMENSIONS)
        if (orientation !in 1..8) return ImageDecodeResult(failure = ImageDecodeFailure.UNKNOWN_ORIENTATION)
        if (maxSourcePixels <= 0 || maxDecodedBytes <= 0) return ImageDecodeResult(failure = ImageDecodeFailure.INVALID_BUDGET)
        if (width.toLong() * height > maxSourcePixels) return ImageDecodeResult(failure = ImageDecodeFailure.SOURCE_CAPACITY)
        val targetWidth = if (orientation >= 5) requiredHeight else requiredWidth
        val targetHeight = if (orientation >= 5) requiredWidth else requiredHeight
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2) {
            val next = sample * 2
            // Quality uses floor; some decoders round odd dimensions down rather than up.
            if (width / next < targetWidth || height / next < targetHeight) break
            sample = next
        }
        fun upper(value: Int) = ((value.toLong() + sample - 1) / sample).toInt()
        val maxWidth = upper(width); val maxHeight = upper(height)
        val pixels = maxWidth.toLong() * maxHeight
        if (pixels > maxDecodedBytes / 4) return ImageDecodeResult(failure = ImageDecodeFailure.DECODE_CAPACITY)
        return ImageDecodeResult(ImageDecodePlan(width, height, orientation, requiredWidth, requiredHeight,
            sample, maxWidth, maxHeight, pixels * 4, width < targetWidth || height < targetHeight))
    }
}
