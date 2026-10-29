package dev.notecore.document

import dev.notecore.canvas.prototype.PointD
import kotlin.test.*

class PdfPageGeometryRulesTest {
    private val crop = PdfCropBox(40.0, 50.0, 340.0, 230.0)
    private fun geometry(rotation: Int, unit: Double = 1.0, box: PdfCropBox = crop) =
        assertNotNull(PdfPageGeometryRules.resolve(box, rotation, unit).geometry)
    private fun near(expected: PointD, actual: PointD) {
        assertEquals(expected.x, actual.x, 1e-8); assertEquals(expected.y, actual.y, 1e-8)
    }

    @Test fun originalFixtureFourRotationColorAnchorsMatchIndependentExpectedPositions() {
        // Centers from generator rectangles; expected values pinned in fixtures/pdf/recipe.json.
        val anchors = listOf(PointD(56.0, 66.0), PointD(324.0, 66.0), PointD(56.0, 214.0), PointD(324.0, 214.0))
        val expected = listOf(
            listOf(PointD(16.0, 164.0), PointD(284.0, 164.0), PointD(16.0, 16.0), PointD(284.0, 16.0)),
            listOf(PointD(16.0, 16.0), PointD(16.0, 284.0), PointD(164.0, 16.0), PointD(164.0, 284.0)),
            listOf(PointD(284.0, 16.0), PointD(16.0, 16.0), PointD(284.0, 164.0), PointD(16.0, 164.0)),
            listOf(PointD(164.0, 284.0), PointD(164.0, 16.0), PointD(16.0, 284.0), PointD(16.0, 16.0)))
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val g = geometry(rotation)
            assertEquals(if (index % 2 == 0) 300.0 else 180.0, g.width)
            assertEquals(if (index % 2 == 0) 180.0 else 300.0, g.height)
            anchors.forEachIndexed { i, point ->
                near(expected[index][i], g.pdfToPage.map(point))
                near(point, g.pageToPdf.map(expected[index][i]))
            }
        }
    }

    @Test fun userUnitScalesOnlyCanonicalCoordinatesAndInverseReturnsOriginalUnits() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val one = geometry(rotation); val scaled = geometry(rotation, 2.5)
            assertEquals(one.width * 2.5, scaled.width); assertEquals(one.height * 2.5, scaled.height)
            for (point in listOf(PointD(40.0, 50.0), PointD(340.0, 230.0), PointD(183.25, 107.75))) {
                val expected = one.pdfToPage.map(point).let { PointD(it.x * 2.5, it.y * 2.5) }
                near(expected, scaled.pdfToPage.map(point)); near(point, scaled.pageToPdf.map(expected))
            }
            assertEquals(crop, scaled.crop); assertEquals(2.5, scaled.userUnit)
        }
    }

    @Test fun negativeOriginCornersCoverExactlyTheRotatedCanonicalPage() {
        val box = PdfCropBox(-200.0, -350.0, 100.0, -170.0)
        val corners = listOf(PointD(box.left, box.bottom), PointD(box.right, box.bottom),
            PointD(box.left, box.top), PointD(box.right, box.top))
        for (rotation in listOf(0, 90, 180, 270)) {
            val g = geometry(rotation, box = box)
            assertEquals(setOf(PointD(0.0, 0.0), PointD(g.width, 0.0), PointD(0.0, g.height), PointD(g.width, g.height)),
                corners.map { g.pdfToPage.map(it) }.toSet())
            corners.forEach { near(it, g.pageToPdf.map(g.pdfToPage.map(it))) }
        }
    }

    @Test fun multipleTurnsAndNegativeRightAnglesNormalizeWithoutDoubleRotation() {
        for ((input, expected) in listOf(-90 to 270, -450 to 270, 450 to 90, 720 to 0, -720 to 0)) {
            val normalized = geometry(input); val base = geometry(expected)
            assertEquals(expected, normalized.rotation)
            assertEquals(base.pdfToPage, normalized.pdfToPage); assertEquals(base.pageToPdf, normalized.pageToPdf)
        }
        assertEquals(PdfPageGeometryFailure.INVALID_ROTATION, PdfPageGeometryRules.resolve(crop, 45).failure)
    }

    @Test fun invalidBoxesUnitsAndCanonicalSizeOverflowReturnNoPartialGeometry() {
        for (box in listOf(crop.copy(left = Double.NaN), crop.copy(right = Double.POSITIVE_INFINITY),
            crop.copy(right = crop.left), crop.copy(top = crop.bottom - 1), crop.copy(left = -1e10))) {
            val result = PdfPageGeometryRules.resolve(box, 0)
            assertNull(result.geometry); assertEquals(PdfPageGeometryFailure.INVALID_BOX, result.failure)
        }
        for (unit in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = PdfPageGeometryRules.resolve(crop, 0, unit)
            assertNull(result.geometry); assertEquals(PdfPageGeometryFailure.INVALID_USER_UNIT, result.failure)
        }
        for (unit in listOf(Double.MIN_VALUE, Double.MAX_VALUE, 100.0)) {
            val result = PdfPageGeometryRules.resolve(crop, 90, unit)
            assertNull(result.geometry); assertEquals(PdfPageGeometryFailure.SIZE_LIMIT, result.failure)
        }
        assertEquals(PdfCropBox(40.0, 50.0, 340.0, 230.0), crop)
    }

    @Test fun asymmetricInteriorGridRoundTripsWithScaledReflectedAxis() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val g = geometry(rotation, 1.25)
            for (x in listOf(40.0, 67.25, 183.5, 340.0)) for (y in listOf(50.0, 78.75, 192.5, 230.0)) {
                val point = PointD(x, y); val page = g.pdfToPage.map(point)
                assertTrue(page.x in 0.0..g.width && page.y in 0.0..g.height)
                near(point, g.pageToPdf.map(page))
            }
        }
    }
}
