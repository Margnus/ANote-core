package dev.notecore.canvas.prototype

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import java.nio.ByteBuffer
import org.junit.Test
import kotlin.test.*

/** Actual Bitmap allocations and draws; injected boundary failures are not OS failure frequency. */
class AndroidBrushImagePocTest {
    @Test fun canonicalPageCompositeDrawsOnActualBitmapAndClosesWithHeldReader() {
        val page = BrushPageImageFixture
        val e = executor()
        val j = assertIs<BrushRenderAdmissionPoc.Accepted>(e.submitPage(page.scene(), page.frame, page.layer,
            page.grid, 65536, 16384)).job
        val until = System.nanoTime() + 5_000_000_000L
        while (e.executorStatus().entries.single().stage != OwnedWorkStage.READY) {
            check(System.nanoTime() < until); Thread.sleep(1)
        }
        assertEquals(BrushRenderAckPoc.PUBLISHED, e.drain().single().second)
        val image = assertIs<AndroidBrushImageBuildPoc.Ready>(AndroidBrushImagePoc.create(e, j)).image
        val target = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(image.draw(Canvas(target), RectF(0f, 0f, 64f, 32f), false))
            val color = target.getPixel(15, 8)
            assertEquals(192, Color.alpha(color))
            // getPixel unpremultiplies; compare reconstructed premultiplied channels.
            assertTrue(kotlin.math.abs((Color.red(color) * 192 + 127) / 255 - 64) <= 1)
            assertTrue(kotlin.math.abs((Color.blue(color) * 192 + 127) / 255 - 128) <= 1)
            assertEquals(0, Color.green(color))
            stop(e); assertFalse(e.isDrained); assertEquals(24576, e.chargedBytes)
            assertFalse(image.draw(Canvas(target), RectF(0f, 0f, 64f, 32f)))
            assertTrue(image.close()); assertTrue(e.isDrained); assertEquals(0, e.chargedBytes)
        } finally { image.close(); target.recycle(); stop(e) }
    }
    private val f = BrushTileImageFixture
    private fun executor(total: Long = 262144) = BrushRenderExecutorPoc(f.frame, f.layer, 32768, total)
    private fun job(e: BrushRenderExecutorPoc, native: Long = 16384): BrushRenderJobPoc {
        val j = assertIs<BrushRenderAdmissionPoc.Accepted>(e.submit(f.catalog(), f.frame, f.layer, f.grid,
            f.rgb, 65536, nativeCopyAllowanceBytes = native)).job
        val deadline = System.nanoTime() + 5_000_000_000L
        while (e.executorStatus().entries.single().stage != OwnedWorkStage.READY) {
            check(System.nanoTime() < deadline); Thread.sleep(1)
        }
        assertEquals(BrushRenderAckPoc.PUBLISHED, e.drain().single().second)
        return j
    }
    private fun stop(e: BrushRenderExecutorPoc) {
        e.close(); assertTrue(e.awaitWorkerStop(5000)); e.drain()
    }

    @Test fun imageDrawsActualPayloadThenRetiresWithoutReleasingHeldBudget() {
        val e = executor(total = 100000)
        val image = assertIs<AndroidBrushImageBuildPoc.Ready>(AndroidBrushImagePoc.create(e, job(e))).image
        val output = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        try {
            assertEquals(8192, image.allocationBytes)
            assertTrue(image.draw(Canvas(output), RectF(0f, 0f, 64f, 32f), filtered = false))
            assertTrue((0 until 2048).any { Color.alpha(output.getPixel(it % 64, it / 64)) == 51 })
            val next = f.frame.copy(viewportVersion = 2, requestSequence = 2)
            assertTrue(e.expect(next, f.layer))
            assertFalse(image.draw(Canvas(output), RectF(0f, 0f, 64f, 32f)))
            assertEquals(24576, e.chargedBytes)
            assertIs<BrushRenderAdmissionPoc.Rejected>(e.submit(f.catalog(), next, f.layer, f.grid, f.rgb,
                65536, nativeCopyAllowanceBytes = 16384)) // Old image still competes for total budget.
            e.close(); assertFalse(e.isDrained)
            assertTrue(image.close()); assertTrue(image.close()); assertTrue(image.released)
            assertFalse(image.draw(Canvas(output), RectF(0f, 0f, 64f, 32f)))
            assertEquals(0, e.chargedBytes)
        } finally { image.close(); output.recycle(); stop(e) }
        assertTrue(e.isDrained)
    }

    @Test fun insufficientAllowanceDoesNotConsumeTheUniqueNativePermit() {
        val e = executor()
        try {
            val j = job(e, native = 8192)
            assertEquals("native-copy-budget", assertIs<AndroidBrushImageBuildPoc.Rejected>(AndroidBrushImagePoc.create(e, j)).reason)
            val reader = assertNotNull(e.borrowNative(j))
            assertTrue(e.completeNativeRelease(reader, ResourceReleaseOutcome.RELEASED))
        } finally { stop(e) }
        assertTrue(e.isDrained)
    }

    @Test fun failedCopyCleansImageAndAllowsNewHolderWithoutRerendering() {
        val e = executor(); var releases = 0
        try {
            val j = job(e)
            val calls = object : AndroidBrushImageCallsPoc {
                override fun copy(bitmap: Bitmap, bytes: ByteBuffer) { error("injected copy failure") }
                override fun release(bitmap: Bitmap) { releases++; bitmap.recycle() }
            }
            assertIs<AndroidBrushImageBuildPoc.Rejected>(AndroidBrushImagePoc.create(e, j, calls))
            assertEquals(1, releases); assertEquals(24576, e.chargedBytes) // Output remains resident.
            val image = assertIs<AndroidBrushImageBuildPoc.Ready>(AndroidBrushImagePoc.create(e, j)).image
            try { assertIs<AndroidBrushImageBuildPoc.Rejected>(AndroidBrushImagePoc.create(e, j)) }
            finally { assertTrue(image.close()) }
        } finally { stop(e) }
        assertTrue(e.isDrained)
    }

    @Test fun unknownReleaseCannotRetryAndNeedsReadOnlyPlatformProof() {
        val e = executor(); var releases = 0; var retainedForTest: Bitmap? = null
        val calls = object : AndroidBrushImageCallsPoc {
            override fun release(bitmap: Bitmap) {
                releases++; retainedForTest = bitmap; error("injected failure before release")
            }
        }
        val image = assertIs<AndroidBrushImageBuildPoc.Ready>(AndroidBrushImagePoc.create(e, job(e), calls)).image
        try {
            e.close(); assertFalse(image.close()); assertTrue(image.releaseUnknown)
            assertFalse(image.close()); assertFalse(image.reconcileReleased()); assertEquals(1, releases)
            assertEquals(24576, e.chargedBytes); assertFalse(e.isDrained)
            // Simulate an independently confirmed external cleanup, without another holder release.
            assertNotNull(retainedForTest).recycle()
            assertTrue(image.reconcileReleased()); assertFalse(image.reconcileReleased())
            assertEquals(0, e.chargedBytes); assertTrue(image.close()); assertEquals(1, releases)
        } finally { retainedForTest?.recycle(); image.reconcileReleased(); stop(e) }
        assertTrue(e.isDrained)
    }

    @Test fun copyFailureAndUnknownCleanupReturnsHolderInsteadOfLosingResponsibility() {
        val e = executor(); var releases = 0
        val calls = object : AndroidBrushImageCallsPoc {
            override fun copy(bitmap: Bitmap, bytes: ByteBuffer) { error("injected copy failure") }
            override fun release(bitmap: Bitmap) { releases++; bitmap.recycle(); error("injected lost confirmation") }
        }
        try {
            val result = assertIs<AndroidBrushImageBuildPoc.CleanupRequired>(AndroidBrushImagePoc.create(e, job(e), calls))
            assertTrue(result.image.releaseUnknown); assertFalse(result.image.close()); assertEquals(1, releases)
            e.close(); assertEquals(24576, e.chargedBytes)
            assertTrue(result.image.reconcileReleased()); assertEquals(0, e.chargedBytes)
        } finally { stop(e) }
        assertTrue(e.isDrained)
    }

    @Test fun wrongThreadCannotReleaseOrDrawAnOwnerConfinedHolder() {
        val e = executor(); val image = assertIs<AndroidBrushImageBuildPoc.Ready>(AndroidBrushImagePoc.create(e, job(e))).image
        val failures = mutableListOf<Throwable>()
        val output = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        try {
            val worker = Thread {
                try { image.close() } catch (failure: Throwable) { failures.add(failure) }
                try { image.allocationBytes } catch (failure: Throwable) { failures.add(failure) }
                try { image.draw(canvas, RectF(0f, 0f, 64f, 32f)) } catch (failure: Throwable) { failures.add(failure) }
            }
            worker.start(); worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(3, failures.size); assertTrue(failures.all { it is IllegalStateException })
            assertFalse(image.released); assertEquals(24576, e.chargedBytes)
        } finally { image.close(); output.recycle(); stop(e) }
        assertTrue(e.isDrained)
    }
}
