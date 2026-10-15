package dev.notecore.canvas.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.nio.ByteBuffer

internal sealed interface AndroidBrushImageBuildPoc {
    data class Ready(val image: AndroidBrushImagePoc) : AndroidBrushImageBuildPoc
    data class Rejected(val reason: String) : AndroidBrushImageBuildPoc
    // Caller must retain this holder and its executor until reconciliation, including on UI detach.
    data class CleanupRequired(val image: AndroidBrushImagePoc, val reason: String) : AndroidBrushImageBuildPoc
}

/** Only the copy/release boundary is injectable for failure tests. Allocation stays the actual
 * Android API; a backend cannot silently allocate an unrelated image or escape a raw Bitmap.
 */
internal interface AndroidBrushImageCallsPoc {
    fun copy(bitmap: Bitmap, bytes: ByteBuffer) { bitmap.copyPixelsFromBuffer(bytes) }
    fun release(bitmap: Bitmap) { bitmap.recycle() }
}

/** Owner-confined, unpublished platform image holder. The raw Bitmap never escapes this adapter.
 * Only software Canvas is admitted; hardware display-list/GPU lifetimes need a separate fence contract.
 * Its strong executor reference keeps the resource authority reachable while a detached UI hands
 * the holder to its existing owner. This is not cross-thread ownership transfer or a GPU fence.
 */
internal class AndroidBrushImagePoc private constructor(
    private val owner: BrushRenderExecutorPoc,
    private val reader: BrushRenderReaderPoc,
    private val calls: AndroidBrushImageCallsPoc,
) {
    private enum class State { BUILDING, READY, UNKNOWN_RELEASE, RELEASED }
    private var state = State.BUILDING
    private var bitmap: Bitmap? = null
    private val paint = Paint()
    private var storage = 0L
    val allocationBytes: Long get() { checkOwner(); return storage }
    val released: Boolean get() { checkOwner(); return state == State.RELEASED }
    val releaseUnknown: Boolean get() { checkOwner(); return state == State.UNKNOWN_RELEASE }
    private fun checkOwner() { owner.executorStatus() }

    /** No stale frame may draw, even if an old UI still holds this image after expect/close. */
    fun draw(canvas: Canvas, destination: RectF, filtered: Boolean = true): Boolean {
        checkOwner()
        if (state != State.READY || canvas.isHardwareAccelerated || !owner.canPublish(reader)) return false
        paint.isFilterBitmap = filtered
        canvas.drawBitmap(checkNotNull(bitmap), null, destination, paint)
        return true
    }

    /** Idempotent after confirmed release. A thrown release is UNKNOWN, never retried implicitly. */
    fun close(): Boolean {
        checkOwner()
        if (state == State.RELEASED) return true
        if (state == State.UNKNOWN_RELEASE) return false
        try {
            bitmap?.let { calls.release(it) }
            check(bitmap == null || bitmap!!.isRecycled) { "Release returned with a live Bitmap" }
        } catch (_: Throwable) {
            state = State.UNKNOWN_RELEASE
            check(!owner.completeNativeRelease(reader, ResourceReleaseOutcome.UNKNOWN))
            return false
        }
        check(owner.completeNativeRelease(reader, ResourceReleaseOutcome.RELEASED))
        bitmap = null; state = State.RELEASED
        return true
    }

    /** Read-only platform proof, not another destructive release attempt. If the failed call did
     * not recycle, the holder remains quarantined; a timeout or owner disappearance is no proof.
     */
    fun reconcileReleased(): Boolean {
        checkOwner()
        if (state != State.UNKNOWN_RELEASE || bitmap?.isRecycled != true) return false
        check(owner.reconcileNativeReleased(reader))
        bitmap = null; state = State.RELEASED
        return true
    }

    companion object {
        private val platformCalls = object : AndroidBrushImageCallsPoc {}
        fun create(owner: BrushRenderExecutorPoc, job: BrushRenderJobPoc,
            calls: AndroidBrushImageCallsPoc = platformCalls,
        ): AndroidBrushImageBuildPoc {
            owner.executorStatus() // Check confinement before borrowing or calling platform APIs.
            val bytes = job.grid.width * job.grid.height * 4L
            if (job.nativeCopyAllowanceBytes < bytes * 2) return AndroidBrushImageBuildPoc.Rejected("native-copy-budget")
            val reader = owner.borrowNative(job) ?: return AndroidBrushImageBuildPoc.Rejected("native-reader-unavailable")
            val image = AndroidBrushImagePoc(owner, reader, calls)
            try {
                val bitmap = Bitmap.createBitmap(job.grid.width, job.grid.height, Bitmap.Config.ARGB_8888)
                image.bitmap = bitmap
                image.storage = bitmap.allocationByteCount.toLong()
                // Unexpected platform padding is rejected and cleaned, never published as in-budget.
                check(bitmap.rowBytes == job.grid.width * 4 && image.storage <= reader.nativeCopyAllowanceBytes - bytes) {
                    "Unsupported native allocation layout"
                }
                val staging = ByteBuffer.allocate(bytes.toInt())
                for (i in 0 until bytes.toInt()) staging.put(reader.byteAt(i).toByte())
                staging.rewind(); calls.copy(bitmap, staging)
                image.state = State.READY
                return AndroidBrushImageBuildPoc.Ready(image)
            } catch (_: Throwable) {
                return if (image.close()) AndroidBrushImageBuildPoc.Rejected("native-image-create-failed")
                else AndroidBrushImageBuildPoc.CleanupRequired(image, "native-image-create-cleanup-unknown")
            }
        }
    }
}
