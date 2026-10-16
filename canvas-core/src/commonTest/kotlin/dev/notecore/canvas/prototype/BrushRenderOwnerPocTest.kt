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

    @Test fun completePageComposesCanonicalOrderAndKeepsNativeReaderAfterClose() {
        val page = BrushPageImageFixture
        for (reverse in listOf(false, true)) {
            val o = owner()
            val j = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(page.scene(reverse), f.frame, f.layer,
                f.grid, working, 16384)).job
            val result = WorkResult.Succeeded(j.execute())
            assertNull(o.borrow(j)); assertEquals(j.peakBytes, o.chargedBytes)
            o.receive(j, result); assertEquals(BrushRenderAckPoc.PUBLISHED, o.acknowledge(j))
            val reader = assertNotNull(o.borrowNative(j)); page.assertCenter(reader, reverse)
            assertNull(o.borrowNative(j)); o.close(); assertFalse(o.isDrained); assertEquals(24576, o.chargedBytes)
            assertFalse(o.canPublish(reader)); assertTrue(o.completeNativeRelease(reader, ResourceReleaseOutcome.RELEASED))
            assertTrue(o.isDrained); assertEquals(0, o.chargedBytes)
        }
    }

    @Test fun laterStrokeFailureAndCancellationNeverPublishPartialPage() {
        val page = BrushPageImageFixture; val o = owner()
        val catalog = assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(f.frame, page.red)).catalog
        val first = assertIs<BrushTileBuild.Ready>(catalog.render(f.frame, f.grid)).edgeTests
        val j = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(page.scene(), f.frame, f.layer, f.grid,
            working, maxEdgeTests = first + f.grid.width * f.grid.height * 8L + 1)).job
        val output = j.execute(); assertNull(output.pixels); assertEquals("raster-work-budget", output.failure)
        o.receive(j, WorkResult.Succeeded(output)); assertEquals(j.peakBytes, o.chargedBytes)
        assertEquals(BrushRenderAckPoc.FAILED, o.acknowledge(j)); assertNull(o.borrow(j)); assertEquals(0, o.chargedBytes)
        val cancelled = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(page.scene(), f.frame, f.layer, f.grid, working)).job
        val c = cancelled.execute { true }; assertNull(c.pixels); assertEquals("cancelled", c.failure)
        o.receive(cancelled, WorkResult.Succeeded(c)); assertEquals(BrushRenderAckPoc.FAILED, o.acknowledge(cancelled))
        // Count the cancellation probes for a fully composed first stroke, then interrupt the
        // second stroke at the same next probe. This exercises an already nonempty accumulator.
        val singleScene = SceneSnapshot.restored(f.frame.sceneId, f.frame.sceneRevision, listOf(page.red))
        val single = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(singleScene, f.frame, f.layer, f.grid, working)).job
        var firstChecks = 0
        val firstOutput = single.execute { firstChecks++; false }
        assertNotNull(firstOutput.pixels); o.receive(single, WorkResult.Succeeded(firstOutput)); o.acknowledge(single)
        val later = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(page.scene(), f.frame, f.layer, f.grid, working)).job
        var checks = 0
        val interrupted = later.execute { ++checks >= firstChecks }
        assertEquals(firstChecks, checks); assertNull(interrupted.pixels); assertEquals("cancelled", interrupted.failure)
        o.receive(later, WorkResult.Succeeded(interrupted)); assertEquals(BrushRenderAckPoc.FAILED, o.acknowledge(later))
        assertNull(o.borrow(later))
        o.close(); assertTrue(o.isDrained)
    }

    @Test fun emptyPageIsCompleteAndAdmissionRejectsUnsupportedOrWrongContent() {
        val page = BrushPageImageFixture; val o = owner()
        val empty = SceneSnapshot.restored(f.frame.sceneId, f.frame.sceneRevision, listOf(page.foreign))
        val j = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(empty, f.frame, f.layer, f.grid, working)).job
        assertEquals(BrushRenderAckPoc.PUBLISHED, complete(o, j))
        val reader = assertNotNull(o.borrow(j)); repeat(8192) { assertEquals(0, reader.byteAt(it)) }; o.returnReader(reader)
        val unsupported = SceneSnapshot.restored(f.frame.sceneId, f.frame.sceneRevision,
            listOf(page.red.copy(brush = page.red.brush.copy(kind = BrushKind.PRESSURE))))
        assertEquals("unsupported-page-brush", assertIs<BrushRenderAdmissionPoc.Rejected>(o.admitPage(unsupported, f.frame, f.layer, f.grid, working)).reason)
        assertIs<BrushRenderAdmissionPoc.Rejected>(o.admitPage(page.scene(), f.frame.copy(sceneRevision = f.frame.sceneRevision + 1), f.layer, f.grid, working))
        assertIs<BrushRenderAdmissionPoc.Rejected>(o.admitPage(page.scene(), f.frame, LayerId("other"), f.grid, working))
        o.close(); assertTrue(o.isDrained)
    }

    @Test fun fullSceneRetentionIsChargedAndLateCompletePageIsDiscarded() {
        val page = BrushPageImageFixture; val o = owner()
        val j = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(page.scene(), f.frame, f.layer, f.grid, working, 16384)).job
        assertEquals(3 * 28 + 3 * 8 + working + 8192 + 16384, j.peakBytes)
        val result = WorkResult.Succeeded(j.execute())
        assertTrue(o.expect(f.frame.copy(viewportVersion = 2, requestSequence = 2), f.layer))
        o.receive(j, result); assertEquals(BrushRenderAckPoc.DISCARDED, o.acknowledge(j)); assertNull(o.borrow(j))
        assertEquals(0, o.chargedBytes); assertFailsWith<IllegalStateException> { j.execute() }
        val strokes = List(257) { page.red.copy(id = ObjectId("bounded-$it")) }
        val tooMany = SceneSnapshot.restored(f.frame.sceneId, f.frame.sceneRevision, strokes)
        assertEquals("page-input-budget", assertIs<BrushRenderAdmissionPoc.Rejected>(o.admitPage(tooMany,
            f.frame.copy(viewportVersion = 2, requestSequence = 2), f.layer, f.grid, working)).reason)
        assertEquals(0, o.chargedBytes); o.close(); assertTrue(o.isDrained)
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


/** Canonical scene fixture shared by actual ART/Native tests, with a foreign layer to exclude. */
internal object BrushPageImageFixture {
    val frame = BrushTileImageFixture.frame
    val layer = BrushTileImageFixture.layer
    val grid = BrushTileImageFixture.grid
    private val dot = StrokeSamples.copyOf(doubleArrayOf(8.0), doubleArrayOf(12.0), floatArrayOf(1f), longArrayOf(0))
    val red = StrokeObject(ObjectId("page-red"), 1, layer, BrushSpec(BrushKind.MONOLINE, 1, 10.0, 0x80ff0000.toInt()), dot)
    val blue = red.copy(id = ObjectId("page-blue"), brush = red.brush.copy(argb = 0x800000ff.toInt()))
    val foreign = red.copy(id = ObjectId("other-page"), layerId = LayerId("other-page"), brush = red.brush.copy(argb = 0xff00ff00.toInt()))
    fun scene(reverse: Boolean = false) = SceneSnapshot.restored(frame.sceneId, frame.sceneRevision,
        if (reverse) listOf(blue, red, foreign) else listOf(red, blue, foreign))
    fun assertCenter(reader: BrushRenderReaderPoc, reversed: Boolean = false) {
        val offset = (8 * grid.width + 15) * 4
        assertEquals(if (reversed) 128 else 64, reader.byteAt(offset))
        assertEquals(0, reader.byteAt(offset + 1))
        assertEquals(if (reversed) 64 else 128, reader.byteAt(offset + 2))
        assertEquals(192, reader.byteAt(offset + 3))
    }
}
