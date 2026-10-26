package dev.notecore.canvas.prototype

import kotlin.test.*

class BrushEnvelopePocTest {
    private fun samples(vararg xy: Pair<Double, Double>, pressure: FloatArray = FloatArray(xy.size) { 1f }) =
        StrokeSamples.copyOf(xy.map { it.first }.toDoubleArray(), xy.map { it.second }.toDoubleArray(), pressure, LongArray(xy.size) { it.toLong() })
    private fun envelope(samples: StrokeSamples, kind: BrushKind = BrushKind.MONOLINE, transform: Affine2D = Affine2D(), argb: Int = -1) =
        assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(samples, BrushSpec(kind, 1, 8.0, argb), transform)).envelope

    @Test fun tinyDiscsDoNotTreatTheirBoundingBoxAsExactCoverage() {
        for (kind in BrushKind.entries) for (scale in listOf(1.0, 1e-170)) {
            val e = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(samples(0.0 to 0.0),
                BrushSpec(kind, 1, 8 * scale, -1))).envelope
            assertTrue(e.containsScene(PointD(2 * scale, 2 * scale)))
            assertFalse(e.containsScene(PointD(3.6 * scale, 3.6 * scale)), "$kind scale=$scale outside circle, inside bbox")
            assertEquals(0.0, e.alphaAtScene(PointD(3.6 * scale, 3.6 * scale)))
        }
    }
    @Test fun tinyPressureSweepsAndReflectedShearedCornersKeepNormalScaleClassification() {
        for (scale in listOf(1.0, 1e-170)) {
            val pressure = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(
                samples(0.0 to 0.0, 10 * scale to 0.0, pressure = floatArrayOf(0f, 1f)),
                BrushSpec(BrushKind.PRESSURE, 1, 8 * scale, -1))).envelope
            assertTrue(pressure.containsScene(PointD(5 * scale, 2.6 * scale)))
            assertFalse(pressure.containsScene(PointD(5 * scale, 2.7 * scale)))
            val transform = Affine2D(a = -2.0, b = .5, c = 1.0, d = .75, tx = 10 * scale, ty = -3 * scale)
            for (kind in BrushKind.entries) {
                val e = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(
                    samples(0.0 to 0.0, 10 * scale to 0.0, 10 * scale to 10 * scale),
                    BrushSpec(kind, 1, 8 * scale, -1), transform)).envelope
                for ((point, hit) in listOf(PointD(5.0, 0.0) to true, PointD(12.0, -2.0) to true,
                    PointD(13.0, -3.0) to false, PointD(5.0, 5.0) to false)) {
                    assertEquals(hit, e.containsScene(transform.map(PointD(point.x * scale, point.y * scale))), "$kind scale=$scale $point")
                }
            }
        }
    }
    @Test fun radiusUnderflowRefusesButRepresentableSubnormalDiscsStayGeometric() {
        for ((width, kind, pressure) in listOf(Triple(Double.MIN_VALUE, BrushKind.MONOLINE, 1f),
            Triple(1e-323, BrushKind.PRESSURE, 0f))) {
            assertEquals("radius-underflow", assertIs<BrushEnvelopeBuild.Rejected>(BrushEnvelopePoc.build(
                samples(0.0 to 0.0, pressure = floatArrayOf(pressure)), BrushSpec(kind, 1, width, -1))).reason)
        }
        val e = assertIs<BrushEnvelopeBuild.Ready>(BrushEnvelopePoc.build(samples(0.0 to 0.0),
            BrushSpec(BrushKind.MONOLINE, 1, 1e-323, -1))).envelope
        assertTrue(e.containsScene(PointD(0.0, 0.0)))
        assertFalse(e.containsScene(PointD(Double.MIN_VALUE, Double.MIN_VALUE)))
    }

    @Test fun roundCapsJoinsAndOpenOutsideCorner() {
        val e = envelope(samples(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0))
        assertEquals(BoundsD(-4.0, -4.0, 14.0, 14.0), e.paintedBounds)
        listOf(PointD(-4.0, 0.0), PointD(5.0, 4.0), PointD(10.0, 14.0), PointD(12.0, -2.0)).forEach { assertTrue(e.containsScene(it)) }
        assertFalse(e.containsScene(PointD(13.0, -3.0)))
        assertFalse(e.containsScene(PointD(5.0, 5.0)))
    }

    @Test fun pressureUsesSweptTaperRatherThanInterpolatingAtNearestCenter() {
        val e = envelope(samples(0.0 to 0.0, 10.0 to 0.0, pressure = floatArrayOf(0f, 1f)), BrushKind.PRESSURE)
        assertEquals(BoundsD(-1.0, -4.0, 14.0, 4.0), e.paintedBounds)
        assertTrue(e.containsScene(PointD(5.0, 2.6))) // nearest center gives r=2.5 and would wrongly reject
        assertFalse(e.containsScene(PointD(5.0, 2.7)))
        assertTrue(e.containsScene(PointD(-1.0, 0.0)))
        assertFalse(e.containsScene(PointD(-1.01, 0.0)))
    }

    @Test fun stationaryAndContainedDiscsPreserveAllSamples() {
        val e = envelope(samples(0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0, pressure = floatArrayOf(0f, 1f, 0f)), BrushKind.PRESSURE)
        assertEquals(3, e.sampleCount)
        assertTrue(e.containsScene(PointD(0.0, 4.0))); assertFalse(e.containsScene(PointD(0.0, 4.01)))
        val contained = envelope(samples(0.0 to 0.0, 1.0 to 0.0, pressure = floatArrayOf(0f, 1f)), BrushKind.PRESSURE)
        assertTrue(contained.containsScene(PointD(-3.0, 0.0)))
    }

    @Test fun highlighterCoverageUnionsBeforeAlphaAndKeepsArgbOpacity() {
        val e = envelope(samples(0.0 to 0.0, 10.0 to 0.0, 0.0 to 0.0), BrushKind.HIGHLIGHTER, argb = 0x80ffff00.toInt())
        assertEquals(128.0 / 255 * 0.25, e.alphaAtScene(PointD(5.0, 0.0)))
        assertEquals(0.0, e.alphaAtScene(PointD(5.0, 5.0)))
        // Same stroke self-overlap stays constant. Separate objects still source-over.
        val alpha = e.sourceAlpha
        assertTrue(alpha + alpha * (1 - alpha) > alpha)
    }

    @Test fun affineDiscBecomesEllipseAndShearBoundsUseRowNorms() {
        val e = envelope(samples(0.0 to 0.0), transform = Affine2D(a = 2.0, d = 0.5, tx = 20.0, ty = 30.0))
        assertEquals(BoundsD(12.0, 28.0, 28.0, 32.0), e.paintedBounds)
        assertTrue(e.containsScene(PointD(28.0, 30.0)))
        assertFalse(e.containsScene(PointD(20.0, 32.01)))
        val shear = envelope(samples(0.0 to 0.0), transform = Affine2D(c = 1.0))
        assertEquals(-4 * kotlin.math.sqrt(2.0), shear.paintedBounds.left)
        assertTrue(shear.containsScene(PointD(4.0, 4.0)))
    }

    @Test fun invalidVersionBudgetSingularityAndOverflowExplicitlyReject() {
        val points = samples(1e9 to 1e9, 1e9 to 1e9)
        assertEquals("sample-budget", assertIs<BrushEnvelopeBuild.Rejected>(BrushEnvelopePoc.build(points, BrushSpec(BrushKind.MONOLINE, 1, 8.0, -1), maxSamples = 1)).reason)
        assertEquals("unsupported-brush-version", assertIs<BrushEnvelopeBuild.Rejected>(BrushEnvelopePoc.build(points, BrushSpec(BrushKind.MONOLINE, 2, 8.0, -1))).reason)
        assertIs<BrushEnvelopeBuild.Rejected>(BrushEnvelopePoc.build(points, BrushSpec(BrushKind.MONOLINE, 1, 8.0, -1), Affine2D(a = 0.0)))
        assertEquals("painted-bounds-overflow", assertIs<BrushEnvelopeBuild.Rejected>(BrushEnvelopePoc.build(points, BrushSpec(BrushKind.MONOLINE, 1, 8.0, -1), Affine2D(a = 1e308, d = 1e308))).reason)
    }

    @Test fun transformedPaintedBoundsNeverExcludeInteriorFromEitherIndex() {
        val transform = Affine2D(a = -2.0, b = 0.5, c = 1.0, d = 0.75, tx = -100.0, ty = 50.0)
        val envelopes = (0 until 100).map { id ->
            envelope(samples(id * 7.0 to 0.0, id * 7.0 + 10 to 10.0, pressure = floatArrayOf(0f, 1f)), BrushKind.PRESSURE, transform)
        }
        val entries = envelopes.mapIndexed { id, e -> SpatialEntry(id, e.paintedBounds) }
        val grid = GridIndexPoc().also { entries.forEach(it::put) }; val tree = PackedRTreePoc(entries)
        envelopes.forEachIndexed { id, e ->
            // Probe a disc strictly inside the continuous tapered sweep, independently
            // of the AABB implementation, under reflection, shear and nonuniform scale.
            for (step in 0..20) {
                val t = step / 20.0
                val radius = 1 + 3 * t
                val p = transform.map(PointD(id * 7.0 + 10 * t, 10 * t + radius * 0.9))
                assertTrue(e.containsScene(p), "id=$id t=$t")
                val query = BoundsD(p.x, p.y, p.x, p.y)
                assertTrue(id in grid.query(query).ids); assertTrue(id in tree.query(query).ids)
            }
        }
    }
}
