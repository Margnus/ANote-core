package dev.notecore.canvas.prototype

import kotlin.test.*

class StagedBrushPagePocTest {
    private val frame = FrameToken(SceneId("staged-original"), 1, 1, 1, 1, 1)
    private val layer = LayerId("ink")
    private val grid = BrushFrameGridPoc(PointD(-2.0, -3.0), 67, 39, samplesPerAxis = 2)
    private fun scene(): SceneSnapshot = SceneSnapshot.restored(frame.sceneId, 1, List(300) { i ->
        val x = (i % 17) * 4.0; val y = (i / 17) * 2.0
        StrokeObject(ObjectId("s-$i"), 1, layer, BrushSpec(BrushKind.MONOLINE, 1, 2.0 + i % 3,
            if (i % 2 == 0) 0x804422aa.toInt() else 0xccee8855.toInt()),
            StrokeSamples.copyOf(doubleArrayOf(x, x + 1, x - 2), doubleArrayOf(y, y + 3, y + 5),
                floatArrayOf(.4f, .8f, .5f), longArrayOf(1, 2, 3)),
            if (i % 3 == 0) Affine2D(1.0, .1, -.1, 1.0) else Affine2D())
    })
    private fun owner() = BrushRenderOwnerPoc(frame, layer, 2L * 1024 * 1024, 4L * 1024 * 1024)
    private fun admit(o: BrushRenderOwnerPoc, scene: SceneSnapshot = scene(), maxWork: Long = 20_000_000) =
        assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(scene, frame, layer, grid, 1024 * 1024,
            maxEdgeTests = maxWork, staged = true)).job

    @Test fun stagedAtlasMatchesIndependentColdPartitionsAndNeverExposesPartialPixels() {
        val scene = scene(); val o = owner(); val job = admit(o, scene)
        assertEquals("page-input-budget", assertIs<BrushRenderAdmissionPoc.Rejected>(o.admitPage(scene, frame, layer, grid)).reason)
        assertEquals(900 * 28L + 300 * 8L + StagedBrushPagePoc.planningBytes(300) + 1024 * 1024 + 67 * 39 * 4, job.peakBytes)
        var result: BrushRenderComputationPoc
        do {
            result = job.executeSlice()
            assertNull(o.borrow(job)); assertEquals(job.peakBytes, o.chargedBytes)
            if (result.more) { assertNull(result.pixels); assertFalse(o.receive(job, WorkResult.Succeeded(result))) }
        } while (result.more)
        assertNull(result.failure); assertTrue(job.completedPageSlices > 300)
        assertTrue(o.receive(job, WorkResult.Succeeded(result)))
        assertEquals(BrushRenderAckPoc.PUBLISHED, o.acknowledge(job))
        val reader = assertNotNull(o.borrow(job))
        val oracle = BrushTilePixelsPoc.Companion.Composition(frame, layer, grid)
        // No page index, no staged candidate list, and different partition boundaries.
        scene.forEachObject { stroke ->
            val catalog = assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(frame, stroke)).catalog
            for (y in 0 until grid.height step 23) for (x in 0 until grid.width step 23) {
                val tile = BrushTileGridPoc(PointD(grid.origin.x + x, grid.origin.y + y), minOf(23, grid.width - x),
                    minOf(23, grid.height - y), samplesPerAxis = 2)
                val mask = assertIs<BrushTileBuild.Ready>(catalog.renderSwept(frame, tile)).tile
                assertTrue(oracle.over(mask, stroke.brush.argb and 0xffffff, { false }, x, y))
            }
        }
        val expected = oracle.finish()
        repeat(expected.logicalBytes.toInt()) { assertEquals(expected.byteAt(it), reader.byteAt(it), "byte $it") }
        o.close(); assertFalse(o.isDrained); o.returnReader(reader); assertTrue(o.isDrained)
    }
    @Test fun partialCancellationWorkFailureAndOldViewportKeepWholeReservationUntilAck() {
        val o = owner(); val cancelled = admit(o)
        repeat(8) { assertTrue(cancelled.executeSlice().more) }
        assertTrue(cancelled.pageWork > grid.width * grid.height * 4)
        val failure = cancelled.executeSlice { true }
        assertEquals("cancelled", failure.failure); assertNull(failure.pixels)
        assertEquals(cancelled.peakBytes, o.chargedBytes)
        assertTrue(o.receive(cancelled, WorkResult.Succeeded(failure)))
        assertEquals(BrushRenderAckPoc.FAILED, o.acknowledge(cancelled)); assertEquals(0, o.chargedBytes)
        val limited = admit(o, maxWork = 20_000)
        var result: BrushRenderComputationPoc
        do { result = limited.executeSlice() } while (result.more)
        assertNotNull(result.failure); assertNull(result.pixels)
        o.receive(limited, WorkResult.Succeeded(result)); assertEquals(BrushRenderAckPoc.FAILED, o.acknowledge(limited))
        val late = admit(o); assertTrue(late.executeSlice().more)
        assertTrue(o.expect(frame.copy(requestSequence = 2, viewportVersion = 2), layer))
        do { result = late.executeSlice() } while (result.more)
        assertNotNull(result.pixels)
        o.receive(late, WorkResult.Succeeded(result)); assertEquals(BrushRenderAckPoc.DISCARDED, o.acknowledge(late))
        assertNull(o.borrow(late)); assertEquals(0, o.chargedBytes); o.close(); assertTrue(o.isDrained)
    }
    @Test fun stagedPreparationIsBudgetedAndEmptyPageCompletesWithoutAlternateScene() {
        val tight = BrushRenderOwnerPoc(frame, layer, 2L * 1024 * 1024, 2L * 1024 * 1024)
        val refused = tight.admitPage(scene(), frame, layer, grid, 2L * 1024 * 1024, staged = true)
        assertIs<BrushRenderAdmissionPoc.Rejected>(refused); assertEquals(0, tight.chargedBytes)
        tight.close(); assertTrue(tight.isDrained)
        val o = owner(); val empty = SceneSnapshot.empty(frame.sceneId)
        val j = assertIs<BrushRenderAdmissionPoc.Accepted>(o.admitPage(
            SceneSnapshot.restored(empty.id, 1, emptyList()), frame, layer, grid, staged = true)).job
        var result: BrushRenderComputationPoc
        do { result = j.executeSlice() } while (result.more)
        o.receive(j, WorkResult.Succeeded(result)); assertEquals(BrushRenderAckPoc.PUBLISHED, o.acknowledge(j))
        val reader = assertNotNull(o.borrow(j)); repeat(grid.width * grid.height * 4) { assertEquals(0, reader.byteAt(it)) }
        o.returnReader(reader); o.close(); assertTrue(o.isDrained)
    }
}
