package dev.notecore.canvas.prototype

import kotlin.test.*

class BrushGeometryTest {
    private fun samples(xs: DoubleArray = doubleArrayOf(0.0, 4.0), ys: DoubleArray = DoubleArray(xs.size),
                        pressures: FloatArray = FloatArray(xs.size) { 1f }): StrokeSamples =
        StrokeSamples.copyOf(xs, ys, pressures, LongArray(xs.size) { it.toLong() })
    private fun brush(kind: BrushKind = BrushKind.MONOLINE, width: Double = 2.0, argb: Int = -1) =
        BrushSpec(kind, BrushGeometryBuilder.ALGORITHM_VERSION, width, argb)
    private fun ready(samples: StrokeSamples = samples(), brush: BrushSpec = brush(),
                      budget: BrushGeometryBudget = BrushGeometryBudget()): BrushGeometry =
        assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(samples, brush, budget)).geometry
    private fun rejected(reason: BrushGeometryRejection, samples: StrokeSamples = samples(), brush: BrushSpec = brush(),
                         budget: BrushGeometryBudget = BrushGeometryBudget()) {
        assertEquals(BrushGeometryResult.Rejected(reason), BrushGeometryBuilder.build(samples, brush, budget))
    }
    private fun twiceArea(geometry: BrushGeometry, contour: Int): Double {
        val start = geometry.contourStart(contour); val end = geometry.contourEnd(contour)
        val x = geometry.x(start); val y = geometry.y(start)
        return (start until end).sumOf { i ->
            val next = if (i + 1 == end) start else i + 1
            (geometry.x(i) - x) * (geometry.y(next) - y) - (geometry.x(next) - x) * (geometry.y(i) - y)
        }
    }
    // Independent nonzero path evaluator: overlapping contours must be filled as one stroke.
    private fun winding(geometry: BrushGeometry, point: PointD, transform: Affine2D = Affine2D()): Int {
        var result = 0
        for (contour in 0 until geometry.contourCount) {
            val start = geometry.contourStart(contour); val end = geometry.contourEnd(contour)
            for (i in start until end) {
                val next = if (i + 1 == end) start else i + 1
                val a = transform.map(PointD(geometry.x(i), geometry.y(i)))
                val b = transform.map(PointD(geometry.x(next), geometry.y(next)))
                val cross = (b.x - a.x) * (point.y - a.y) - (point.x - a.x) * (b.y - a.y)
                if (a.y <= point.y && b.y > point.y && cross > 0) result++
                if (a.y > point.y && b.y <= point.y && cross < 0) result--
            }
        }
        return result
    }

    @Test fun monolineIgnoresPressureAndKeepsMaximumConfiguredDiameter() {
        val low = ready(samples(pressures = floatArrayOf(0f, 0.2f)))
        val high = ready(samples(pressures = floatArrayOf(1f, 0.9f)))
        assertEquals(BoundsD(-1.0, -1.0, 5.0, 1.0), low.bounds)
        assertEquals(36, low.vertexCount); assertEquals(3, low.contourCount)
        for (i in 0 until low.vertexCount) { assertEquals(high.x(i), low.x(i)); assertEquals(high.y(i), low.y(i)) }
    }

    @Test fun pressureUsesStoredFloatWithNonzeroFloorAndBoundedMaximum() {
        val input = samples(pressures = floatArrayOf(0f, 1f))
        val geometry = ready(input, brush(BrushKind.PRESSURE, 8.0))
        assertEquals(BoundsD(-1.0, -4.0, 8.0, 4.0), geometry.bounds)
        assertEquals(1.0, geometry.x(0)); assertEquals(8.0, geometry.x(16))
        assertEquals(0f, input.pressure(0)); assertEquals(1f, input.pressure(1))
        val half = ready(samples(doubleArrayOf(0.0), pressures = floatArrayOf(0.5f)), brush(BrushKind.PRESSURE, 8.0))
        assertEquals(2.5, half.x(0))
        val storedFloat = ready(samples(doubleArrayOf(0.0), pressures = floatArrayOf(0.3f)), brush(BrushKind.PRESSURE, 8.0))
        assertEquals(1.9000000357627869, storedFloat.x(0))
    }

    @Test fun highlighterCapsOpacityWithoutChangingRgbOrIncreasingLowerOpacity() {
        val opaque = ready(brush = brush(BrushKind.HIGHLIGHTER, argb = 0xffabcdef.toInt()))
        assertEquals(0x60abcdef, opaque.argb)
        val translucent = ready(brush = brush(BrushKind.HIGHLIGHTER, argb = 0x20abcdef))
        assertEquals(0x20abcdef, translucent.argb)
        val transparent = ready(brush = brush(BrushKind.HIGHLIGHTER, argb = 0x00abcdef))
        assertEquals(0x00abcdef, transparent.argb); assertEquals(36, transparent.vertexCount)
    }

    @Test fun retracedHighlighterIsOneNonzeroFillRatherThanEvenOddOrRepeatedCompositing() {
        val geometry = ready(samples(doubleArrayOf(0.0, 4.0, 0.0)), brush(BrushKind.HIGHLIGHTER))
        assertEquals(BrushFillRule.NON_ZERO, geometry.fillRule)
        assertEquals(BrushBlendMode.SOURCE_OVER, geometry.blendMode)
        assertEquals(2, winding(geometry, PointD(2.0, 0.1)))
        assertEquals(0, winding(geometry, PointD(2.0, 2.0)))
        val reflection = Affine2D(a = -2.0, c = 1.0, d = 3.0, tx = 8.0, ty = 9.0)
        assertEquals(-2, winding(geometry, reflection.map(PointD(2.0, 0.1)), reflection))
    }

    @Test fun singleDotAndRepeatedLocationsRetainEverySampleWithoutZeroLengthBridges() {
        val dot = ready(samples(doubleArrayOf(17.0), doubleArrayOf(-9.0)))
        assertEquals(PointD(17.0, -9.0), dot.origin); assertEquals(16, dot.vertexCount)
        assertEquals(1, dot.contourCount); assertEquals(BoundsD(-1.0, -1.0, 1.0, 1.0), dot.bounds)
        val duplicate = ready(samples(doubleArrayOf(17.0, 17.0), doubleArrayOf(-9.0, -9.0), floatArrayOf(0f, 1f)),
            brush(BrushKind.PRESSURE, 8.0))
        assertEquals(2, duplicate.sourcePointCount); assertEquals(32, duplicate.vertexCount)
        assertEquals(2, duplicate.contourCount); assertEquals(BoundsD(-4.0, -4.0, 4.0, 4.0), duplicate.bounds)
        assertTrue(winding(duplicate, PointD(3.0, 0.1)) > 0)
    }

    @Test fun acuteTurnsReversalsAndDiagonalsHaveFinitePositiveContoursInsideConservativeStrokeBounds() {
        val input = samples(doubleArrayOf(0.0, 4.0, 0.0, 0.001, -4.0), doubleArrayOf(0.0, 0.0, 0.0, 4.0, -2.0),
            floatArrayOf(0f, 1f, 0.2f, 0.7f, 0.4f))
        val geometry = ready(input, brush(BrushKind.PRESSURE, 8.0))
        for (contour in 0 until geometry.contourCount) assertTrue(twiceArea(geometry, contour) > 0)
        val conservative = input.bounds(4.0)
        for (vertex in 0 until geometry.vertexCount) {
            val x = geometry.x(vertex) + geometry.origin.x; val y = geometry.y(vertex) + geometry.origin.y
            assertTrue(x.isFinite() && y.isFinite())
            assertTrue(x in conservative.left..conservative.right && y in conservative.top..conservative.bottom)
        }
    }

    @Test fun originRebasingPreservesTinyDotsAtLargeSampleCoordinates() {
        val dot = ready(samples(doubleArrayOf(1e9), doubleArrayOf(-1e9)), brush(width = 1e-10))
        assertEquals(PointD(1e9, -1e9), dot.origin); assertEquals(5e-11, dot.x(0))
        assertTrue(twiceArea(dot, 0) > 0)
    }

    @Test fun unrepresentableRadiiOrFarLocalOffsetsReturnNumericFailureWithoutPartialGeometry() {
        rejected(BrushGeometryRejection.NUMERIC_RANGE, brush = brush(width = Double.MIN_VALUE))
        rejected(BrushGeometryRejection.NUMERIC_RANGE, samples = samples(doubleArrayOf(0.0, 1e9)), brush = brush(width = 1e-10))
    }

    @Test fun exactCapacityIsAcceptedAndEachSmallerBudgetRejectsExplicitly() {
        val geometry = ready(); assertEquals(848L, geometry.logicalByteCharge)
        ready(budget = BrushGeometryBudget(2, 36, 848))
        rejected(BrushGeometryRejection.SAMPLE_CAPACITY, budget = BrushGeometryBudget(maxSamples = 1))
        rejected(BrushGeometryRejection.VERTEX_CAPACITY, budget = BrushGeometryBudget(maxVertices = 35))
        rejected(BrushGeometryRejection.BYTE_CAPACITY, budget = BrushGeometryBudget(logicalBytes = 847))
    }

    @Test fun longStrokeAtDefaultLimitRetainsAllSamplesAndNextSampleIsRejected() {
        val input = samples(DoubleArray(20_000) { it.toDouble() })
        val geometry = ready(input)
        assertEquals(20_000, geometry.sourcePointCount); assertEquals(399_996, geometry.vertexCount)
        assertEquals(39_999, geometry.contourCount); assertEquals(6_560_192L, geometry.logicalByteCharge)
        assertEquals(20_000.0, geometry.bounds.right)
        val over = samples(DoubleArray(20_001) { it.toDouble() })
        rejected(BrushGeometryRejection.SAMPLE_CAPACITY, samples = over)
        assertEquals(20_000.0, over.x(20_000))
    }

    @Test fun unknownAndLegacyVersionsAreRejectedWithoutFallbackOrChangingInput() {
        val input = samples()
        for (version in listOf(1, 3, Int.MAX_VALUE)) rejected(BrushGeometryRejection.UNSUPPORTED_ALGORITHM,
            samples = input, brush = BrushSpec(BrushKind.MONOLINE, version, 2.0, -1))
        assertEquals(2, input.size); assertEquals(4.0, input.x(1))
    }

    @Test fun inputOwnershipAndIndependentBuildsCannotMutatePublishedGeometry() {
        val xs = doubleArrayOf(0.0, 4.0); val ys = doubleArrayOf(0.0, 0.0); val pressures = floatArrayOf(0f, 1f)
        val input = samples(xs, ys, pressures)
        val first = ready(input, brush(BrushKind.PRESSURE, 8.0))
        xs[0] = 100.0; ys[0] = 100.0; pressures[0] = 1f
        val second = ready(input, brush(BrushKind.PRESSURE, 8.0))
        assertNotSame(first, second); assertEquals(PointD(0.0, 0.0), first.origin)
        for (i in 0 until first.vertexCount) { assertEquals(first.x(i), second.x(i)); assertEquals(first.y(i), second.y(i)) }
        assertFailsWith<IllegalArgumentException> { first.x(first.vertexCount) }
        assertFailsWith<IllegalArgumentException> { first.contourStart(first.contourCount) }
    }
}
