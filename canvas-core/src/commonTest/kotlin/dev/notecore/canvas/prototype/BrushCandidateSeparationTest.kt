package dev.notecore.canvas.prototype

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.*

/** Decision evidence: these profiles are deliberately different, not version aliases. */
class BrushCandidateSeparationTest {
    private fun samples(xs: DoubleArray, pressures: FloatArray = FloatArray(xs.size) { 1f }) =
        StrokeSamples.copyOf(xs, DoubleArray(xs.size), pressures, LongArray(xs.size) { it.toLong() })

    // Independent half-plane membership for the candidate's convex, positively wound contours.
    // This queries actual emitted vertices; it does not reproduce the tessellator.
    private fun contains(geometry: BrushGeometry, p: PointD): Boolean =
        (0 until geometry.contourCount).any { contour ->
            val start = geometry.contourStart(contour)
            val end = geometry.contourEnd(contour)
            (start until end).all { i ->
                val j = if (i + 1 == end) start else i + 1
                (geometry.x(j) - geometry.x(i)) * (p.y - geometry.y(i)) -
                    (geometry.y(j) - geometry.y(i)) * (p.x - geometry.x(i)) >= 0.0
            }
        }

    @Test fun highlighterProfilesHaveDifferentOpacityEvenBeforeRasterization() {
        val input = samples(doubleArrayOf(0.0))
        val brush = BrushSpec(BrushKind.HIGHLIGHTER, 1, 8.0, 0xffabcdef.toInt())
        val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(input, brush)).envelope
        val candidate = assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(input,
            brush.copy(algorithmVersion = 2))).geometry
        assertEquals(127, BrushDisplayRules.displayArgb(brush) ushr 24)
        assertEquals(0.25, oracle.alphaAtScene(PointD(0.0, 0.0)))
        assertEquals(96, candidate.argb ushr 24)
        // Partially transparent input also rules out treating the difference as byte rounding.
        val translucent = brush.copy(argb = 0x20abcdef)
        assertEquals(16, BrushDisplayRules.displayArgb(translucent) ushr 24)
        assertEquals(32, assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(input,
            translucent.copy(algorithmVersion = 2))).geometry.argb ushr 24)
        println("C1 profiles: opaque preview alpha=127/255; oracle=0.25; candidate2=96/255; translucent preview=16/255 candidate2=32/255")
    }

    @Test fun fixedSixteenSidedDotDoesNotPreserveRoundCoverageAtSupportedWidth() {
        val input = samples(doubleArrayOf(0.0))
        val brush = BrushSpec(BrushKind.MONOLINE, 1, 128.0, -1)
        val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(input, brush)).envelope
        val candidate = assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(input,
            brush.copy(algorithmVersion = 2))).geometry
        val probe = PointD(62.0, 12.0) // 62^2+12^2=3988 < radius^2=4096, strictly inside the circle.
        assertTrue(oracle.containsScene(probe))
        assertFalse(contains(candidate, probe))
        val deficitAtFourTimesZoom = 64.0 * (1.0 - cos(PI / 16.0)) * 4.0
        assertTrue(deficitAtFourTimesZoom > 4.9)
        println("C1 candidate2 dot: round probe(62,12)=inside; emitted polygon=outside; radial deficit at scale4=$deficitAtFourTimesZoom target units (not AA pixels)")
    }

    @Test fun perpendicularPressureBridgeIsNotContinuousTaperedDiscCoverage() {
        val input = samples(doubleArrayOf(0.0, 10.0), floatArrayOf(0f, 1f))
        val brush = BrushSpec(BrushKind.PRESSURE, 1, 8.0, -1)
        val oracle = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(input, brush)).envelope
        val candidate = assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(input,
            brush.copy(algorithmVersion = 2))).geometry
        val probe = PointD(5.0, 2.6)
        // The independent quadratic is 91*t^2 - 106*t + 30.76;
        // at t=53/91 its minimum is approximately -0.10813 < 0.
        assertTrue(oracle.containsScene(probe))
        assertFalse(contains(candidate, probe))
        assertFalse(oracle.containsScene(PointD(5.0, 2.7)))
        println("C1 pressure probe(5,2.6): continuous swept discs=inside; candidate2 polygon union=outside")
    }
}
