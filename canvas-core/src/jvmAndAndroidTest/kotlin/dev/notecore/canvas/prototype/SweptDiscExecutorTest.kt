package dev.notecore.canvas.prototype

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

internal object SweptDiscExecutorFixture {
    val frame = FrameToken(SceneId("swept-executor"), 1, 1, 1, 1, 1)
    val layer = LayerId("ink")
    val grid = BrushTileGridPoc(PointD(0.0, 0.0), 32, 16)
    val retainedBytes = grid.width * grid.height * 4L
    const val rgb = 0x336699
    val centerAlpha = (8 * grid.width + 16) * 4 + 3

    fun catalog(): ChunkedBrushPoc {
        val edges = SweptDiscGeometryPoc.SEGMENT_EDGES
        val xs = DoubleArray(2 * edges + 1) { 4.0 + minOf(it, 2 * edges - it) * 0.125 }
        val samples = StrokeSamples.copyOf(xs, DoubleArray(xs.size) { 8.0 },
            FloatArray(xs.size) { 1f }, LongArray(xs.size) { it.toLong() })
        val brush = BrushSpec(BrushKind.HIGHLIGHTER, 1, 8.0, -1)
        val stroke = StrokeObject(ObjectId("retrace"), 1, layer, brush, samples)
        return assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(frame, stroke)).catalog
    }

    fun submit(executor: BrushRenderExecutorPoc, catalog: ChunkedBrushPoc, token: FrameToken = frame,
        allowance: Long = 0,
    ): BrushRenderJobPoc = assertIs<BrushRenderAdmissionPoc.Accepted>(
        executor.submitSwept(catalog, token, layer, grid, rgb, 65_536, allowance)).job

    fun awaitReady(executor: BrushRenderExecutorPoc, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (executor.executorStatus().entries.count { it.stage == OwnedWorkStage.READY } < count) {
            check(System.nanoTime() < deadline) { "Swept worker did not become ready" }
            Thread.sleep(1)
        }
    }
}

class SweptDiscExecutorTest {
    @Test fun realQueueRetainsChargesAcrossBlockedDeliveryAndDropsObsoleteRaster() {
        val f = SweptDiscExecutorFixture
        val catalog = f.catalog()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val permit = AtomicBoolean(false)
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 8_192, 262_144, maxJobs = 2,
            beforeCompute = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }, permitDelivery = { permit.get() })
        try {
            val first = f.submit(e, catalog)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val second = f.submit(e, catalog)
            assertEquals(listOf(OwnedWorkStage.RUNNING, OwnedWorkStage.QUEUED),
                e.executorStatus().entries.map { it.stage })
            assertEquals(first.peakBytes + second.peakBytes, e.chargedBytes)
            release.countDown()
            f.awaitReady(e, 2)
            assertTrue(e.drain().isEmpty())
            assertEquals(listOf(OwnedWorkStage.DELIVERY_BLOCKED, OwnedWorkStage.READY),
                e.executorStatus().entries.map { it.stage })
            assertEquals(first.peakBytes + second.peakBytes, e.chargedBytes)
            val current = f.frame.copy(viewportVersion = 2, requestSequence = 2)
            assertTrue(e.expect(current, f.layer))
            assertTrue(e.retryDelivery(first))
            assertTrue(e.drain().isEmpty())
            assertEquals(first.peakBytes + second.peakBytes, e.chargedBytes)
            permit.set(true)
            assertEquals(listOf(OwnedWorkStage.DELIVERY_BLOCKED, OwnedWorkStage.READY),
                e.executorStatus().entries.map { it.stage })
            assertTrue(e.retryDelivery(first))
            assertEquals(listOf(BrushRenderAckPoc.DISCARDED, BrushRenderAckPoc.DISCARDED), e.drain().map { it.second })
            assertEquals(0, e.chargedBytes)
            assertNull(e.borrow(first))
            val latest = f.submit(e, catalog, current)
            f.awaitReady(e, 1)
            assertEquals(BrushRenderAckPoc.PUBLISHED, e.drain().single().second)
            val reader = assertNotNull(e.borrow(latest))
            assertEquals(127, reader.byteAt(f.centerAlpha))
            e.close()
            assertTrue(e.awaitWorkerStop(10_000))
            assertEquals(f.retainedBytes, e.retiredBytes)
            assertFalse(e.isDrained)
            assertEquals(LeaseReturn.RETURNED, e.returnReader(reader))
            assertTrue(e.isDrained)
            println("Swept queue: two obsolete jobs discarded, current alpha=127, drained")
        } finally {
            release.countDown()
            permit.set(true)
            e.close()
            e.awaitWorkerStop(10_000)
            e.drain()
        }
    }
}
