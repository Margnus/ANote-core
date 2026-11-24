package dev.notecore.canvas.prototype

import kotlin.math.*
import kotlin.test.*

class SweptDiscGeometryPocTest {
    private fun samples(xs: DoubleArray, ys: DoubleArray = DoubleArray(xs.size),
                        pressures: FloatArray = FloatArray(xs.size) { 1f }) =
        StrokeSamples.copyOf(xs, ys, pressures, LongArray(xs.size) { it.toLong() })
    private fun brush(kind: BrushKind = BrushKind.MONOLINE, width: Double = 8.0) = BrushSpec(kind, 1, width, -1)
    private fun ready(s: StrokeSamples, b: BrushSpec = brush(), t: Affine2D = Affine2D(), error: Double = .125,
                      budget: BrushGeometryBudget = BrushGeometryBudget()) =
        assertIs<SweptDiscBuild.Ready>(SweptDiscGeometryPoc.build(s, b, t, error, budget))

    private fun contains(g: BrushGeometry, p: PointD): Boolean = (0 until g.contourCount).any { c ->
        val start = g.contourStart(c); val end = g.contourEnd(c)
        (start until end).all { i ->
            val j = if (i + 1 == end) start else i + 1
            (g.x(j) - g.x(i)) * (p.y - g.y(i)) - (g.y(j) - g.y(i)) * (p.x - g.x(i)) >= 0.0
        }
    }
    private fun distance(g: BrushGeometry, p: PointD, t: Affine2D): Double {
        var result = Double.POSITIVE_INFINITY
        val target = t.map(p)
        for (c in 0 until g.contourCount) {
            val start = g.contourStart(c); val end = g.contourEnd(c)
            for (i in start until end) {
                val j = if (i + 1 == end) start else i + 1
                val a = t.map(PointD(g.x(i), g.y(i))); val b = t.map(PointD(g.x(j), g.y(j)))
                val dx = b.x - a.x; val dy = b.y - a.y
                val fraction = (((target.x - a.x) * dx + (target.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                result = min(result, hypot(target.x - a.x - fraction * dx, target.y - a.y - fraction * dy))
            }
        }
        return result
    }

    @Test fun finishedSegmentKeepsVerticesWhenHigherPressureAppendsAcrossBoundary() {
        val edges = SweptDiscGeometryPoc.SEGMENT_EDGES
        val prior = samples(DoubleArray(edges + 1) { it * 10.0 }, pressures = FloatArray(edges + 1))
        val extended = samples(DoubleArray(edges + 2) { it * 10.0 },
            pressures = FloatArray(edges + 2) { if (it == edges + 1) 1f else 0f })
        val pressure = brush(BrushKind.PRESSURE, width = 10.0)
        val before = assertIs<SweptDiscBuild.Ready>(SweptDiscGeometryPoc.buildSegment(prior, 0, pressure))
        val after = assertIs<SweptDiscBuild.Ready>(SweptDiscGeometryPoc.buildSegment(extended, 0, pressure))
        val tail = assertIs<SweptDiscBuild.Ready>(SweptDiscGeometryPoc.buildSegment(extended, 1, pressure))
        assertEquals(1, SweptDiscGeometryPoc.segmentCount(prior))
        assertEquals(2, SweptDiscGeometryPoc.segmentCount(extended))
        assertEquals(before.discVertices, after.discVertices)
        assertEquals(before.geometry.vertexCount, after.geometry.vertexCount)
        for (i in 0 until before.geometry.vertexCount) {
            assertEquals(before.geometry.x(i), after.geometry.x(i))
            assertEquals(before.geometry.y(i), after.geometry.y(i))
        }
        assertEquals(PointD(edges * 10.0, 0.0), tail.geometry.origin)
        assertTrue(tail.discVertices > after.discVertices)
        assertTrue(contains(after.geometry, tail.geometry.origin))
        assertTrue(contains(tail.geometry, PointD(5.0, 0.0))) // The bridge across the segment boundary.
        assertFailsWith<IllegalArgumentException> { SweptDiscGeometryPoc.buildSegment(extended, 2, pressure) }
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.SAMPLE_CAPACITY),
            SweptDiscGeometryPoc.buildSegment(extended, 1, pressure, budget = BrushGeometryBudget(maxSamples = 1)))
    }

    @Test fun knownRoundDotAndPressureBridgeCounterexamplesNowHaveCoverage() {
        val dot = ready(samples(doubleArrayOf(0.0)), brush(width = 128.0), Affine2D(a = 4.0, d = 4.0))
        assertTrue(contains(dot.geometry, PointD(62.0, 12.0)))
        assertTrue(dot.discVertices > 16); assertTrue(dot.targetDeviationBound <= .125)
        val taper = ready(samples(doubleArrayOf(0.0, 10.0), pressures = floatArrayOf(0f, 1f)), brush(BrushKind.PRESSURE))
        assertTrue(contains(taper.geometry, PointD(5.0, 2.6)))
        assertFalse(contains(taper.geometry, PointD(5.0, 2.7)))
        println("C1 adaptive tangent: known dot/taper probes corrected; dot sides=${dot.discVertices}, bound=${dot.targetDeviationBound}")
    }

    @Test fun analyticOracleGridHasNoOvershootAndOnlyBoundedDiscDeficitsUnderAffineTargets() {
        val s = samples(doubleArrayOf(0.0, 10.0, 10.0, 0.0), doubleArrayOf(0.0, 0.0, 10.0, 0.0), floatArrayOf(0f, 1f, .25f, .75f))
        var checked = 0; var deficits = 0
        for (kind in BrushKind.entries) for (t in listOf(Affine2D(), Affine2D(a = -4.0, b = .5, c = 1.5, d = 2.0),
            Affine2D(a = .25, b = .5, c = 2.0, d = .25))) {
            val b = brush(kind)
            val result = ready(s, b, t)
            val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(s, b)).envelope
            for (x in -15..45) for (y in -15..45) {
                val point = PointD(x / 3.0 + .013, y / 3.0 + .017)
                val actual = contains(result.geometry, point); val expected = oracle.containsScene(point)
                if (actual) assertTrue(expected, "$kind t=$t overshoot $point")
                if (expected && !actual) {
                    deficits++
                    assertTrue(distance(result.geometry, point, t) <= result.targetDeviationBound + 1e-9,
                        "$kind t=$t excess deficit $point")
                }
                checked++
            }
            // Reuse the original target-coordinate planner, including orientation reversal.
            assertIs<BrushPathPlanResult.Ready>(BrushPathPlanner.prepare(result.geometry, t))
        }
        assertTrue(deficits > 0) // Confirms this test actually exercised the approximation band.
        println("C1 adaptive tangent: oracle classifications=$checked; bounded deficit probes=$deficits")
    }

    @Test fun containedAndRepeatedDiscsRetainSamplesWithoutUnnecessaryBridges() {
        val s = samples(doubleArrayOf(0.0, 1.0, 1.0), pressures = floatArrayOf(0f, 1f, 0f))
        val r = ready(s, brush(BrushKind.PRESSURE))
        assertEquals(3, r.geometry.sourcePointCount); assertEquals(3, r.geometry.contourCount)
        assertTrue(contains(r.geometry, PointD(4.0, 0.0)))
        assertTrue(contains(r.geometry, PointD(-2.0, 0.0)))
    }

    @Test fun exactOutputBudgetSucceedsAndEachSmallerLimitRejectsBeforePublication() {
        val s = samples(doubleArrayOf(0.0, 10.0))
        val g = ready(s).geometry
        val exact = BrushGeometryBudget(2, g.vertexCount, g.logicalByteCharge)
        ready(s, budget = exact)
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.SAMPLE_CAPACITY), SweptDiscGeometryPoc.build(s, brush(), budget = exact.copy(maxSamples = 1)))
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.VERTEX_CAPACITY), SweptDiscGeometryPoc.build(s, brush(), budget = exact.copy(maxVertices = g.vertexCount - 1)))
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.BYTE_CAPACITY), SweptDiscGeometryPoc.build(s, brush(), budget = exact.copy(logicalBytes = g.logicalByteCharge - 1)))
    }

    @Test fun profileIdentityColorAndInputRemainSeparateFromOracleAndCandidateTwo() {
        val s = samples(doubleArrayOf(0.0, 10.0), pressures = floatArrayOf(.2f, .8f))
        val b = brush(BrushKind.HIGHLIGHTER)
        val r = ready(s, b)
        assertEquals("swept-discs-tangent-adaptive-draft-1", r.ruleId)
        assertEquals(1, r.geometry.brush.algorithmVersion) // Existing input identity, no persisted version allocation.
        assertEquals(127, r.geometry.argb ushr 24)
        assertEquals(.2f, s.pressure(0)); assertEquals(10.0, s.x(1))
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.UNSUPPORTED_ALGORITHM), SweptDiscGeometryPoc.build(s, b.copy(algorithmVersion = 2)))
    }

    @Test fun tighterTargetToleranceRefinesAndUnrepresentableRequestsReject() {
        val s = samples(doubleArrayOf(1e9), doubleArrayOf(-1e9))
        val coarse = ready(s); val fine = ready(s, error = .001)
        assertTrue(fine.discVertices > coarse.discVertices)
        assertEquals(PointD(1e9, -1e9), fine.geometry.origin)
        val wide = samples(doubleArrayOf(0.0, 1e9))
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.NUMERIC_RANGE), SweptDiscGeometryPoc.build(wide, brush(), maxTargetDeviation = 1e-9))
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.NUMERIC_RANGE), SweptDiscGeometryPoc.build(s, brush(width = Double.MIN_VALUE)))
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.NUMERIC_RANGE), SweptDiscGeometryPoc.build(s, brush(), Affine2D(a = 0.0, d = 0.0)))
        assertEquals(SweptDiscBuild.Rejected(BrushGeometryRejection.VERTEX_CAPACITY), SweptDiscGeometryPoc.build(s, brush(), maxTargetDeviation = 1e-12))
    }
}
