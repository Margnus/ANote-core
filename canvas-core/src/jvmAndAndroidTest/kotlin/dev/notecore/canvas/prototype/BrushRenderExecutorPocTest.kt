package dev.notecore.canvas.prototype

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Exactly the same real executor/renderer integration tests on JVM and Android ART. */
class BrushRenderExecutorPocTest {
    private val f = BrushTileImageFixture
    private fun submit(e: BrushRenderExecutorPoc, frame: FrameToken = f.frame, c: ChunkedBrushPoc = f.catalog()) =
        assertIs<BrushRenderAdmissionPoc.Accepted>(e.submit(c, frame, f.layer, f.grid, f.rgb, 65536, 8192)).job
    private fun ready(e: BrushRenderExecutorPoc, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (e.executorStatus().entries.count { it.stage == OwnedWorkStage.READY } < count) {
            check(System.nanoTime() < deadline) { "Worker did not become ready" }; Thread.sleep(1)
        }
    }
    private fun finish(e: BrushRenderExecutorPoc) {
        e.close(); assertTrue(e.awaitWorkerStop(5000)); e.drain()
    }

    @Test fun realWorkerProducesPixelsAndOwnerAcknowledgementSettlesPeak() {
        val ownerThread = Thread.currentThread().id; var workerThread = ownerThread
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 32768, 262144,
            beforeCompute = { workerThread = Thread.currentThread().id })
        try {
            val job = submit(e); ready(e, 1)
            assertEquals(job.peakBytes, e.chargedBytes); assertEquals(job.peakBytes, e.executorStatus().chargedBytes)
            assertEquals(BrushRenderAckPoc.PUBLISHED, e.drain().single().second)
            assertNotEquals(ownerThread, workerThread); assertEquals(16384, e.chargedBytes)
            assertEquals(0, e.executorStatus().chargedBytes)
            val reader = assertNotNull(e.borrow(job)); assertEquals(f.frame, reader.frame)
            assertTrue((0 until 2048).any { reader.byteAt(it * 4 + 3) == 51 })
            e.close(); assertFalse(e.isDrained); e.returnReader(reader)
        } finally { finish(e) }
        assertTrue(e.isDrained)
    }

    @Test fun runningQueuedReadyAndCancelledJobsKeepChargesUntilActualHandoff() {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 32768, 262144, 2, beforeCompute = {
            started.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        try {
            val first = submit(e); assertTrue(started.await(5, TimeUnit.SECONDS)); val second = submit(e)
            assertEquals(listOf(OwnedWorkStage.RUNNING, OwnedWorkStage.QUEUED), e.executorStatus().entries.map { it.stage })
            assertIs<BrushRenderAdmissionPoc.Rejected>(e.submit(f.catalog(), f.frame, f.layer, f.grid, f.rgb, 65536))
            assertTrue(e.cancel(first)); assertEquals(first.peakBytes + second.peakBytes, e.chargedBytes)
            e.close(); assertFalse(e.awaitWorkerStop(1)); assertEquals(first.peakBytes + second.peakBytes, e.chargedBytes)
            release.countDown(); assertTrue(e.awaitWorkerStop(5000))
            assertEquals(first.peakBytes + second.peakBytes, e.chargedBytes)
            assertTrue(e.drain().all { it.second == BrushRenderAckPoc.FAILED }); assertTrue(e.isDrained)
        } finally { release.countDown(); finish(e) }
    }

    @Test fun deliveryRetryDoesNotRecomputeOrReleaseReadyPayloadEarly() {
        val permit = AtomicBoolean(false); var computes = 0
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 32768, 262144,
            beforeCompute = { computes++ }, permitDelivery = { permit.get() })
        try {
            val job = submit(e); ready(e, 1)
            assertTrue(e.drain().isEmpty()); assertEquals(OwnedWorkStage.DELIVERY_BLOCKED, e.executorStatus().entries.single().stage)
            assertEquals(job.peakBytes, e.chargedBytes); assertNull(e.borrow(job))
            permit.set(true); assertTrue(e.retryDelivery(job))
            assertEquals(BrushRenderAckPoc.PUBLISHED, e.drain().single().second)
            assertEquals(1, computes); assertEquals(16384, e.chargedBytes)
        } finally { finish(e) }
        assertTrue(e.isDrained)
    }

    @Test fun completedOldViewportResultIsRetiredWhileNewFramePublishes() {
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 32768, 262144)
        try {
            val old = submit(e); ready(e, 1)
            val moved = f.frame.copy(viewportVersion = 2, requestSequence = 2)
            assertTrue(e.expect(moved, f.layer))
            assertEquals(BrushRenderAckPoc.DISCARDED, e.drain().single().second); assertNull(e.borrow(old))
            val current = submit(e, moved); ready(e, 1)
            assertEquals(BrushRenderAckPoc.PUBLISHED, e.drain().single().second)
            val reader = assertNotNull(e.borrow(current)); assertEquals(moved, reader.frame); e.returnReader(reader)
        } finally { finish(e) }
        assertTrue(e.isDrained)
    }

    @Test fun unknownComputeRemainsChargedUntilWorkerStopsAndOwnerReconciles() {
        val e = BrushRenderExecutorPoc(f.frame, f.layer, 32768, 262144, beforeCompute = { error("Injected before pure compute") })
        val job = submit(e)
        try {
            ready(e, 1); assertEquals(BrushRenderAckPoc.QUARANTINED, e.drain().single().second)
            assertEquals(job.peakBytes, e.chargedBytes); assertFalse(e.reconcileNoOutput(job))
            e.close(); assertTrue(e.awaitWorkerStop(5000)); assertFalse(e.isDrained)
            assertTrue(e.reconcileNoOutput(job)); assertEquals(0, e.chargedBytes); assertTrue(e.isDrained)
        } finally { finish(e) }
    }
}
