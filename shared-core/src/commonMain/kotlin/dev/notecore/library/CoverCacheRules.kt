package dev.notecore.library

import dev.notecore.document.hasValidUnicode

internal data class CoverCacheKey(
    val noteId: String,
    val firstPageRevision: Long,
    val bucketWidth: Int,
    val bucketHeight: Int,
)

internal enum class CoverCacheFailure { INVALID_NOTE_ID, INVALID_DIMENSIONS, INVALID_SCALE }

internal data class CoverCacheResult(val key: CoverCacheKey? = null, val failure: CoverCacheFailure? = null)

internal object CoverCacheRules {
    const val BUCKET_SIZE = 64
    const val MIN_DIMENSION = 16
    const val MAX_DIMENSION = 2048

    private fun validNoteId(id: String) = id.isNotBlank() && id.length <= 128 && id.hasValidUnicode()

    private fun bucket(value: Int) = ((value + BUCKET_SIZE - 1) / BUCKET_SIZE) * BUCKET_SIZE

    private fun validDimension(value: Int) = value in MIN_DIMENSION..MAX_DIMENSION

    private fun validScale(scale: Double) = scale.isFinite() && scale > 0.0 && scale <= 8.0

    fun derive(noteId: String, firstPageRevision: Long,
               targetWidth: Int, targetHeight: Int, scale: Double): CoverCacheResult {
        if (!validNoteId(noteId)) return CoverCacheResult(failure = CoverCacheFailure.INVALID_NOTE_ID)
        if (firstPageRevision < 0) return CoverCacheResult(failure = CoverCacheFailure.INVALID_DIMENSIONS)
        if (!validScale(scale)) return CoverCacheResult(failure = CoverCacheFailure.INVALID_SCALE)
        val w = (targetWidth * scale).toInt()
        val h = (targetHeight * scale).toInt()
        if (!validDimension(w) || !validDimension(h)) {
            return CoverCacheResult(failure = CoverCacheFailure.INVALID_DIMENSIONS)
        }
        return CoverCacheResult(CoverCacheKey(noteId, firstPageRevision, bucket(w), bucket(h)))
    }

    fun isStale(cached: CoverCacheKey, current: CoverCacheKey): Boolean = cached != current

    fun sameBucket(a: CoverCacheKey, b: CoverCacheKey): Boolean =
        a.noteId == b.noteId && a.firstPageRevision == b.firstPageRevision &&
            a.bucketWidth == b.bucketWidth && a.bucketHeight == b.bucketHeight
}
