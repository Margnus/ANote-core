package dev.notecore.canvas.prototype

import kotlin.test.*

class PointCreationTest {
    @Test fun arbitraryNonFiniteCoordinatesRefuseWithoutConstructingAPoint() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(PointD.validated(invalid, 1.0))
            assertNull(PointD.validated(1.0, invalid))
            assertNull(PointD.validated(invalid, invalid))
        }
        assertNotNull(PointD.validated(1.0, 2.0))
    }

    @Test fun acceptedCoordinatesKeepTheirExactCanonicalBits() {
        for (x in listOf(-0.0, 0.0, Double.MIN_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE)) {
            val point = assertNotNull(PointD.validated(x, -x))
            assertEquals(x.toRawBits(), point.x.toRawBits())
            assertEquals((-x).toRawBits(), point.y.toRawBits())
        }
    }

    @Test fun legacyRawConstructionKeepsItsOriginalRefusalContract() {
        assertFailsWith<IllegalArgumentException> { PointD(Double.NaN, 1.0) }
        assertFailsWith<IllegalArgumentException> { PointD(1.0, Double.POSITIVE_INFINITY) }
        assertEquals(PointD(1.0, 2.0), PointD.validated(1.0, 2.0))
    }
}
