package dev.notecore.canvas.prototype

import kotlin.math.abs
import kotlin.test.*

class BrushPathPlanTest {
    private fun geometry(origin: Double = 0.0, width: Double = 2.0): BrushGeometry =
        assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(
            StrokeSamples.copyOf(doubleArrayOf(origin, origin + 4.0, origin), doubleArrayOf(origin, origin, origin),
                floatArrayOf(0f, 0.5f, 1f), longArrayOf(1, 2, 3)), BrushSpec(BrushKind.HIGHLIGHTER, 2, width, -1))).geometry
    private fun ready(geometry: BrushGeometry = geometry(), matrix: Affine2D = Affine2D(),
                      precision: BrushPathPrecision = BrushPathPrecision.FLOAT32): BrushPathPlan =
        assertIs<BrushPathPlanResult.Ready>(BrushPathPlanner.prepare(geometry, matrix, precision)).plan

    @Test fun targetOriginIsCancelledBeforeFloatConversionAndGeometryIsUnchanged() {
        val origin = 1e9 - 4.0 // The next real sample must remain within the existing +/-1e9 admission bound.
        val geometry = geometry(origin)
        val plan = ready(geometry, Affine2D(a = 4.0, d = 4.0, tx = 64 - 4 * origin, ty = 64 - 4 * origin))
        assertEquals(68.0, plan.x(0)); assertEquals(64.0, plan.y(0))
        assertEquals(PointD(origin, origin), geometry.origin); assertEquals(1.0, geometry.x(0))
        assertEquals(geometry.vertexCount, plan.vertexCount); assertEquals(geometry.contourCount, plan.contourCount)
        assertEquals(geometry.argb, plan.argb); assertEquals(BrushFillRule.NON_ZERO, plan.fillRule)
    }
    @Test fun fullAffineIncludingReflectionPreservesCommonContourOrientation() {
        val matrix = Affine2D(a = -3.0, b = 1.0, c = 2.0, d = 4.0, tx = 50.0, ty = 60.0)
        val geometry = geometry(); val plan = ready(geometry, matrix)
        for (i in 0 until plan.vertexCount) {
            val expected = matrix.map(PointD(geometry.x(i), geometry.y(i)))
            assertEquals(expected.x.toFloat().toDouble(), plan.x(i)); assertEquals(expected.y.toFloat().toDouble(), plan.y(i))
        }
    }
    @Test fun float32QuantizationIsExplicitAndWithinTargetPixelTolerance() {
        val geometry = geometry(); val matrix = Affine2D(tx = 0.123456789, ty = 0.987654321)
        val f32 = ready(geometry, matrix); val f64 = ready(geometry, matrix, BrushPathPrecision.FLOAT64)
        assertNotEquals(f32.x(0), f64.x(0))
        for (i in 0 until f32.vertexCount) {
            assertTrue(abs(f32.x(i) - f64.x(i)) <= BrushPathPlanner.FLOAT32_MAX_ERROR)
            assertTrue(abs(f32.y(i) - f64.y(i)) <= BrushPathPlanner.FLOAT32_MAX_ERROR)
        }
    }
    @Test fun remoteUnrebasedCoordinatesAreRejectedRatherThanDrawingCollapsedFloatPaths() {
        val result = BrushPathPlanner.prepare(geometry(), Affine2D(tx = 1e9))
        assertEquals(BrushPathPlanResult.Rejected(BrushPathRejection.PRECISION_LOSS), result)
    }
    @Test fun singularTransformsAndVertexBudgetAreRejectedBeforeNativeAllocation() {
        val geometry = geometry()
        assertEquals(BrushPathPlanResult.Rejected(BrushPathRejection.INVALID_TRANSFORM),
            BrushPathPlanner.prepare(geometry, Affine2D(a = 0.0, d = 0.0)))
        assertEquals(BrushPathPlanResult.Rejected(BrushPathRejection.VERTEX_CAPACITY),
            BrushPathPlanner.prepare(geometry, maxVertices = geometry.vertexCount - 1))
    }
    @Test fun numericOverflowAndDegenerationReturnTypedRejections() {
        assertEquals(BrushPathPlanResult.Rejected(BrushPathRejection.NON_FINITE),
            BrushPathPlanner.prepare(geometry(1e9 - 4.0), Affine2D(a = 1e308, d = 1e308)))
        assertEquals(BrushPathPlanResult.Rejected(BrushPathRejection.DEGENERATE_CONTOUR),
            BrushPathPlanner.prepare(geometry(), Affine2D(a = 1e-200, d = 1e-200), BrushPathPrecision.FLOAT64))
        assertEquals(BrushPathPlanResult.Rejected(BrushPathRejection.DEGENERATE_CONTOUR),
            BrushPathPlanner.prepare(geometry(width = 1e-10), Affine2D(tx = 100.0, ty = 100.0)))
    }
    @Test fun repeatedPlansDoNotChangeSourceOrAliasMutableTargetData() {
        val geometry = geometry(); val first = ready(geometry)
        val second = ready(geometry, Affine2D(tx = 100.0))
        assertEquals(1.0, first.x(0)); assertEquals(101.0, second.x(0)); assertEquals(1.0, geometry.x(0))
        assertFailsWith<IllegalArgumentException> { first.x(first.vertexCount) }
    }
}
