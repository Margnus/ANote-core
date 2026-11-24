package dev.notecore.canvas.prototype

import kotlin.test.*

class SweptDiscTileUnionTest {
    private val frame = FrameToken(SceneId("swept-page"), 7, 1, 1, 1, 1)
    private val grid = BrushTileGridPoc(PointD(0.0, 8.0), 32, 16, samplesPerAxis = 4)

    private fun retrace(): ChunkedBrushPoc {
        val edges = SweptDiscGeometryPoc.SEGMENT_EDGES
        val xs = DoubleArray(edges * 2 + 1) { 4.0 + minOf(it, edges * 2 - it) * 0.125 }
        val samples = StrokeSamples.copyOf(xs, DoubleArray(xs.size) { 16.0 },
            FloatArray(xs.size) { 1f }, LongArray(xs.size) { it.toLong() })
        val stroke = StrokeObject(ObjectId("marker"), 1, LayerId("ink"),
            BrushSpec(BrushKind.HIGHLIGHTER, 1, 8.0, -1), samples, Affine2D())
        return assertIs<ChunkedBrushBuild.Ready>(ChunkedBrushPoc.build(frame, stroke)).catalog
    }

    @Test fun retracedSegmentsShareSingleAlphaAndAdjacentTilesMatchWideTile() {
        val catalog = retrace()
        val wide = assertIs<BrushTileBuild.Ready>(catalog.renderSwept(frame, grid))
        assertEquals(2, wide.builtChunks)
        assertEquals(127, wide.tile.alphaByte(16, 8))
        assertEquals(127, wide.tile.alphaByte(20, 8)) // Segment endpoint.
        assertEquals(0, wide.tile.alphaByte(24, 8))
        assertEquals("swept-disc-subpixel-union-draft-1", wide.tile.ruleId)
        for (left in listOf(0, 16)) {
            val half = grid.copy(origin = PointD(left.toDouble(), 8.0), width = 16)
            val tile = assertIs<BrushTileBuild.Ready>(catalog.renderSwept(frame, half)).tile
            for (y in 0 until grid.height) for (x in 0 until half.width)
                assertEquals(wide.tile.coverageBits(x + left, y), tile.coverageBits(x, y), "x=${x + left} y=$y")
        }
    }

    @Test fun cumulativeLimitsRejectWholeResultEvenWhenEachSegmentFits() {
        val catalog = retrace()
        val full = assertIs<BrushTileBuild.Ready>(catalog.renderSwept(frame, grid))
        assertIs<BrushTileBuild.Ready>(catalog.renderSwept(frame, grid, maxEdgeTests = full.edgeTests,
            maxGeneratedVertices = full.generatedVertices, maxWorkingBytes = full.peakWorkingLogicalBytes))
        assertEquals(BrushTileBuild.Rejected("generated-vertex-budget"),
            catalog.renderSwept(frame, grid, maxGeneratedVertices = full.generatedVertices - 1))
        assertEquals(BrushTileBuild.Rejected("raster-work-budget"),
            catalog.renderSwept(frame, grid, maxEdgeTests = full.edgeTests - 1))
        assertEquals(BrushTileBuild.Rejected("working-budget"),
            catalog.renderSwept(frame, grid, maxWorkingBytes = full.peakWorkingLogicalBytes - 1))
        assertEquals(BrushTileBuild.Rejected("candidate-budget"), catalog.renderSwept(frame, grid, maxCandidateChunks = 1))
        assertEquals(BrushTileBuild.Cancelled, catalog.renderSwept(frame, grid, cancelled = { true }))
        println("Swept union: candidates=${full.candidateChunks} vertices=${full.generatedVertices} edgeTests=${full.edgeTests} peakLogicalBytes=${full.peakWorkingLogicalBytes}")
    }
}
