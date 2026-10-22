package dev.notecore.canvas.prototype

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

/** Single controlled trace on real ART worker/Bitmap. Timings include the test's blocking gate. */
class RenderViewportTraceTest {
    companion object { var observation: String? = null }
    @Test fun fixedScrollZoomQueueAndHeldReaderTrace() {
        val f = BrushTileImageFixture
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val started = mutableMapOf<BrushRenderIdPoc, Long>()
        val ordinal = AtomicInteger()
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 32768, 210000, maxJobs = 2, beforeCompute = { job ->
            synchronized(started) { started[job.id] = System.nanoTime() }
            if (ordinal.incrementAndGet() == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        })
        val traceBegin = System.nanoTime()
        val events = JSONArray(); val waits = JSONArray(); val copies = JSONArray()
        val images = mutableListOf<AndroidBrushImagePoc>()
        val output = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        fun event(name: String) { events.put(JSONObject().put("event", name).put("elapsed_ns", System.nanoTime() - traceBegin).put("charged", e.chargedBytes)
            .put("retired", e.retiredBytes).put("executor", e.executorStatus().chargedBytes)) }
        fun ready() {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (e.executorStatus().entries.any { it.stage != OwnedWorkStage.READY }) {
                check(System.nanoTime() < deadline); Thread.sleep(1)
            }
        }
        fun submit(frame: FrameToken, grid: BrushTileGridPoc): BrushRenderJobPoc {
            val time = System.nanoTime()
            val job = assertIs<BrushRenderAdmissionPoc.Accepted>(e.submit(f.catalog(), frame, f.layer, grid, f.rgb, 65536, 16384)).job
            // Record admission timestamp on owner; worker timing is read only after READY's lock handoff.
            admissionTimes[job.id] = time to System.nanoTime()
            return job
        }
        fun copy(job: BrushRenderJobPoc) {
            val begin = System.nanoTime()
            val image = assertIs<AndroidBrushImageBuildPoc.Ready>(AndroidBrushImagePoc.create(e, job)).image
            copies.put(JSONObject().put("elapsed_ns", System.nanoTime() - begin)
                .put("row_storage_upper_bytes", 8192 + image.allocationBytes))
            images.add(image)
        }
        try {
            val a = submit(f.frame, f.grid); ready(); assertEquals(listOf(BrushRenderAckPoc.PUBLISHED), e.drain().map { it.second }); copy(a)
            event("first-held")
            val scroll = f.frame.copy(viewportVersion = 2, requestSequence = 2)
            e.expect(scroll, f.layer); event("scroll-old-retired")
            assertFalse(images[0].draw(Canvas(output), RectF(0f, 0f, 64f, 32f)))
            val grid = f.grid.copy(origin = PointD(4.25, 8.0))
            val b = submit(scroll, grid)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val c = submit(scroll, grid)
            assertIs<BrushRenderAdmissionPoc.Rejected>(e.submit(f.catalog(), scroll, f.layer, grid, f.rgb, 65536, 16384))
            event("two-jobs-rejected-third")
            e.cancel(c); event("queued-cancelled")
            release.countDown(); ready(); event("ready-before-ack")
            val zoom = f.frame.copy(viewportVersion = 3, requestSequence = 3)
            e.expect(zoom, f.layer)
            assertEquals(listOf(BrushRenderAckPoc.DISCARDED, BrushRenderAckPoc.FAILED), e.drain().map { it.second })
            event("late-and-cancelled-ack")
            val d = submit(zoom, grid.copy(pixelSize = 0.25)); ready(); e.drain(); copy(d); event("zoom-image-held")
            for (job in listOf(a, b, c, d)) {
                val start = synchronized(started) { started.getValue(job.id) }
                val (begin, end) = admissionTimes.getValue(job.id)
                waits.put(JSONObject().put("submit_elapsed_ns", end - begin).put("request_to_worker_ns", start - begin)
                    .put("queue_handoff_wait_lower_ns", maxOf(0L, start - end)).put("queue_handoff_wait_upper_ns", start - begin))
            }
            e.close(); assertTrue(e.awaitWorkerStop(5000)); event("closed-two-readers")
            assertFalse(e.isDrained); assertTrue(images[1].close()); event("new-reader-released")
            assertTrue(images[0].close()); event("old-reader-released"); assertTrue(e.isDrained)
            observation = JSONObject().put("trace_id", "dev034-fixed-viewport-v1").put("events", events)
                .put("queue_wait_ns", waits).put("copies", copies).put("total_budget", 210000).put("resident_budget", 32768)
                .put("max_jobs", 2).put("input_points", 4).put("viewports", JSONArray("[[0.25,8,0.5],[4.25,8,0.5],[4.25,8,0.25]]"))
                .put("timing_scope", "One ART run; Admission-to-beforeCompute wait bounded by submit call timestamps; includes controlled worker gate and handoff overhead; copy is create API elapsed, not heap/GPU")
                .put("closed_drained", e.isDrained).toString()
        } finally {
            release.countDown(); images.forEach { it.close() }; output.recycle(); e.close(); e.awaitWorkerStop(5000); e.drain()
            admissionTimes.clear()
        }
    }
    private val admissionTimes = mutableMapOf<BrushRenderIdPoc, Pair<Long, Long>>()
}
