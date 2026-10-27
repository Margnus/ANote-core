package dev.notecore.canvas.prototype

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryContractTest {
    @Test fun inversePredicatePreservesConstructedInverseSupportAtNumericEdges() {
        val cases = listOf(Affine2D(), Affine2D(a = -1.0, d = 2.0, tx = 17.0),
            Affine2D(a = 1.0, b = .25, c = .5, d = 1.0), Affine2D(a = 0.0, d = 0.0),
            Affine2D(a = 1.0, b = 1.0, c = 1.0, d = 1.0 + 1e-13),
            Affine2D(a = 1e-300, d = 1e-300), Affine2D(a = 1e-300, d = 1e-300, tx = 1e100),
            Affine2D(a = 1e300, d = 1e300), Affine2D(a = Double.MIN_VALUE, d = Double.MIN_VALUE))
        val expected = listOf(true, true, true, false, false, true, false, true, false)
        for ((index, value) in cases.withIndex()) {
            assertEquals(expected[index], value.hasInverse(), "predicate $index")
            assertEquals(expected[index], value.inverseOrNull() != null, "constructed inverse $index")
        }
    }

    @Test fun inverseRoundTripsSeededTransforms() {
        val random = Random(20261014)
        repeat(500) {
            val transform = Affine2D(
                a = random.nextDouble(0.5, 4.0), b = random.nextDouble(-0.1, 0.1),
                c = random.nextDouble(-0.1, 0.1), d = random.nextDouble(0.5, 4.0),
                tx = random.nextDouble(-1e6, 1e6), ty = random.nextDouble(-1e6, 1e6),
            )
            val point = PointD(random.nextDouble(-1e4, 1e4), random.nextDouble(-1e4, 1e4))
            val back = assertNotNull(transform.inverseOrNull()).map(transform.map(point))
            assertTrue(abs(back.x - point.x) < 1e-7)
            assertTrue(abs(back.y - point.y) < 1e-7)
        }
    }

    @Test fun compositionOrderIsExplicit() {
        val translation = Affine2D(tx = 10.0, ty = 20.0)
        val scale = Affine2D(a = 2.0, d = 3.0)
        val point = PointD(4.0, 5.0)
        assertEquals(scale.map(translation.map(point)), translation.then(scale).map(point))
    }

    @Test fun rejectsNonFiniteAndSingularValues() {
        assertFailsWith<IllegalArgumentException> { PointD(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { Affine2D(tx = Double.POSITIVE_INFINITY) }
        assertNull(Affine2D(a = 1.0, b = 2.0, c = 2.0, d = 4.0).inverseOrNull())
    }

    @Test fun verySmallValidScaleCanBeInverted() {
        val transform = Affine2D(a = 1e-100, d = 1e-100)
        val back = assertNotNull(transform.inverseOrNull()).map(transform.map(PointD(2.0, 3.0)))
        assertTrue(abs(back.x - 2.0) < 1e-12 && abs(back.y - 3.0) < 1e-12)
    }

    @Test fun mappedBoundsCoverRotatedRectangle() {
        assertEquals(BoundsD(-4.0, 1.0, -2.0, 3.0), Affine2D(a = 0.0, b = 1.0, c = -1.0, d = 0.0).mapBounds(BoundsD(1.0, 2.0, 3.0, 4.0)))
    }
}
