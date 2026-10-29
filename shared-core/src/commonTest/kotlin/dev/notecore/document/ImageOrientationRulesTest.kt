package dev.notecore.document

import dev.notecore.canvas.prototype.PointD
import kotlin.test.*

class ImageOrientationRulesTest {
    @Test fun allEightDirectionsMatchIndependentAsymmetricPixelGrid() {
        val expected = listOf(
            listOf(0, 1, 2, 3, 4, 5), listOf(1, 0, 3, 2, 5, 4),
            listOf(5, 4, 3, 2, 1, 0), listOf(4, 5, 2, 3, 0, 1),
            listOf(0, 2, 4, 1, 3, 5), listOf(4, 2, 0, 5, 3, 1),
            listOf(5, 3, 1, 4, 2, 0), listOf(1, 3, 5, 0, 2, 4))
        for (orientation in 1..8) {
            val m = assertNotNull(ImageOrientationRules.resolve(2, 3, orientation).mapping)
            val actual = IntArray(6) { -1 }
            for (y in 0..2) for (x in 0..1) {
                val center = PointD(x + .5, y + .5)
                val mapped = m.sourceToDisplay.map(center)
                actual[mapped.y.toInt() * m.width + mapped.x.toInt()] = y * 2 + x
                assertEquals(center, m.displayToSource.map(mapped))
            }
            assertEquals(expected[orientation - 1], actual.toList())
            assertEquals(if (orientation >= 5) 3 else 2, m.width)
            assertEquals(if (orientation >= 5) 2 else 3, m.height)
            assertEquals(2, m.sourceWidth); assertEquals(3, m.sourceHeight)
        }
    }

    @Test fun cornersCoverWholeDisplayAndEveryMappingIsInvertible() {
        for (orientation in 1..8) {
            val m = assertNotNull(ImageOrientationRules.resolve(403, 271, orientation).mapping)
            val corners = listOf(PointD(0.0, 0.0), PointD(403.0, 0.0), PointD(0.0, 271.0), PointD(403.0, 271.0))
            assertEquals(setOf(PointD(0.0, 0.0), PointD(m.width.toDouble(), 0.0), PointD(0.0, m.height.toDouble()),
                PointD(m.width.toDouble(), m.height.toDouble())), corners.map { m.sourceToDisplay.map(it) }.toSet())
            val source = PointD(123.25, 78.75)
            assertEquals(source, m.displayToSource.map(m.sourceToDisplay.map(source)))
        }
    }

    @Test fun invalidDimensionsUnknownOrientationAndBudgetReturnNoMapping() {
        for ((width, height) in listOf(0 to 3, 2 to -1)) {
            val result = ImageOrientationRules.resolve(width, height, 1)
            assertNull(result.mapping); assertEquals(ImageOrientationFailure.INVALID_DIMENSIONS, result.failure)
        }
        for (orientation in listOf(0, 9, Int.MIN_VALUE)) {
            val result = ImageOrientationRules.resolve(2, 3, orientation)
            assertNull(result.mapping); assertEquals(ImageOrientationFailure.UNKNOWN_ORIENTATION, result.failure)
        }
        assertEquals(ImageOrientationFailure.INVALID_BUDGET, ImageOrientationRules.resolve(2, 3, 1, 0).failure)
        assertEquals(ImageOrientationFailure.PIXEL_CAPACITY, ImageOrientationRules.resolve(2, 3, 1, 5).failure)
        assertNotNull(ImageOrientationRules.resolve(2, 3, 1, 6).mapping)
    }

    @Test fun pixelAdmissionUsesLongAndNeverOverflowsIntMultiplication() {
        assertEquals(ImageOrientationFailure.PIXEL_CAPACITY, ImageOrientationRules.resolve(65536, 65536, 6).failure)
        assertEquals(ImageOrientationFailure.PIXEL_CAPACITY, ImageOrientationRules.resolve(Int.MAX_VALUE, Int.MAX_VALUE, 1).failure)
        val exact = Int.MAX_VALUE.toLong() * Int.MAX_VALUE
        assertEquals(ImageOrientationFailure.PIXEL_CAPACITY, ImageOrientationRules.resolve(Int.MAX_VALUE, Int.MAX_VALUE, 1, exact - 1).failure)
        assertNotNull(ImageOrientationRules.resolve(Int.MAX_VALUE, Int.MAX_VALUE, 1, exact).mapping)
    }
}
