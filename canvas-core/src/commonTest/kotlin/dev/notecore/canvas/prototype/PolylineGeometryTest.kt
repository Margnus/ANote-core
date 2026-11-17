package dev.notecore.canvas.prototype

import kotlin.test.*

class PolylineGeometryTest {
    private fun p(x: Double, y: Double) = PointD(x, y)

    @Test fun pointSegmentDistanceCoversProjectionEndpointsAndDegenerateSegment() {
        assertEquals(25.0, PolylineGeometry.pointSegmentDistanceSquared(p(5.0, 5.0), p(0.0, 0.0), p(10.0, 0.0)))
        assertEquals(50.0, PolylineGeometry.pointSegmentDistanceSquared(p(-5.0, 5.0), p(0.0, 0.0), p(10.0, 0.0)))
        assertEquals(25.0, PolylineGeometry.pointSegmentDistanceSquared(p(3.0, 4.0), p(0.0, 0.0), p(0.0, 0.0)))
    }

    @Test fun segmentDistanceIsZeroForCrossingAndPositiveForParallel() {
        assertEquals(0.0, PolylineGeometry.segmentDistanceSquared(
            p(0.0, 0.0), p(10.0, 10.0), p(0.0, 10.0), p(10.0, 0.0)))
        assertEquals(16.0, PolylineGeometry.segmentDistanceSquared(
            p(0.0, 0.0), p(10.0, 0.0), p(0.0, 4.0), p(10.0, 4.0)))
        // A small segment entirely on a thick centerline is within the radius, without intersecting it.
        assertEquals(4.0, PolylineGeometry.segmentDistanceSquared(
            p(0.0, 0.0), p(100.0, 0.0), p(40.0, 2.0), p(60.0, 2.0)))
    }

    @Test fun polylinesWithinHonoursThresholdBoundary() {
        val a = listOf(p(0.0, 0.0), p(10.0, 0.0))
        val b = listOf(p(5.0, 3.0))
        assertTrue(PolylineGeometry.polylinesWithin(a, b, 3.0))
        assertFalse(PolylineGeometry.polylinesWithin(a, b, 2.99))
    }

    @Test fun pointInPolygonHandlesConvexConcaveAndOutside() {
        val square = listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.0, 10.0), p(0.0, 10.0))
        assertTrue(PolylineGeometry.pointInPolygon(p(5.0, 5.0), square))
        assertFalse(PolylineGeometry.pointInPolygon(p(15.0, 5.0), square))
        val concave = listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.0, 10.0), p(5.0, 4.0), p(0.0, 10.0))
        assertFalse(PolylineGeometry.pointInPolygon(p(5.0, 6.0), concave))
        assertTrue(PolylineGeometry.pointInPolygon(p(2.0, 6.0), concave))
    }

    @Test fun maxScaleIsExactForRotationUniformAndNonUniformScale() {
        assertEquals(1.0, PolylineGeometry.maxScale(Affine2D()), 1e-12)
        val rotation = Affine2D(a = 0.6, b = 0.8, c = -0.8, d = 0.6)
        assertEquals(1.0, PolylineGeometry.maxScale(rotation), 1e-12)
        assertEquals(2.0, PolylineGeometry.maxScale(Affine2D(a = 2.0, d = 2.0)), 1e-12)
        assertEquals(3.0, PolylineGeometry.maxScale(Affine2D(a = 3.0, d = 0.5)), 1e-12)
    }

    @Test fun boundsOverlapIncludesTangentAndExcludesSeparated() {
        val a = BoundsD(0.0, 0.0, 10.0, 10.0)
        assertTrue(PolylineGeometry.overlaps(a, BoundsD(10.0, 10.0, 20.0, 20.0)))
        assertFalse(PolylineGeometry.overlaps(a, BoundsD(10.01, 0.0, 20.0, 10.0)))
    }
}
