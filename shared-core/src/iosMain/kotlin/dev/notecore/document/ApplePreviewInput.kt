@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.notecore.document

import dev.notecore.canvas.prototype.PointerPhase
import dev.notecore.canvas.prototype.InputRejection
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/** Unfrozen local-process native scalar buffers, not a persisted wire format or another session.
 * NSData must stay immutable during this synchronous owner call. No platform pointer is retained.
 */
object ApplePreviewInput {
    fun offer(notebook: PagedPreviewNotebook, phase: PointerPhase, eventTimeMicros: Long,
              xs: NSData, ys: NSData, pressures: NSData, times: NSData): PreviewInputResult {
        notebook.realBatchGate(phase)?.let { return it }
        if (xs.length > PREVIEW_INPUT_BATCH_LIMIT.toULong() * 8uL)
            return notebook.rejectRealBatch(PreviewFailureKind.INPUT_BATCH_CAPACITY)
        val count = (xs.length / 8uL).toInt()
        if (xs.length % 8uL != 0uL || ys.length != count.toULong() * 8uL ||
            pressures.length != count.toULong() * 4uL || times.length != count.toULong() * 8uL)
            return notebook.rejectRealBatch(PreviewFailureKind.INVALID_INPUT, InputRejection.INVALID_BATCH)
        val x = DoubleArray(count); val y = DoubleArray(count); val pressure = FloatArray(count); val time = LongArray(count)
        if (count > 0) {
            x.usePinned { memcpy(it.addressOf(0), xs.bytes, xs.length) }
            y.usePinned { memcpy(it.addressOf(0), ys.bytes, ys.length) }
            pressure.usePinned { memcpy(it.addressOf(0), pressures.bytes, pressures.length) }
            time.usePinned { memcpy(it.addressOf(0), times.bytes, times.length) }
        }
        return notebook.offerRealBatch(phase, eventTimeMicros, x, y, pressure, time)
    }
}
