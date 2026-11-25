package dev.notecore.canvas.prototype

import kotlin.test.*

class SweptDiscCacheIdentityTest {
    private val frame = FrameToken(SceneId("swept-cache"), 2, 1, 1, 1, 1)
    private val layer = LayerId("ink")
    private val grid = BrushTileGridPoc(PointD(0.0, 8.0), 32, 16)
    private val bytes = 2_048L
    private val working = 65_536L

    private fun samples(y: Double = 16.0) = StrokeSamples.copyOf(
        doubleArrayOf(4.0, 28.0, 4.0), DoubleArray(3) { y }, FloatArray(3) { 1f }, longArrayOf(0, 1, 2))
    private fun stroke() = StrokeObject(ObjectId("stroke"), 1, layer,
        BrushSpec(BrushKind.HIGHLIGHTER, 1, 8.0, -1), samples())
    private fun catalog(stroke: StrokeObject, token: FrameToken = frame) =
        assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(token, stroke)).catalog

    @Test fun rebuiltCatalogHitsOnlyForSameContentAndRebindsFrame() {
        val s = stroke()
        val cache = BrushTileCachePoc(bytes * 16, working + bytes * 16)
        fun acquire(value: StrokeObject, token: FrameToken = frame, tile: BrushTileGridPoc = grid,
            maxEdgeTests: Long = 20_000_000,
        ) = assertIs<CachedBrushTileBuild.Ready>(
            cache.acquireSwept(catalog(value, token), token, value.layerId, tile,
                maxWorkingBytes = working, maxEdgeTests = maxEdgeTests))
        val first = acquire(s)
        assertFalse(first.cacheHit)
        assertEquals(127, first.reader.alphaByte(16, 8))
        val current = frame.copy(sceneRevision = 3, viewportVersion = 2, requestSequence = 2)
        val reused = acquire(s.copy(), current, maxEdgeTests = 1)
        assertTrue(reused.cacheHit)
        assertEquals(127, reused.reader.alphaByte(16, 8))
        val gate = FramePublicationGate(current)
        assertFalse(first.reader.canPublish(gate, layer))
        assertTrue(reused.reader.canPublish(gate, layer))
        cache.returnReader(first.reader)
        cache.returnReader(reused.reader)

        val cases = listOf(
            s.copy(transform = Affine2D(ty = 6.0)),
            s.copy(samples = samples(10.0)), // Same ID and revision, different immutable samples.
            s.copy(brush = s.brush.copy(argb = 0x80336699.toInt())),
            s.copy(layerId = LayerId("another-layer")),
            s.copy(objectRevision = 2),
            s.copy(id = ObjectId("other-stroke")),
        )
        for (edited in cases) {
            val result = acquire(edited, current)
            assertFalse(result.cacheHit)
            if (edited.transform != s.transform) assertEquals(0, result.reader.alphaByte(16, 8))
            if (edited.brush != s.brush) assertEquals(64, result.reader.alphaByte(16, 8))
            cache.returnReader(result.reader)
        }
        for (token in listOf(current.copy(sessionGeneration = 2), current.copy(sceneId = SceneId("other-page")))) {
            val other = acquire(s, token)
            assertFalse(other.cacheHit)
            cache.returnReader(other.reader)
        }
        val shifted = acquire(s, current, tile = grid.copy(origin = PointD(0.25, 8.0)))
        assertFalse(shifted.cacheHit)
        cache.returnReader(shifted.reader)
        val legacy = assertIs<CachedBrushTileBuild.Ready>(cache.acquire(catalog(s), frame, layer, grid, sceneTolerance = 0.125))
        assertFalse(legacy.cacheHit)
        assertEquals(64, legacy.reader.alphaByte(16, 8))
        cache.returnReader(legacy.reader)
        cache.close()
        assertTrue(cache.isDrained)
    }

    @Test fun synchronousMissChargesPeakAlongsideRetiredMasks() {
        val cache = BrushTileCachePoc(bytes, working + bytes)
        val c = catalog(stroke())
        fun acquire(x: Double, cancelled: () -> Boolean = { false }) =
            cache.acquireSwept(c, frame, layer, grid.copy(origin = PointD(x, 8.0)),
                maxWorkingBytes = working, cancelled = cancelled)
        val first = assertIs<CachedBrushTileBuild.Ready>(acquire(0.0))
        var observedPeak = 0L
        val second = assertIs<CachedBrushTileBuild.Ready>(acquire(8.0) {
            observedPeak = maxOf(observedPeak, cache.chargedBytes)
            false
        })
        assertEquals(working + bytes, observedPeak)
        assertEquals(bytes, cache.retiredBytes)
        assertEquals(2 * bytes, cache.chargedBytes)
        assertEquals("cache-total_limit", assertIs<CachedBrushTileBuild.Rejected>(acquire(16.0)).reason)
        assertEquals(127, first.reader.alphaByte(16, 8))
        cache.returnReader(first.reader)
        val third = assertIs<CachedBrushTileBuild.Ready>(acquire(16.0))
        assertFalse(third.cacheHit)
        cache.returnReader(second.reader)
        cache.returnReader(third.reader)
        cache.close()
        assertTrue(cache.isDrained)
        println("Swept cache: peak=$observedPeak retired=$bytes drained")
    }
}
