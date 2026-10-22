package dev.notecore.canvas.prototype

import kotlin.math.abs
import kotlin.test.*

internal fun BrushPathPoc.covers(point: PointD): Boolean {
    var winding = 0
    for (contour in 0 until contourCount) {
        val start = contourStart(contour); val end = contourEnd(contour)
        for (i in start until end) {
            val j = if (i + 1 == end) start else i + 1
            val cross = (x(j) - x(i)) * (point.y - y(i)) - (point.x - x(i)) * (y(j) - y(i))
            if (y(i) <= point.y && y(j) > point.y && cross > 0) winding++
            if (y(i) > point.y && y(j) <= point.y && cross < 0) winding--
        }
    }
    return winding != 0
}

class BrushPathPocTest {
    private fun samples(count: Int = 5) = StrokeSamples.copyOf(
        DoubleArray(count) { if (it % 2 == 0) 10.0 else 35.0 },
        DoubleArray(count) { (it % 3) * 12.0 + 10 },
        FloatArray(count) { (it % 3) / 2f }, LongArray(count) { it.toLong() })

    @Test fun compoundContoursAgreeWithIndependentOracleAwayFromApproximationBoundary() {
        val transforms = listOf(Affine2D(), Affine2D(a = -0.8, b = 0.25, c = 0.3, d = 1.1, tx = 40.0))
        for (kind in BrushKind.entries) for (transform in transforms) {
            val input = samples(); val brush = BrushSpec(kind, 1, 8.0, -1)
            val path = assertIs<BrushPathBuild.Ready>(BrushPathPoc.build(input, brush, transform, sceneTolerance = 0.02)).path
            val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(input, brush, transform)).envelope
            var checked = 0
            for (x in 0 until 64) for (y in 0 until 64) {
                val point = PointD(x + 0.37, y + 0.61)
                val covered = path.covers(point)
                if (covered) assertTrue(oracle.containsScene(point), "$kind $point exceeds oracle")
                val delta = 0.05
                if (listOf(PointD(point.x - delta, point.y), PointD(point.x + delta, point.y),
                        PointD(point.x, point.y - delta), PointD(point.x, point.y + delta)).all(oracle::containsScene)) {
                    assertTrue(covered, "$kind $point misses interior"); checked++
                }
            }
            assertTrue(checked > 100)
            // All subpaths have the same signed orientation even after reflection.
            val signs = (0 until path.contourCount).map { c ->
                val start = path.contourStart(c); val end = path.contourEnd(c)
                (start until end).sumOf { i -> val j = if (i + 1 == end) start else i + 1; path.x(i) * path.y(j) - path.x(j) * path.y(i) }
            }
            assertTrue(signs.all { it > 0 } || signs.all { it < 0 })
        }
    }

    @Test fun coincidentSamplesContainedDiscsAndReverseStrokeKeepSingleAlpha() {
        val input = StrokeSamples.copyOf(doubleArrayOf(10.0, 10.0, 11.0, 10.0), DoubleArray(4) { 10.0 },
            floatArrayOf(0f, 1f, 0f, 1f), longArrayOf(0, 1, 2, 3))
        val pressure = assertIs<BrushPathBuild.Ready>(BrushPathPoc.build(input, BrushSpec(BrushKind.PRESSURE, 1, 8.0, -1))).path
        assertEquals(4, pressure.contourCount) // each connecting disc is contained
        assertTrue(pressure.covers(PointD(10.0, 13.5)))
        val marker = assertIs<BrushPathBuild.Ready>(BrushPathPoc.build(samples(), BrushSpec(BrushKind.HIGHLIGHTER, 1, 8.0, 0x80ffff00.toInt()))).path
        assertEquals(128.0 / 255 * 0.25, marker.sourceAlpha)
    }

    @Test fun countsBytesAndToleranceAreAdmittedBeforeOutputAndNeverTruncate() {
        val input = samples(1000); val brush = BrushSpec(BrushKind.MONOLINE, 1, 8.0, -1)
        assertEquals("output-budget", assertIs<BrushPathBuild.Rejected>(BrushPathPoc.build(input, brush, maxVertices = 10)).reason)
        assertEquals("output-budget", assertIs<BrushPathBuild.Rejected>(BrushPathPoc.build(input, brush, maxBytes = 100)).reason)
        assertEquals(1000, input.size)
        assertEquals("tolerance-budget", assertIs<BrushPathBuild.Rejected>(BrushPathPoc.build(samples(), brush,
            Affine2D(a = 1e10, d = 1e10), sceneTolerance = 0.0001)).reason)
        assertIs<BrushPathBuild.Rejected>(BrushPathPoc.build(input, brush.copy(algorithmVersion = 2)))
        assertIs<BrushPathBuild.Rejected>(BrushPathPoc.build(input, brush, Affine2D(d = 0.0)))
        assertFailsWith<IllegalArgumentException> { BrushPathPoc.build(input, brush, sceneTolerance = Double.NaN) }
        val path = assertIs<BrushPathBuild.Ready>(BrushPathPoc.build(samples(), brush)).path
        assertEquals(path.vertexCount * 16L + (path.contourCount + 1) * 4L, path.logicalBytes)
    }

    @Test fun cancellationBeforeAndDuringGenerationPublishesNoPartialPath() {
        val input = samples(512); val brush = BrushSpec(BrushKind.MONOLINE, 1, 8.0, -1)
        assertIs<BrushPathBuild.Cancelled>(BrushPathPoc.build(input, brush, cancelled = { true }))
        var checks = 0
        assertIs<BrushPathBuild.Cancelled>(BrushPathPoc.build(input, brush, cancelled = { ++checks >= 6 }))
        assertEquals(512, input.size)
        assertIs<BrushPathBuild.Ready>(BrushPathPoc.build(input, brush))
    }
}
