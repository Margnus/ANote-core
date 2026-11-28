package dev.notecore.canvas.prototype

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class StagedBrushExecutorTest {
    private val f = BrushFullFrameFixture
    private fun ready(e: BoundedOwnerExecutor, count: Int) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (e.status().entries.count { it.stage == OwnedWorkStage.READY } < count) {
            check(System.nanoTime() < end) { "Worker not ready" }; Thread.sleep(1)
        }
    }
    @Test fun closeDuringPartialAtlasAndBlockedDeliveryRetainChargeUntilExactAck() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slices = AtomicInteger(); var deliver = false
        val e = BrushRenderExecutorPoc(BrushPageImageFixture.frame, BrushPageImageFixture.layer,
            2L * 1024 * 1024, 4L * 1024 * 1024, beforeCompute = {
                if (slices.incrementAndGet() == 2) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            }, permitDelivery = { deliver })
        val job = assertIs<BrushRenderAdmissionPoc.Accepted>(e.submitPage(f.scene(), BrushPageImageFixture.frame,
            BrushPageImageFixture.layer, f.grid, 1024 * 1024, staged = true)).job
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertNull(e.borrow(job)); e.close(); assertFalse(e.awaitWorkerStop(1))
            assertEquals(job.peakBytes, e.chargedBytes)
            release.countDown(); assertTrue(e.awaitWorkerStop(10000)); assertTrue(e.drain().isEmpty())
            assertEquals(OwnedWorkStage.DELIVERY_BLOCKED, e.executorStatus().entries.single().stage)
            assertEquals(job.peakBytes, e.chargedBytes); assertEquals(2, slices.get())
            deliver = true; assertTrue(e.retryDelivery(job))
            assertEquals(BrushRenderAckPoc.FAILED, e.drain().single().second)
            assertNull(e.borrow(job)); assertTrue(e.isDrained); assertEquals(0, e.chargedBytes)
        } finally { release.countDown(); deliver = true; e.close(); e.awaitWorkerStop(10000); e.retryDelivery(job); e.drain() }
    }
    @Test fun storageRunsBetweenSlicesWithoutAdmissionGapOrEarlyAtlasPublication() {
        val shared = BoundedOwnerExecutor(2, 8L * 1024 * 1024, "staged-storage")
        val started = CountDownLatch(1); val go = CountDownLatch(1); val slices = AtomicInteger()
        val e = BrushRenderExecutorPoc(BrushPageImageFixture.frame, BrushPageImageFixture.layer, 2L * 1024 * 1024, 4L * 1024 * 1024,
            sharedExecutor = shared, beforeCompute = {
                if (slices.incrementAndGet() == 1) { started.countDown(); check(go.await(10, TimeUnit.SECONDS)) }
            })
        try {
            val job = assertIs<BrushRenderAdmissionPoc.Accepted>(e.submitPage(f.scene(), BrushPageImageFixture.frame, BrushPageImageFixture.layer,
                f.grid, 1024 * 1024, 16384, staged = true)).job
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val ticket = shared.status().entries.single().ticket
            var saved = false; var saveAtSlice = 0
            assertIs<WorkAdmission.Accepted>(shared.submit(17, {
                saveAtSlice = slices.get(); "saved"
            }) { result ->
                assertEquals("saved", assertIs<WorkResult.Succeeded<String>>(result).value)
                assertNull(e.borrow(job)); saved = true; ResultDisposition.ACKNOWLEDGED
            })
            assertEquals(job.peakBytes + 17, shared.status().chargedBytes)
            shared.stopAccepting(); go.countDown(); assertTrue(shared.awaitWorkerStop(10000))
            assertEquals(1, saveAtSlice); assertTrue(slices.get() > 1)
            assertEquals(ticket, shared.status().entries.first().ticket)
            assertEquals(job.peakBytes + 17, shared.status().chargedBytes)
            shared.drainResults(); assertTrue(saved)
            assertEquals(BrushRenderAckPoc.PUBLISHED, e.settleAfterDrain().single().second)
            val reader = assertNotNull(e.borrowNative(job)); f.assertSeam(reader)
            e.close(); assertFalse(e.isDrained)
            assertTrue(e.completeNativeRelease(reader, ResourceReleaseOutcome.RELEASED)); assertTrue(e.isDrained)
        } finally { go.countDown(); e.close(); shared.stopAccepting(); shared.awaitWorkerStop(10000); shared.drainResults(); e.settleAfterDrain() }
    }
    @Test fun unknownAfterACompletedSliceIsNotRetriedOrPrematurelyReleased() {
        val slices = AtomicInteger()
        val e = BrushRenderExecutorPoc(BrushPageImageFixture.frame, BrushPageImageFixture.layer, 2L * 1024 * 1024, 4L * 1024 * 1024,
            beforeCompute = { if (slices.incrementAndGet() == 2) error("after preparation") })
        val job = assertIs<BrushRenderAdmissionPoc.Accepted>(e.submitPage(f.scene(), BrushPageImageFixture.frame, BrushPageImageFixture.layer,
            f.grid, 1024 * 1024, staged = true)).job
        try {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (e.executorStatus().entries.single().stage != OwnedWorkStage.READY) {
                check(System.nanoTime() < end); Thread.sleep(1)
            }
            assertEquals(BrushRenderAckPoc.QUARANTINED, e.drain().single().second)
            assertEquals(2, slices.get()); assertEquals(job.peakBytes, e.chargedBytes); assertNull(e.borrow(job))
            assertFalse(e.reconcileNoOutput(job)); e.close(); assertTrue(e.awaitWorkerStop(10000))
            assertTrue(e.reconcileNoOutput(job)); assertTrue(e.isDrained)
        } finally { e.close(); e.awaitWorkerStop(10000); e.drain() }
    }
}
