package dev.notecore.canvas.prototype

import kotlin.test.*

class ChunkedBrushPocTest {
    private val frame = FrameToken(SceneId("chunk-page"), 7, 1, 1, 1, 1)
    private fun stroke(xs: DoubleArray, ys: DoubleArray = DoubleArray(xs.size) { 16.0 },
        kind: BrushKind = BrushKind.MONOLINE, pressure: FloatArray = FloatArray(xs.size) { 1f }, transform: Affine2D = Affine2D()) =
        StrokeObject(ObjectId("stroke"), 2, LayerId("ink"), BrushSpec(kind, 1, 8.0, -1),
            StrokeSamples.copyOf(xs, ys, pressure, LongArray(xs.size) { it.toLong() }), transform)
    private fun catalog(stroke: StrokeObject, segments: Int = 4) =
        assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(frame, stroke, segments)).catalog

    @Test fun sharedEndpointsCoverEveryOriginalEdgeExactlyOnceAndSinglePointWorks() {
        val s = stroke(DoubleArray(18) { it.toDouble() })
        val c = catalog(s)
        assertEquals(5, c.chunkCount)
        val edges = (0 until c.chunkCount).flatMap { id -> (c.chunkStart(id) until c.chunkEnd(id)).toList() }
        assertEquals((0 until 17).toList(), edges)
        for (id in 1 until c.chunkCount) assertEquals(c.chunkEnd(id - 1), c.chunkStart(id))
        val dot = catalog(stroke(doubleArrayOf(16.0)))
        assertEquals(0, dot.chunkStart(0)); assertEquals(0, dot.chunkEnd(0))
        val tile = assertIs<BrushTileBuild.Ready>(dot.render(frame, BrushTileGridPoc(PointD(8.0, 8.0), 16, 16))).tile
        assertEquals(255, tile.alphaByte(8, 8))
    }

    @Test fun indexedChunkQueriesMatchBruteBoundsAndDoNotMissTransformedCoverage() {
        val s = stroke(DoubleArray(1000) { it / 4.0 }, DoubleArray(1000) { (it % 31) / 4.0 }, BrushKind.PRESSURE,
            FloatArray(1000) { (it % 8) / 8f }, Affine2D(a = -0.8, b = 0.2, c = 0.25, d = 1.1, tx = 260.0))
        val c = catalog(s, 32)
        for (i in 0 until 1000 step 17) {
            val p = s.transform.map(PointD(s.samples.x(i), s.samples.y(i)))
            val bounds = BoundsD(p.x, p.y, p.x, p.y)
            val oracle = (0 until c.chunkCount).filter { c.chunkBounds(it).overlaps(bounds) }.toSet()
            assertEquals(oracle, c.queryChunks(bounds).ids)
            assertTrue(c.queryChunks(bounds).ids.isNotEmpty())
        }
    }

    @Test fun overlappingReversalChunksUnionBitsBeforeApplyingMarkerAlpha() {
        val s = stroke(doubleArrayOf(4.0, 28.0, 4.0, 28.0, 4.0), kind = BrushKind.HIGHLIGHTER)
        val grid = BrushTileGridPoc(PointD(0.0, 8.0), 32, 16, samplesPerAxis = 4)
        val c = catalog(s, 1)
        val result = assertIs<BrushTileBuild.Ready>(c.render(frame, grid))
        assertEquals(64, result.tile.alphaByte(16, 8)) // Four chunk passes must not become 174 alpha.
        assertEquals(65535, result.tile.coverageBits(16, 8))
        assertEquals(0, result.tile.alphaByte(16, 0))
        val full = assertIs<ReducedBrushPathBuild.Ready>(ReducedBrushPathPoc.build(s.samples, s.brush)).path
        for (y in 0 until grid.height) for (x in 0 until grid.width) {
            var expected = 0
            for (sy in 0 until 4) for (sx in 0 until 4) {
                val p = PointD(x + (sx + 0.5) / 4, 8 + y + (sy + 0.5) / 4)
                if (full.covers(p)) expected = expected or (1 shl (sy * 4 + sx))
            }
            assertEquals(expected, result.tile.coverageBits(x, y), "seam x=$x y=$y")
        }
    }

    @Test fun chunkedTaperAndShearMatchOriginalOracleAtStableSubpixels() {
        val s = stroke(DoubleArray(17) { it * 2.0 + 4 }, DoubleArray(17) { it / 2.0 + 8 },
            BrushKind.PRESSURE, FloatArray(17) { (it % 5) / 4f }, Affine2D(a = -0.8, b = 0.2, c = 0.3, d = 1.1, tx = 40.0))
        val grid = BrushTileGridPoc(PointD(0.0, 0.0), 48, 32)
        val rendered = assertIs<BrushTileBuild.Ready>(catalog(s, 4).render(frame, grid, sceneTolerance = 0.01)).tile
        val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(s.samples, s.brush, s.transform)).envelope
        for (y in 0 until 32) for (x in 0 until 48) for (sy in 0 until 2) for (sx in 0 until 2) {
            val p = PointD(x + (sx + 0.5) / 2, y + (sy + 0.5) / 2)
            val expected = oracle.containsScene(p)
            val stable = listOf(PointD(p.x - 0.03, p.y), PointD(p.x + 0.03, p.y), PointD(p.x, p.y - 0.03), PointD(p.x, p.y + 0.03)).all { oracle.containsScene(it) == expected }
            if (stable) assertEquals(expected, rendered.coverageBits(x, y) and (1 shl (sy * 2 + sx)) != 0)
        }
    }

    @Test fun invisibleChunksAreNotGeneratedAndCompletedResultsRemainFrameBound() {
        val c = catalog(stroke(DoubleArray(10000) { it / 4.0 }), 128)
        val movedViewport = frame.copy(viewportVersion = 2, requestSequence = 2)
        val result = assertIs<BrushTileBuild.Ready>(c.render(movedViewport, BrushTileGridPoc(PointD(1000.0, 0.0), 32, 32)))
        assertTrue(result.builtChunks < 5); assertTrue(result.builtChunks < c.chunkCount / 10)
        assertEquals(movedViewport, result.tile.frame); assertEquals(LayerId("ink"), result.tile.ownerLayer)
        val gate = FramePublicationGate(movedViewport)
        assertTrue(gate.canPublish(result.tile.frame))
        assertTrue(result.tile.canPublish(gate, LayerId("ink")))
        assertFalse(result.tile.canPublish(gate, LayerId("other-page-ink")))
        assertEquals(0.05, result.tile.sceneTolerance); assertEquals(128, result.tile.segmentsPerChunk)
        assertTrue(gate.expect(movedViewport.copy(requestSequence = 3)))
        assertFalse(gate.canPublish(result.tile.frame)); gate.close()
        assertIs<BrushTileBuild.Rejected>(c.render(frame.copy(sceneRevision = 8), result.tile.grid))
        assertIs<BrushTileBuild.Rejected>(c.render(frame.copy(sceneId = SceneId("another")), result.tile.grid))
        val empty = assertIs<BrushTileBuild.Ready>(c.render(frame, BrushTileGridPoc(PointD(-1000.0, -1000.0), 8, 8)))
        assertEquals(0, empty.generatedVertices); assertEquals(0, empty.builtChunks)
    }

    @Test fun budgetsAndMidRasterCancellationNeverPublishPartialMasks() {
        val s = stroke(DoubleArray(100) { (it % 2) * 24.0 + 4 }, kind = BrushKind.HIGHLIGHTER)
        assertIs<ChunkedBrushBuild.Rejected>(ChunkedBrushPoc.build(frame, s, 1, maxChunks = 2))
        assertIs<ChunkedBrushBuild.Rejected>(ChunkedBrushPoc.build(frame, s, maxBoundsBytes = 1))
        val c = catalog(s, 8); val grid = BrushTileGridPoc(PointD(0.0, 8.0), 32, 16)
        assertIs<BrushTileBuild.Rejected>(c.render(frame, grid, maxCandidateChunks = 1))
        assertIs<BrushTileBuild.Rejected>(c.render(frame, grid, maxWorkingBytes = 100))
        assertEquals("raster-work-budget", assertIs<BrushTileBuild.Rejected>(c.render(frame, grid, maxEdgeTests = 1)).reason)
        var checks = 0
        assertIs<BrushTileBuild.Cancelled>(c.render(frame, grid, cancelled = { ++checks > 20 }))
        assertEquals(100, s.samples.size)
        assertIs<BrushTileBuild.Ready>(c.render(frame, grid))
    }

    @Test fun fullSubpixelCoverageStopsRemainingChunkWorkWithoutChangingUnion() {
        val s = stroke(doubleArrayOf(-20.0, 20.0, -20.0, 20.0), DoubleArray(4), BrushKind.HIGHLIGHTER)
        val c = catalog(s, 1)
        val rendered = assertIs<BrushTileBuild.Ready>(c.render(frame, BrushTileGridPoc(PointD(-1.0, -1.0), 2, 2, samplesPerAxis = 4)))
        assertEquals(3, rendered.candidateChunks); assertEquals(1, rendered.builtChunks)
        assertEquals(64, rendered.tile.alphaByte(0, 0))
    }

    @Test fun adjacentTilesShareTheSameWorldSubpixelLatticeWithoutASeam() {
        val s = stroke(doubleArrayOf(4.0, 28.0, 4.0), kind = BrushKind.HIGHLIGHTER)
        val c = catalog(s, 1)
        val wholeGrid = BrushTileGridPoc(PointD(0.25, 8.0), 64, 32, pixelSize = 0.5, samplesPerAxis = 4)
        val whole = assertIs<BrushTileBuild.Ready>(c.render(frame, wholeGrid)).tile
        val left = assertIs<BrushTileBuild.Ready>(c.render(frame, wholeGrid.copy(width = 32))).tile
        val right = assertIs<BrushTileBuild.Ready>(c.render(frame, wholeGrid.copy(origin = PointD(16.25, 8.0), width = 32))).tile
        for (y in 0 until 32) for (x in 0 until 64) {
            val part = if (x < 32) left else right
            assertEquals(whole.coverageBits(x, y), part.coverageBits(x % 32, y))
            assertEquals(whole.alphaByte(x, y), part.alphaByte(x % 32, y))
        }
    }
}
