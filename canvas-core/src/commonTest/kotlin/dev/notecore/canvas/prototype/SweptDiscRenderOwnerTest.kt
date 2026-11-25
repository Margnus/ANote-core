package dev.notecore.canvas.prototype

import kotlin.test.*

class SweptDiscRenderOwnerTest {
    @Test fun sweptTilePeakAndRetiredReaderShareOneTotalBudget() {
        val frame = FrameToken(SceneId("swept-residency"), 1, 1, 1, 1, 1)
        val layer = LayerId("ink")
        val grid = BrushTileGridPoc(PointD(0.0, 0.0), 32, 16)
        val edges = SweptDiscGeometryPoc.SEGMENT_EDGES
        val xs = DoubleArray(2 * edges + 1) { 4.0 + minOf(it, 2 * edges - it) * 0.125 }
        val samples = StrokeSamples.copyOf(xs, DoubleArray(xs.size) { 8.0 },
            FloatArray(xs.size) { 1f }, LongArray(xs.size) { it.toLong() })
        val stroke = StrokeObject(ObjectId("retrace"), 1, layer,
            BrushSpec(BrushKind.HIGHLIGHTER, 1, 8.0, -1), samples)
        val catalog = assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(frame, stroke)).catalog
        val working = 65_536L
        val allowance = 2_048L
        val retained = grid.width * grid.height * 4L + allowance
        val peak = catalog.inputPayloadBytes + working + retained
        val total = peak + retained
        val owner = BrushRenderOwnerPoc(frame, layer, retained, total)
        fun admit() = owner.admitSwept(catalog, frame, layer, grid, 0x336699, working, allowance)
        fun acknowledge(job: BrushRenderJobPoc, expectedCharge: Long) {
            assertEquals(peak, job.peakBytes)
            val output = job.execute()
            assertNotNull(output.pixels)
            assertTrue(owner.receive(job, WorkResult.Succeeded(output)))
            assertEquals(expectedCharge, owner.chargedBytes)
            assertNull(owner.borrow(job))
            assertEquals(BrushRenderAckPoc.PUBLISHED, owner.acknowledge(job))
        }

        val first = assertIs<BrushRenderAdmissionPoc.Accepted>(admit()).job
        acknowledge(first, peak)
        val reader = assertNotNull(owner.borrowNative(first))
        val centerAlpha = (8 * grid.width + 16) * 4 + 3
        assertEquals(127, reader.byteAt(centerAlpha)) // Two swept segments, one highlighter opacity.
        val second = assertIs<BrushRenderAdmissionPoc.Accepted>(admit()).job
        acknowledge(second, total)
        assertEquals(retained, owner.retiredBytes)
        assertEquals(2 * retained, owner.chargedBytes)
        assertNull(owner.borrow(first))
        assertEquals(127, reader.byteAt(centerAlpha))
        assertEquals("pool-total_limit", assertIs<BrushRenderAdmissionPoc.Rejected>(admit()).reason)
        assertFalse(owner.completeNativeRelease(reader, ResourceReleaseOutcome.UNKNOWN))
        assertEquals("pool-total_limit", assertIs<BrushRenderAdmissionPoc.Rejected>(admit()).reason)
        assertEquals(2 * retained, owner.chargedBytes)
        assertTrue(owner.reconcileNativeReleased(reader))
        assertEquals(retained, owner.chargedBytes)

        val third = assertIs<BrushRenderAdmissionPoc.Accepted>(admit()).job
        assertEquals(total, owner.chargedBytes)
        assertTrue(owner.rejectDispatch(third))
        assertEquals(retained, owner.chargedBytes)
        owner.close()
        assertTrue(owner.isDrained)
        assertEquals(0, owner.chargedBytes)
        println("Swept owner: peak=$peak retained=$retained sharedBudget=$total retiredChargeAfterEviction=$retained")
    }
}
