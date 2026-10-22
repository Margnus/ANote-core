package dev.notecore.canvas.prototype

import kotlin.test.*

class BrushReductionPocTest {
    private fun samples(xs: DoubleArray, ys: DoubleArray = DoubleArray(xs.size), pressures: FloatArray = FloatArray(xs.size) { 1f }) =
        StrokeSamples.copyOf(xs, ys, pressures, LongArray(xs.size) { it.toLong() })
    private fun brush(kind: BrushKind = BrushKind.MONOLINE) = BrushSpec(kind, 1, 8.0, -1)
    private fun plan(samples: StrokeSamples, kind: BrushKind = BrushKind.MONOLINE) =
        assertIs<BrushReductionBuild.Ready>(BrushReductionPoc.build(samples, brush(kind))).plan

    @Test fun straightRunRetainsEndpointsAndCanonicalSampleIdentity() {
        val input = samples(DoubleArray(1000) { it / 4.0 })
        val p = plan(input)
        assertEquals(2, p.size); assertEquals(0, p.sourceIndex(0)); assertEquals(999, p.sourceIndex(1))
        assertEquals(4000, p.logicalBytes)
        assertTrue(p.matches(input, brush())); assertFalse(p.matches(samples(DoubleArray(1000) { it / 4.0 }), brush()))
        assertFailsWith<IllegalArgumentException> { p.sourceIndex(2) }
        assertEquals(1000, input.size); assertEquals(999, input.timeMicros(999))
    }

    @Test fun turnsReversalAndNonLatticeCoordinatesAreConservativelyRetained() {
        assertEquals(3, plan(samples(doubleArrayOf(0.0, 1.0, 0.0))).size)
        assertEquals(3, plan(samples(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.0, 1.0, 0.0))).size)
        assertEquals(3, plan(samples(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.0, 0.0000001, 0.0))).size)
        assertEquals(3, plan(samples(doubleArrayOf(1e8, 1e8 + 1, 1e8 + 2))).size)
        assertEquals(2, plan(samples(doubleArrayOf(10.0, 10.0, 12.0))).size)
    }

    @Test fun pressureMustBeLinearNotJustBoundedByEndpointWidths() {
        val x = doubleArrayOf(0.0, 1.0, 2.0)
        assertEquals(2, plan(samples(x, pressures = floatArrayOf(0f, 0.5f, 1f)), BrushKind.PRESSURE).size)
        assertEquals(3, plan(samples(x, pressures = floatArrayOf(0f, 1f, 0f)), BrushKind.PRESSURE).size)
        assertEquals(3, plan(samples(x, pressures = floatArrayOf(1f, 0f, 1f)), BrushKind.PRESSURE).size)
        assertEquals(3, plan(samples(x, pressures = floatArrayOf(0f, 0.5000001f, 1f)), BrushKind.PRESSURE).size)
        val repeated = samples(doubleArrayOf(0.0, 0.0, 0.0), pressures = floatArrayOf(0f, 1f, 0f))
        assertEquals(3, plan(repeated, BrushKind.PRESSURE).size)
    }

    @Test fun integerProductExtremesDoNotOverflowOrEraseTurns() {
        val edge = 1_000_000_000.0 / 1024
        assertEquals(2, plan(samples(doubleArrayOf(-edge, 0.0, edge), doubleArrayOf(-edge, 0.0, edge))).size)
        assertEquals(3, plan(samples(doubleArrayOf(-edge, edge, -edge), doubleArrayOf(-edge, edge, -edge))).size)
        assertEquals(3, plan(samples(doubleArrayOf(-edge, edge, edge), doubleArrayOf(-edge, -edge, edge))).size)
    }

    @Test fun reducedPathAgreesWithOriginalOracleUnderAffineAndAlpha() {
        val input = samples(DoubleArray(65) { it / 2.0 + 8 }, DoubleArray(65) { it / 4.0 + 8 }, FloatArray(65) { it / 64f })
        for (kind in BrushKind.entries) {
            val spec = brush(kind); val transform = Affine2D(a = -0.8, b = 0.2, c = 0.3, d = 1.1, tx = 45.0)
            val reduced = assertIs<ReducedBrushPathBuild.Ready>(ReducedBrushPathPoc.build(input, spec, transform, sceneTolerance = 0.01))
            assertEquals(2, reduced.derivedSamples)
            val baseline = assertIs<BrushPathBuild.Ready>(BrushPathPoc.build(input, spec, transform, sceneTolerance = 0.01)).path
            assertTrue(reduced.path.vertexCount * 10 < baseline.vertexCount)
            assertEquals(baseline.sourceAlpha, reduced.path.sourceAlpha)
            val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(input, spec, transform)).envelope
            for (x in 0 until 64) for (y in 0 until 64) {
                val p = PointD(x + 0.37, y + 0.61)
                if (reduced.path.covers(p)) assertTrue(oracle.containsScene(p))
                if (listOf(PointD(p.x - 0.03, p.y), PointD(p.x + 0.03, p.y), PointD(p.x, p.y - 0.03), PointD(p.x, p.y + 0.03)).all(oracle::containsScene))
                    assertTrue(reduced.path.covers(p))
            }
        }
        assertEquals(65, input.size); assertEquals(64, input.timeMicros(64))
    }

    @Test fun budgetsAndCancellationNeverPublishPartialPlansOrDiscardSource() {
        val input = samples(DoubleArray(1000) { it / 4.0 })
        assertIs<BrushReductionBuild.Rejected>(BrushReductionPoc.build(input, brush(), maxPlanBytes = 100))
        assertIs<ReducedBrushPathBuild.Rejected>(ReducedBrushPathPoc.build(input, brush(), maxWorkingBytes = 4100))
        assertIs<ReducedBrushPathBuild.Rejected>(ReducedBrushPathPoc.build(input, brush().copy(algorithmVersion = 2)))
        var polls = 0
        assertIs<BrushReductionBuild.Cancelled>(BrushReductionPoc.build(input, brush(), cancelled = { ++polls >= 3 }))
        assertIs<ReducedBrushPathBuild.Cancelled>(ReducedBrushPathPoc.build(input, brush(), cancelled = { true }))
        assertEquals(1000, input.size)
        val ready = assertIs<ReducedBrushPathBuild.Ready>(ReducedBrushPathPoc.build(input, brush()))
        assertTrue(ready.workingLogicalBytes <= 16L * 1024 * 1024)
    }

    @Test fun denseTraceTurnsPressureResetsAndPlateausMatchOriginalCoverage() {
        val input = samples(DoubleArray(100) { it / 4.0 }, DoubleArray(100) {
            (if (it % 64 < 32) it % 32 else 63 - it % 64) / 4.0
        }, FloatArray(100) { (8 + it % 16) / 32f })
        for (kind in BrushKind.entries) {
            val spec = brush(kind)
            val reduced = assertIs<ReducedBrushPathBuild.Ready>(ReducedBrushPathPoc.build(input, spec, sceneTolerance = 0.01))
            val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(input, spec)).envelope
            assertTrue(reduced.derivedSamples < 30)
            for (x in -8..116) for (y in -16..48) {
                val p = PointD(x / 4.0 + 0.013, y / 4.0 + 0.017)
                if (reduced.path.covers(p)) assertTrue(oracle.containsScene(p), "$kind exceeds original at $p")
                if (listOf(PointD(p.x - 0.04, p.y), PointD(p.x + 0.04, p.y), PointD(p.x, p.y - 0.04), PointD(p.x, p.y + 0.04)).all(oracle::containsScene))
                    assertTrue(reduced.path.covers(p), "$kind misses original at $p")
            }
        }
        assertEquals(100, input.size)
    }
}
