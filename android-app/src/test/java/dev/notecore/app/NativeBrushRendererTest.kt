package dev.notecore.app

import android.graphics.*
import dev.notecore.canvas.prototype.*
import java.io.File
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.*

/** Native Skia on the Windows JVM, NOT emulator/ART, GPU, real input or device performance evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeBrushRendererTest {
    private fun geometry(kind: BrushKind = BrushKind.HIGHLIGHTER, width: Double = 2.0,
                         xs: DoubleArray = doubleArrayOf(0.0, 4.0, 0.0), pressures: FloatArray = FloatArray(xs.size) { 1f },
                         argb: Int = 0xffffff00.toInt()): BrushGeometry =
        assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(StrokeSamples.copyOf(xs, DoubleArray(xs.size), pressures,
            LongArray(xs.size) { it.toLong() }), BrushSpec(kind, 2, width, argb))).geometry
    private fun renderer(geometry: BrushGeometry = geometry(), transform: Affine2D = Affine2D(a = 16.0, d = 16.0, tx = 24.0, ty = 64.0)) =
        NativeBrushRenderer.prepare(assertIs<BrushPathPlanResult.Ready>(BrushPathPlanner.prepare(geometry, transform)).plan)
    private fun image(renderer: NativeBrushRenderer, times: Int = 1): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it); repeat(times) { renderer.draw(canvas) }
    }

    @Test fun retracedHighlighterCompositesOnceWithinTheStrokeAndTwiceAcrossStrokes() {
        val renderer = renderer()
        val once = image(renderer); val twice = image(renderer, 2)
        assertEquals(96, Color.alpha(once.getPixel(56, 64)))
        assertEquals(156, Color.alpha(twice.getPixel(56, 64)))
        assertEquals(255, Color.red(once.getPixel(56, 64))); assertEquals(255, Color.green(once.getPixel(56, 64)))
        assertEquals(0, Color.alpha(once.getPixel(56, 90)))
        once.recycle(); twice.recycle()
    }

    @Test fun pressureGeometryHasDifferentEndpointWidthsInNativePixels() {
        val geometry = geometry(BrushKind.PRESSURE, 8.0, doubleArrayOf(0.0, 4.0), floatArrayOf(0f, 1f), 0xff3568be.toInt())
        val bitmap = image(renderer(geometry, Affine2D(a = 8.0, d = 8.0, tx = 24.0, ty = 64.0)))
        assertEquals(0, Color.alpha(bitmap.getPixel(24, 75)))
        assertEquals(255, Color.alpha(bitmap.getPixel(56, 88)))
        bitmap.recycle()
    }

    @Test fun monolineDoesNotTurnInputPressureIntoNativeWidth() {
        val a = image(renderer(geometry(BrushKind.MONOLINE, pressures = floatArrayOf(0f, 0f, 0f))))
        val b = image(renderer(geometry(BrushKind.MONOLINE, pressures = floatArrayOf(1f, 0.5f, 1f))))
        assertTrue(a.sameAs(b)); a.recycle(); b.recycle()
    }

    @Test fun reflectedFullAffineAndLargeOriginsUseTargetCoordinatesOnlyOnce() {
        val reflected = image(renderer(transform = Affine2D(a = -16.0, b = 4.0, c = 2.0, d = 16.0, tx = 88.0, ty = 56.0)))
        assertEquals(96, Color.alpha(reflected.getPixel(56, 64)))
        assertEquals(0, Color.alpha(reflected.getPixel(56, 100)))
        reflected.recycle()
        val large = assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(
            StrokeSamples.copyOf(doubleArrayOf(1e9), doubleArrayOf(1e9), floatArrayOf(1f), longArrayOf(1)),
            BrushSpec(BrushKind.MONOLINE, 2, 2.0, 0xffff0000.toInt()))).geometry
        val dot = image(renderer(large, Affine2D(a = 4.0, d = 4.0, tx = 64 - 4e9, ty = 64 - 4e9)))
        assertEquals(255, Color.alpha(dot.getPixel(64, 64))); assertEquals(0, Color.alpha(dot.getPixel(70, 64)))
        dot.recycle()
    }

    @Test fun nativePrepareRefusesAFloat64PlanRatherThanSilentlyQuantizingIt() {
        val plan = assertIs<BrushPathPlanResult.Ready>(BrushPathPlanner.prepare(geometry(), precision = BrushPathPrecision.FLOAT64)).plan
        assertFailsWith<IllegalArgumentException> { NativeBrushRenderer.prepare(plan) }
    }

    @Test fun paintAndBitmapStateDoNotLeakAcrossPreparedStrokes() {
        val canvasImage = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); val canvas = Canvas(canvasImage)
        renderer().draw(canvas)
        renderer(geometry(BrushKind.MONOLINE, argb = 0xff0000ff.toInt())).draw(canvas)
        assertEquals(0xff0000ff.toInt(), canvasImage.getPixel(56, 64))
        canvas.drawRect(0f, 0f, 4f, 4f, Paint().apply { color = Color.RED })
        assertEquals(Color.RED, canvasImage.getPixel(1, 1)); canvasImage.recycle()
    }

    @Test fun allSixSpecificationGoldensMatchIndependentNativePathsAndProduceInspectablePngs() {
        val catalog = JSONObject(File(checkNotNull(System.getProperty("notecore.brush.fixtures"))).readText(Charsets.UTF_8))
        val cases = catalog.getJSONArray("cases"); assertEquals(6, cases.length())
        val output = File(checkNotNull(System.getProperty("notecore.brush.images"))).apply { mkdirs() }
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index); val rows = case.getJSONArray("samples"); val brush = case.getJSONObject("brush")
            val samples = StrokeSamples.copyOf(DoubleArray(rows.length()) { rows.getJSONArray(it).getDouble(0) },
                DoubleArray(rows.length()) { rows.getJSONArray(it).getDouble(1) }, FloatArray(rows.length()) { rows.getJSONArray(it).getDouble(2).toFloat() },
                LongArray(rows.length()) { rows.getJSONArray(it).getLong(3) })
            val geometry = assertIs<BrushGeometryResult.Ready>(BrushGeometryBuilder.build(samples, BrushSpec(BrushKind.valueOf(brush.getString("kind")),
                brush.getInt("algorithm_version"), brush.getDouble("width"), brush.getInt("argb")))).geometry
            val actual = image(renderer(geometry, Affine2D(a = 4.0, d = 4.0, tx = 64 - 4 * geometry.origin.x, ty = 64 - 4 * geometry.origin.y)))
            val expected = case.getJSONObject("expected"); val vertices = expected.getJSONArray("vertices"); val offsets = expected.getJSONArray("contour_offsets")
            val path = Path().apply { fillType = Path.FillType.WINDING }
            for (contour in 0 until offsets.length() - 1) {
                val start = offsets.getInt(contour); val end = offsets.getInt(contour + 1)
                for (v in start until end) {
                    val p = vertices.getJSONArray(v); val x = (p.getDouble(0) * 4 + 64).toFloat(); val y = (p.getDouble(1) * 4 + 64).toFloat()
                    if (v == start) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            val reference = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            Canvas(reference).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = expected.getInt("argb"); style = Paint.Style.FILL })
            for (y in 0 until 128) for (x in 0 until 128) {
                val a = actual.getPixel(x, y); val b = reference.getPixel(x, y)
                for (shift in listOf(0, 8, 16, 24)) assertTrue(abs((a ushr shift and 255) - (b ushr shift and 255)) <= 2,
                    "${case.getString("id")} pixel $x,$y channel $shift")
            }
            File(output, case.getString("id") + ".png").outputStream().use { assertTrue(actual.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            actual.recycle(); reference.recycle()
        }
    }
}
