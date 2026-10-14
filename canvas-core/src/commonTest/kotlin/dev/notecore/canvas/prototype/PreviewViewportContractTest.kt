package dev.notecore.canvas.prototype

import dev.notecore.canvas.preview.PreviewViewport
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class PreviewViewportContractTest {
    @Test fun fitCentersPaperAndResetRestoresExactMapping() {
        val v = PreviewViewport(); v.resize(1200.0, 1000.0)
        assertEquals(1.0, v.zoom); assertEquals(0.0, v.offsetY, 1e-9)
        assertEquals(500.0, v.toScene(600.0, 500.0).x, 1e-9)
        v.gesture(600.0, 500.0, 550.0, 600.0, 3.0); v.reset()
        assertEquals(1.0, v.zoom); assertEquals(700.0, v.toScene(600.0, 500.0).y, 1e-9)
    }
    @Test fun zoomAndPanKeepCentroidAnchorWithoutChangingSceneCoordinates() {
        val v = PreviewViewport(); v.resize(1000.0, 1400.0)
        val anchor = v.toScene(400.0, 600.0)
        v.gesture(400.0, 600.0, 450.0, 650.0, 2.0)
        assertEquals(anchor, v.toScene(450.0, 650.0))
        v.gesture(450.0, 650.0, 400.0, 600.0, 1.0)
        assertEquals(anchor, v.toScene(400.0, 600.0))
    }
    @Test fun zoomAndTranslationBoundsKeepPaperReachableAndRejectInvalidGestures() {
        val v = PreviewViewport(); v.resize(1000.0, 1400.0)
        v.gesture(500.0, 700.0, 500.0, 700.0, 100.0); assertEquals(6.0, v.zoom)
        v.gesture(0.0, 0.0, 1e9, -1e9, 1.0)
        assertEquals(0.0, v.offsetX); assertEquals(1400.0 - 1400 * v.scale, v.offsetY)
        val before = v.toView(42.0, 99.0)
        v.gesture(Double.NaN, 0.0, 0.0, 0.0, 1.0); v.gesture(0.0, 0.0, 0.0, 0.0, -1.0)
        assertEquals(before, v.toView(42.0, 99.0))
        v.gesture(500.0, 700.0, 500.0, 700.0, 0.001); assertEquals(1.0, v.zoom)
    }
    @Test fun resizePreservesCenterWhenUnclampedAndZeroSizeCanRecover() {
        val v = PreviewViewport(); v.resize(1000.0, 1400.0)
        v.gesture(500.0, 700.0, 500.0, 700.0, 3.0)
        v.resize(1400.0, 1000.0)
        assertEquals(500.0, v.toScene(700.0, 500.0).x, 1e-9)
        assertEquals(700.0, v.toScene(700.0, 500.0).y, 1e-9)
        v.resize(0.0, 0.0); assertEquals(0.0, v.scale)
        v.resize(1000.0, 1400.0); assertTrue(v.scale > 0)
        assertFailsWith<IllegalArgumentException> { v.resize(Double.NaN, 100.0) }
    }
    @Test fun randomGestureMappingsRoundTripWithoutDocumentMutation() {
        val v = PreviewViewport(); v.resize(947.0, 1291.0); val random = Random(29)
        repeat(1000) {
            v.gesture(400.0, 600.0, random.nextDouble(200.0, 700.0), random.nextDouble(300.0, 900.0), random.nextDouble(0.5, 2.0))
            val x = random.nextDouble(0.0, 1000.0); val y = random.nextDouble(0.0, 1400.0)
            val view = v.toView(x, y); val point = v.toScene(view.x, view.y)
            assertTrue(abs(point.x - x) < 1e-8 && abs(point.y - y) < 1e-8)
        }
    }
}
