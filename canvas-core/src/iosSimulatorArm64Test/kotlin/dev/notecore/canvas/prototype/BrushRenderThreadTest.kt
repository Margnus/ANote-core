@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.notecore.canvas.prototype

import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import kotlin.test.*

/** Test-only one-slot NSThread handoff. Does NOT claim integration with the app Swift executor. */
class BrushRenderThreadTest {
    private val f = BrushTileImageFixture
    private class Handoff(private val job: BrushRenderJobPoc) {
        private val condition = NSCondition()
        private var started = false
        private var released = false
        private var done = false
        private var cancelled = false
        private var result: WorkResult<BrushRenderComputationPoc>? = null
        var workerWasOwner = true; private set
        private val owner = NSThread.currentThread
        private val worker = NSThread {
            condition.lock()
            workerWasOwner = NSThread.currentThread.isEqual(owner)
            started = true; condition.broadcast()
            while (!released) check(condition.waitUntilDate(NSDate(timeIntervalSinceReferenceDate = NSDate().timeIntervalSinceReferenceDate + 5.0)))
            condition.unlock()
            val computed = try { WorkResult.Succeeded(job.execute {
                condition.lock(); try { cancelled } finally { condition.unlock() }
            }) } catch (failure: Throwable) { WorkResult.OutcomeUnknown("native-worker", failure.toString()) }
            condition.lock(); result = computed; done = true; condition.broadcast(); condition.unlock()
        }
        init { worker.name = "NoteCore.mask.test.worker"; worker.start() }
        fun awaitStart() {
            condition.lock()
            try { while (!started) check(condition.waitUntilDate(NSDate(timeIntervalSinceReferenceDate = NSDate().timeIntervalSinceReferenceDate + 5.0))) }
            finally { condition.unlock() }
        }
        fun release(cancel: Boolean = false) {
            condition.lock(); cancelled = cancel; released = true; condition.broadcast(); condition.unlock()
        }
        fun take(): WorkResult<BrushRenderComputationPoc> {
            condition.lock()
            try {
                while (!done) check(condition.waitUntilDate(NSDate(timeIntervalSinceReferenceDate = NSDate().timeIntervalSinceReferenceDate + 5.0)))
                return checkNotNull(result).also { result = null }
            } finally { condition.unlock() }
        }
    }
    private fun owner(): BrushRenderOwnerPoc {
        val thread = NSThread.currentThread
        return BrushRenderOwnerPoc(f.frame, f.layer, 32768, 262144,
            checkOwner = { check(NSThread.currentThread.isEqual(thread)) })
    }
    private fun job(o: BrushRenderOwnerPoc) = assertIs<BrushRenderAdmissionPoc.Accepted>(
        o.admit(f.catalog(), f.frame, f.layer, f.grid, f.rgb, 65536, 8192)).job

    @Test fun nativeWorkerGeneratesMaskAndRgbaWithOwnerOnlyResourceSettlement() {
        val o = owner(); val j = job(o); val handoff = Handoff(j)
        handoff.awaitStart(); assertEquals(j.peakBytes, o.chargedBytes); handoff.release()
        val output = handoff.take(); assertFalse(handoff.workerWasOwner)
        assertTrue(o.receive(j, output)); assertEquals(j.peakBytes, o.chargedBytes)
        assertEquals(BrushRenderAckPoc.PUBLISHED, o.acknowledge(j)); assertEquals(16384, o.chargedBytes)
        val reader = assertNotNull(o.borrow(j)); assertTrue((0 until 2048).any { reader.byteAt(it * 4 + 3) == 51 })
        o.close(); assertFalse(o.isDrained); o.returnReader(reader); assertTrue(o.isDrained)
    }

    @Test fun nativeCancellationAndCloseKeepChargeWhileWorkerIsBlocked() {
        val o = owner(); val j = job(o); val handoff = Handoff(j)
        handoff.awaitStart(); o.cancel(j); o.close()
        assertEquals(j.peakBytes, o.chargedBytes); assertFalse(o.isDrained)
        handoff.release(cancel = true)
        assertTrue(o.receive(j, handoff.take())); assertEquals(j.peakBytes, o.chargedBytes)
        assertEquals(BrushRenderAckPoc.FAILED, o.acknowledge(j)); assertTrue(o.isDrained)
    }

    @Test fun nativeImageKeepsCopyAllowanceUntilCgReleaseAndReaderReturn() {
        val o = owner()
        val j = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admit(f.catalog(), f.frame, f.layer, f.grid,
            f.rgb, 65536, nativeCopyAllowanceBytes = 16384)).job
        val handoff = Handoff(j); handoff.awaitStart(); handoff.release()
        assertTrue(o.receive(j, handoff.take())); assertEquals(BrushRenderAckPoc.PUBLISHED, o.acknowledge(j))
        val reader = assertNotNull(o.borrowNative(j)); val color = checkNotNull(CGColorSpaceCreateDeviceRGB())
        val context = try { checkNotNull(CGBitmapContextCreate(null, 64u, 32u, 8u, 256u, color,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big)) }
        finally { CGColorSpaceRelease(color) }
        try {
            val data = checkNotNull(CGBitmapContextGetData(context)).reinterpret<UByteVar>()
            val stride = CGBitmapContextGetBytesPerRow(context).toInt()
            for (y in 0 until 32) for (x in 0 until 256) data[y * stride + x] = reader.byteAt(y * 256 + x).toUByte()
            val image = checkNotNull(CGBitmapContextCreateImage(context))
            try {
                assertEquals(64uL, CGImageGetWidth(image)); assertEquals(32uL, CGImageGetHeight(image))
                val declaredStorage = (CGImageGetBytesPerRow(image) + CGBitmapContextGetBytesPerRow(context)).toLong() * 32
                assertTrue(declaredStorage <= reader.nativeCopyAllowanceBytes)
                o.close(); assertFalse(o.canPublish(reader)); assertFalse(o.isDrained); assertEquals(24576, o.chargedBytes)
                println("NOTECORE_NATIVE_RENDER {\"render_native_lease\":true,\"rgba_bytes\":8192,\"native_copy_allowance_bytes\":16384,\"image_plus_context_row_storage_upper_bytes\":$declaredStorage,\"charged_while_closed_reader_held\":${o.chargedBytes}}")
            } finally { CGImageRelease(image) }
        } finally { CGContextRelease(context); check(o.completeNativeRelease(reader, ResourceReleaseOutcome.RELEASED)); o.close() }
        assertTrue(o.isDrained); assertEquals(0, o.chargedBytes)
    }
}
