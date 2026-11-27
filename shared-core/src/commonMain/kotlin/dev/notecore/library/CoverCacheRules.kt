package dev.notecore.library

import dev.notecore.document.PageId
import dev.notecore.document.hasValidUnicode

internal data class CoverCacheKey(
    val spaceId: String,
    val noteId: String,
    val firstPageId: PageId,
    val firstPageRevision: Long,
    val bucketWidth: Int,
    val bucketHeight: Int,
)

internal enum class CoverCacheFailure { INVALID_SPACE_ID, INVALID_NOTE_ID, INVALID_PAGE_ID, INVALID_DIMENSIONS, INVALID_SCALE }

internal data class CoverCacheResult(val key: CoverCacheKey? = null, val failure: CoverCacheFailure? = null)

internal object CoverCacheRules {
    const val BUCKET_SIZE = 64
    const val MIN_DIMENSION = 16
    const val MAX_DIMENSION = 2048

    private fun validId(id: String) = id.isNotBlank() && id.length <= 128 && id.hasValidUnicode()

    private fun bucket(value: Int) = ((value + BUCKET_SIZE - 1) / BUCKET_SIZE) * BUCKET_SIZE

    private fun validDimension(value: Int) = value in MIN_DIMENSION..MAX_DIMENSION

    private fun validScale(scale: Double) = scale.isFinite() && scale > 0.0 && scale <= 8.0

    fun sizeFailure(targetWidth: Int, targetHeight: Int, scale: Double): CoverCacheFailure? {
        if (!validScale(scale)) return CoverCacheFailure.INVALID_SCALE
        if (!validDimension((targetWidth * scale).toInt()) || !validDimension((targetHeight * scale).toInt())) {
            return CoverCacheFailure.INVALID_DIMENSIONS
        }
        return null
    }

    fun derive(spaceId: String, noteId: String, firstPageId: PageId, firstPageRevision: Long,
               targetWidth: Int, targetHeight: Int, scale: Double): CoverCacheResult {
        if (!validId(spaceId)) return CoverCacheResult(failure = CoverCacheFailure.INVALID_SPACE_ID)
        if (!validId(noteId)) return CoverCacheResult(failure = CoverCacheFailure.INVALID_NOTE_ID)
        if (!firstPageId.value.hasValidUnicode()) return CoverCacheResult(failure = CoverCacheFailure.INVALID_PAGE_ID)
        if (firstPageRevision < 0) return CoverCacheResult(failure = CoverCacheFailure.INVALID_DIMENSIONS)
        sizeFailure(targetWidth, targetHeight, scale)?.let { return CoverCacheResult(failure = it) }
        return CoverCacheResult(CoverCacheKey(spaceId, noteId, firstPageId, firstPageRevision,
            bucket((targetWidth * scale).toInt()), bucket((targetHeight * scale).toInt())))
    }

    fun isStale(cached: CoverCacheKey, current: CoverCacheKey): Boolean = cached != current

    fun sameBucket(a: CoverCacheKey, b: CoverCacheKey): Boolean = a == b
}
