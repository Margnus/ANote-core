package dev.notecore.canvas.prototype

import kotlin.test.*

class BrushRenderOwnerPocTest {
    private val f = BrushTileImageFixture
    private val working = 65536L
    private fun owner(total: Long = 262144, max: Int = 4) =
        BrushRenderOwnerPoc(f.frame, f.layer, 32768, total, max)
    private fun job(owner: BrushRenderOwnerPoc, frame: FrameToken = f.frame, c: ChunkedBrushPoc = f.catalog(), native: Long = 8192) =
        assertIs<BrushRenderAdmissionPoc.Accepted>(owner.admit(c, frame, f.layer, f.grid, f.rgb, working, native)).job
    private fun complete(owner: BrushRenderOwnerPoc, job: BrushRenderJobPoc): BrushRenderAckPoc {
        assertTrue(owner.receive(job, WorkResult.Succeeded(job.execute())))
        return owner.acknowledge(job)
    }

    @Test fun actualMaskAndRgbaStayPeakChargedUntilExecutorAcknowledges() {
        val o = owner(); val job = job(o)
        assertTrue(job.peakBytes > 32768); assertEquals(job.peakBytes, o.chargedBytes)
        val result = WorkResult.Succeeded(job.execute())
        assertTrue(o.receive(job, result)); assertTrue(o.receive(job, result))
        assertEquals(job.peakBytes, o.chargedBytes); assertNull(o.borrow(job))
        assertEquals(BrushRenderAckPoc.PUBLISHED, o.acknowledge(job)); assertEquals(16384, o.chargedBytes)
        val reader = assertNotNull(o.borrow(job)); assertEquals(f.frame, reader.frame)
        assertTrue(o.canPublish(reader)); assertTrue(o.expect(f.frame, f.layer))
        assertNotNull(o.borrow(job)).also { o.returnReader(it) }
        val oracle = assertIs<BrushTileBuild.Ready>(f.catalog().render(f.frame, f.grid)).tile
        for (i in 0 until 2048) assertEquals(oracle.alphaByte(i % 64, i / 64), reader.byteAt(i * 4 + 3))
        assertFailsWith<IllegalStateException> { job.execute() }
        o.close(); assertFalse(o.canPublish(reader)); assertFalse(o.isDrained); assertEquals(16384, o.retiredBytes)
        o.returnReader(reader); assertTrue(o.isDrained); assertEquals(0, o.chargedBytes)
    }

    @Test fun viewportChangeAndCancellationNeverReleaseQueuedOrDeliveredWorkEarly() {
        val o = owner(); val a = job(o); val b = job(o)
        assertTrue(o.cancel(a)); assertEquals(a.peakBytes + b.peakBytes, o.chargedBytes)
        val moved = f.frame.copy(viewportVersion = 2, requestSequence = 2)
        assertTrue(o.expect(moved, f.layer))
        assertEquals(BrushRenderAckPoc.DISCARDED, complete(o, a))
        assertEquals(b.peakBytes, o.chargedBytes)
        assertEquals(BrushRenderAckPoc.DISCARDED, complete(o, b)); assertEquals(0, o.chargedBytes)
        val current = job(o, moved)
        assertEquals(BrushRenderAckPoc.PUBLISHED, complete(o, current)); o.close(); assertTrue(o.isDrained)
    }

    @Test fun closeKeepsInFlightReservationAndLatePixelsNeverBecomeResident() {
        val o = owner(); val j = job(o); val output = WorkResult.Succeeded(j.execute())
        o.close(); assertEquals(j.peakBytes, o.chargedBytes); assertFalse(o.isDrained)
        assertTrue(o.receive(j, output)); assertEquals(j.peakBytes, o.chargedBytes)
        assertEquals(BrushRenderAckPoc.DISCARDED, o.acknowledge(j)); assertNull(o.borrow(j)); assertTrue(o.isDrained)
    }

    @Test fun jobAndTotalBudgetsIncludeReadyResultsAndOldBorrowedOutput() {
        val maxOne = owner(max = 1); val first = job(maxOne)
        assertIs<BrushRenderAdmissionPoc.Rejected>(maxOne.admit(f.catalog(), f.frame, f.layer, f.grid, f.rgb, working))
        assertTrue(maxOne.receive(first, WorkResult.Succeeded(first.execute())))
        assertEquals(1, maxOne.pendingJobs); assertTrue(maxOne.rejectDispatch(first).not())
        maxOne.acknowledge(first); maxOne.close(); assertTrue(maxOne.isDrained)

        val c = f.catalog(); val peak = c.inputPayloadBytes + working + 8192
        val o = owner(total = peak + 8192)
        val a = job(o, c = c, native = 0); complete(o, a)
        val held = assertNotNull(o.borrow(a)); val moved = f.frame.copy(viewportVersion = 2, requestSequence = 2)
        o.expect(moved, f.layer); assertFalse(o.canPublish(held)); assertEquals(8192, o.retiredBytes)
        val b = job(o, moved, c, 0)
        assertEquals(peak + 8192, o.chargedBytes)
        assertIs<BrushRenderAdmissionPoc.Rejected>(o.admit(c, moved, f.layer, f.grid, f.rgb, working))
        o.returnReader(held); complete(o, b); o.close(); assertTrue(o.isDrained)
    }

    @Test fun pureRenderFailuresCooperativeCancellationAndRejectedDispatchReleaseAfterAck() {
        val o = owner()
        val rejected = job(o); assertTrue(o.rejectDispatch(rejected)); assertEquals(0, o.chargedBytes)
        assertFailsWith<IllegalStateException> { rejected.execute() }
        val cancelled = job(o); val output = WorkResult.Succeeded(cancelled.execute { true })
        o.receive(cancelled, output); assertEquals(cancelled.peakBytes, o.chargedBytes)
        assertEquals(BrushRenderAckPoc.FAILED, o.acknowledge(cancelled))
        val budget = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admit(f.catalog(), f.frame, f.layer, f.grid, f.rgb, working, maxEdgeTests = 1)).job
        assertEquals(BrushRenderAckPoc.FAILED, complete(o, budget)); assertEquals(0, o.chargedBytes)
        o.close(); assertTrue(o.isDrained)
    }

    @Test fun unknownOutcomeStaysQuarantinedUntilExplicitReconciliation() {
        val o = owner(); val j = job(o)
        o.receive(j, WorkResult.OutcomeUnknown("injected", "No automatic re-execution"))
        assertEquals(BrushRenderAckPoc.QUARANTINED, o.acknowledge(j))
        o.close(); assertFalse(o.isDrained); assertEquals(j.peakBytes, o.chargedBytes)
        assertTrue(o.reconcileNoOutput(j)); assertFalse(o.reconcileNoOutput(j)); assertTrue(o.isDrained)
    }

    @Test fun foreignResultsTokensAndWrongSceneCannotDischargeAnotherOwner() {
        val a = owner(); val b = owner(); val ja = job(a); val jb = job(b)
        val result = WorkResult.Succeeded(jb.execute())
        assertFalse(a.receive(ja, result)); assertFalse(a.receive(jb, result))
        assertEquals(BrushRenderAckPoc.NOT_READY, a.acknowledge(jb)); assertEquals(ja.peakBytes, a.chargedBytes)
        assertIs<BrushRenderAdmissionPoc.Rejected>(a.admit(f.catalog(), f.frame.copy(sceneId = SceneId("foreign")), f.layer, f.grid, f.rgb, working))
        assertTrue(a.rejectDispatch(ja)); b.receive(jb, result); b.acknowledge(jb)
        a.close(); b.close(); assertTrue(a.isDrained && b.isDrained)
    }

    @Test fun peakReservationCannotPublishBeforeSettlementOrExceedOutputLimit() {
        val pool = ResourceLeasePool<String, ByteArray>(16, 128, releaseLane = ResourceLane.OWNER)
        val r = assertIs<ReservationResult.Reserved>(pool.reservePeak(100, 8)).reservation
        assertEquals(ResourcePublication.UnsettledReservation, pool.publish(r, "early", byteArrayOf(1)))
        assertEquals(100, pool.chargedBytes); assertNull(pool.settleReservation(r, 9))
        val settled = assertNotNull(pool.settleReservation(r, 8)); assertEquals(8, pool.chargedBytes)
        assertEquals(ResourcePublication.StaleReservation, pool.publish(r, "old", byteArrayOf(1)))
        assertIs<ResourcePublication.Retired>(pool.publishRetired(settled, byteArrayOf(2)))
        val release = assertIs<ResourceReleaseDispatch.Ready<ByteArray>>(pool.nextRelease(ResourceLane.OWNER)).ticket
        pool.completeRelease(release, ResourceReleaseOutcome.RELEASED); pool.close(); assertTrue(pool.isDrained)
        val o = owner()
        assertEquals("charge-overflow", assertIs<BrushRenderAdmissionPoc.Rejected>(o.admit(f.catalog(), f.frame, f.layer, f.grid, f.rgb, Long.MAX_VALUE)).reason)
        o.close(); assertTrue(o.isDrained)
    }

    @Test fun nativeCopyAllowanceIsExclusiveAndUnknownReleaseKeepsItsLease() {
        val o = owner(); val j = job(o); complete(o, j)
        val cpu = assertNotNull(o.borrow(j)); assertEquals(0, cpu.nativeCopyAllowanceBytes)
        val native = assertNotNull(o.borrowNative(j)); assertEquals(8192, native.nativeCopyAllowanceBytes)
        assertNull(o.borrowNative(j)); assertFailsWith<IllegalStateException> { o.returnReader(native) }
        assertFalse(o.completeNativeRelease(native, ResourceReleaseOutcome.NOT_RELEASED)); assertNull(o.borrowNative(j))
        assertFalse(o.completeNativeRelease(native, ResourceReleaseOutcome.UNKNOWN))
        assertFalse(o.completeNativeRelease(native, ResourceReleaseOutcome.RELEASED))
        o.returnReader(cpu); o.close(); assertEquals(16384, o.chargedBytes); assertFalse(o.isDrained)
        assertTrue(o.reconcileNativeReleased(native)); assertFalse(o.reconcileNativeReleased(native))
        assertTrue(o.isDrained); assertEquals(0, o.chargedBytes)
    }
}
