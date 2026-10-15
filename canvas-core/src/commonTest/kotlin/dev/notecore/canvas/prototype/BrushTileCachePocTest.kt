package dev.notecore.canvas.prototype

import kotlin.test.*

class BrushTileCachePocTest {
    private val frame = FrameToken(SceneId("cache-page"), 7, 1, 1, 1, 1)
    private val layer = LayerId("ink")
    private val grid = BrushTileGridPoc(PointD(0.0, 8.0), 32, 16)
    private val bytes = 32 * 16 * 4L
    private fun catalog(sourceFrame: FrameToken = frame, width: Double = 8.0, segments: Int = 1): ChunkedBrushPoc {
        val s = StrokeObject(ObjectId("stroke"), 2, layer, BrushSpec(BrushKind.HIGHLIGHTER, 1, width, -1),
            StrokeSamples.copyOf(doubleArrayOf(4.0, 28.0, 4.0), doubleArrayOf(16.0, 16.0, 16.0),
                floatArrayOf(1f, 1f, 1f), longArrayOf(0, 1, 2)))
        return assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(sourceFrame, s, segments)).catalog
    }
    private fun ready(cache: BrushTileCachePoc, c: ChunkedBrushPoc, f: FrameToken = frame,
        g: BrushTileGridPoc = grid) = assertIs<CachedBrushTileBuild.Ready>(cache.acquire(c, f, layer, g))

    @Test fun viewportReuseSharesPixelsButRequiresFreshPublicationToken() {
        val cache = BrushTileCachePoc(bytes, bytes * 2); val c = catalog()
        val first = ready(cache, c); assertFalse(first.cacheHit)
        val moved = frame.copy(viewportVersion = 2, surfaceGeneration = 2, requestSequence = 2)
        // Tiny work allowance proves a hit performs no geometry/raster generation.
        val hit = assertIs<CachedBrushTileBuild.Ready>(cache.acquire(c, moved, layer, grid, maxEdgeTests = 1))
        assertTrue(hit.cacheHit); assertEquals(bytes, cache.chargedBytes); assertEquals(bytes, cache.borrowedBytes)
        assertEquals(64, hit.reader.alphaByte(16, 8)); assertEquals(2, cache.outstandingReaders)
        val gate = FramePublicationGate(moved)
        assertFalse(first.reader.canPublish(gate, layer)); assertTrue(hit.reader.canPublish(gate, layer))
        assertFalse(hit.reader.canPublish(gate, LayerId("other")))
        gate.close(); assertFalse(hit.reader.canPublish(gate, layer))
        cache.returnReader(first.reader); cache.returnReader(hit.reader); cache.close(); assertTrue(cache.isDrained)
    }

    @Test fun retiredReadersKeepBudgetAndRejectionPreservesCurrentResident() {
        val cache = BrushTileCachePoc(bytes, bytes * 2); val c = catalog()
        val old = ready(cache, c)
        val rightGrid = grid.copy(origin = PointD(32.0, 8.0))
        val current = ready(cache, c, g = rightGrid)
        assertEquals(bytes * 2, cache.chargedBytes); assertEquals(bytes, cache.retiredBytes)
        val rejected = assertIs<CachedBrushTileBuild.Rejected>(cache.acquire(c, frame, layer, grid.copy(origin = PointD(64.0, 8.0))))
        assertEquals("cache-total_limit", rejected.reason)
        val hit = ready(cache, c, g = rightGrid); assertTrue(hit.cacheHit)
        assertEquals(64, old.reader.alphaByte(16, 8)) // Still usable after LRU retirement.
        cache.returnReader(old.reader); assertEquals(bytes, cache.chargedBytes); assertEquals(0, cache.retiredBytes)
        cache.returnReader(current.reader); cache.returnReader(hit.reader)
        val third = ready(cache, c, g = grid.copy(origin = PointD(64.0, 8.0)))
        assertEquals(bytes, cache.chargedBytes)
        cache.returnReader(third.reader); cache.close(); assertTrue(cache.isDrained)
    }

    @Test fun identicalObjectIdsCannotAliasAnotherCatalogPageRevisionOrSession() {
        val cache = BrushTileCachePoc(bytes * 8, bytes * 9)
        val first = ready(cache, catalog()); assertFalse(first.cacheHit); cache.returnReader(first.reader)
        val cases = listOf(frame, frame.copy(sceneId = SceneId("other-page")), frame.copy(sceneRevision = 8), frame.copy(sessionGeneration = 2))
        for (f in cases) {
            val c = catalog(f, width = 12.0)
            val result = ready(cache, c, f); assertFalse(result.cacheHit)
            cache.returnReader(result.reader)
        }
        val c = catalog()
        for (f in cases.drop(1)) assertEquals("content-owner-mismatch", assertIs<CachedBrushTileBuild.Rejected>(cache.acquire(c, f, layer, grid)).reason)
        assertIs<CachedBrushTileBuild.Rejected>(cache.acquire(c, frame, LayerId("other"), grid))
        cache.close(); assertTrue(cache.isDrained)
    }

    @Test fun gridPrecisionAndLatticeArePartOfCacheIdentity() {
        val cache = BrushTileCachePoc(bytes * 8, bytes * 9); val c = catalog()
        val initial = ready(cache, c); cache.returnReader(initial.reader)
        for (g in listOf(grid.copy(origin = PointD(0.25, 8.0)), grid.copy(pixelSize = 0.5), grid.copy(samplesPerAxis = 4), grid.copy(width = 16))) {
            val result = ready(cache, c, g = g); assertFalse(result.cacheHit); cache.returnReader(result.reader)
        }
        val precise = assertIs<CachedBrushTileBuild.Ready>(cache.acquire(c, frame, layer, grid, sceneTolerance = 0.01))
        assertFalse(precise.cacheHit); cache.returnReader(precise.reader)
        assertTrue(ready(cache, c).also { cache.returnReader(it.reader) }.cacheHit)
        cache.close(); assertTrue(cache.isDrained)
    }

    @Test fun failedAndCancelledJobsReleaseReservationsWithoutPoisoningCache() {
        val cache = BrushTileCachePoc(bytes, bytes * 2); val c = catalog()
        assertIs<CachedBrushTileBuild.Rejected>(cache.acquire(c, frame, layer, grid, maxEdgeTests = 1))
        assertEquals(0, cache.chargedBytes)
        var checks = 0
        assertIs<CachedBrushTileBuild.Cancelled>(cache.acquire(c, frame, layer, grid, cancelled = { ++checks > 12 }))
        assertTrue(checks > 12); assertEquals(0, cache.chargedBytes)
        val result = ready(cache, c); assertFalse(result.cacheHit); cache.returnReader(result.reader)
        assertIs<CachedBrushTileBuild.Cancelled>(cache.acquire(c, frame, layer, grid, cancelled = { true }))
        assertTrue(ready(cache, c).also { cache.returnReader(it.reader) }.cacheHit)
        cache.close(); assertTrue(cache.isDrained)
    }

    @Test fun readerLimitRejectsBeforeRenderingAndReturnedReadersCannotAccessPixels() {
        val cache = BrushTileCachePoc(bytes * 2, bytes * 3, maxReaders = 1); val c = catalog()
        val reader = ready(cache, c).reader
        for (g in listOf(grid, grid.copy(origin = PointD(32.0, 8.0)))) {
            assertEquals("reader-budget", assertIs<CachedBrushTileBuild.Rejected>(cache.acquire(c, frame, layer, g)).reason)
            assertEquals(bytes, cache.chargedBytes)
        }
        assertEquals(LeaseReturn.RETURNED, cache.returnReader(reader))
        assertEquals(LeaseReturn.ALREADY_RETURNED, cache.returnReader(reader))
        assertFailsWith<IllegalStateException> { reader.alphaByte(0, 0) }
        cache.close(); assertTrue(cache.isDrained)
    }

    @Test fun closeWaitsForReadersAndForeignReturnCannotDischargeTheirBudget() {
        val cache = BrushTileCachePoc(bytes, bytes * 2); val other = BrushTileCachePoc(bytes, bytes * 2)
        val c = catalog(); val reader = ready(cache, c).reader
        assertEquals(LeaseReturn.FOREIGN, other.returnReader(reader))
        cache.close(); cache.close(); assertFalse(cache.isDrained); assertEquals(bytes, cache.retiredBytes)
        assertEquals(64, reader.alphaByte(16, 8))
        assertEquals("closed", assertIs<CachedBrushTileBuild.Rejected>(cache.acquire(c, frame, layer, grid)).reason)
        cache.returnReader(reader); assertEquals(0, cache.chargedBytes); assertTrue(cache.isDrained)
        other.close(); assertTrue(other.isDrained)
    }

    @Test fun closeDuringRenderingRetiresLateOutputAndDoesNotPublishIt() {
        val cache = BrushTileCachePoc(bytes, bytes * 2); val c = catalog()
        var checks = 0
        assertEquals("closed", assertIs<CachedBrushTileBuild.Rejected>(cache.acquire(c, frame, layer, grid,
            cancelled = { if (++checks == 8) cache.close(); false })).reason)
        assertTrue(checks >= 8); assertEquals(0, cache.chargedBytes); assertTrue(cache.isDrained)
    }
}
