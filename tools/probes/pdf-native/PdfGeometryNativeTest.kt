package dev.notecore.app

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.notecore.canvas.prototype.Affine2D
import dev.notecore.canvas.prototype.PointD
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Host native PDF/Canvas conformance, not ART, App UI or production annotation/export acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfGeometryNativeTest {
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun save(bitmap: Bitmap, file: File) = file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    /** Test-only invocation of compiled internal implementation, without introducing an exported facade. */
    private fun sharedMapping(rotation: Int): Affine2D {
        val cropClass = Class.forName("dev.notecore.document.PdfCropBox")
        val crop = cropClass.getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).newInstance(40.0, 50.0, 340.0, 230.0)
        val rules = Class.forName("dev.notecore.document.PdfPageGeometryRules")
        val result = rules.getMethod("resolve", cropClass, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .invoke(rules.getField("INSTANCE").get(null), crop, rotation, 1.0)
        val geometry = assertNotNull(result.javaClass.getMethod("getGeometry").invoke(result))
        return geometry.javaClass.getMethod("getPdfToPage").invoke(geometry) as Affine2D
    }

    @Test fun fourRotationNativePdfBackgroundAndSharedCoordinateOverlayAlign() {
        val fixture = File(checkNotNull(System.getProperty("notecore.pdf.fixture")))
        val recipe = JSONObject(File(checkNotNull(System.getProperty("notecore.pdf.recipe"))).readText(Charsets.UTF_8))
        val output = File(checkNotNull(System.getProperty("notecore.pdf.images"))).apply { mkdirs() }
        val before = sha(fixture)
        val anchors = listOf(PointD(56.0, 66.0), PointD(324.0, 66.0), PointD(56.0, 214.0), PointD(324.0, 214.0))
        val expectedPages = recipe.getJSONArray("expected_pages")
        ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                assertEquals(4, renderer.pageCount)
                repeat(4) { index -> renderer.openPage(index).use { page ->
                    val expected = expectedPages.getJSONObject(index)
                    assertEquals(expected.getInt("width"), page.width); assertEquals(expected.getInt("height"), page.height)
                    val mapping = sharedMapping(expected.getInt("rotation"))
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        save(bitmap, File(output, "background-$index.png"))
                        anchors.forEachIndexed { i, source ->
                            val p = mapping.map(source); val xy = expected.getJSONArray("marker_xy").getJSONArray(i)
                            assertEquals(xy.getDouble(0), p.x, 1e-8); assertEquals(xy.getDouble(1), p.y, 1e-8)
                            val color = recipe.getJSONArray("marker_rgb").getJSONArray(i)
                            val pixel = bitmap.getPixel(p.x.toInt(), p.y.toInt())
                            assertTrue(kotlin.math.abs(Color.red(pixel) - color.getInt(0)) <= 2)
                            assertTrue(kotlin.math.abs(Color.green(pixel) - color.getInt(1)) <= 2)
                            assertTrue(kotlin.math.abs(Color.blue(pixel) - color.getInt(2)) <= 2)
                        }
                        val canvas = Canvas(bitmap); val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
                        anchors.forEach { source -> mapping.map(source).let { p -> canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 3f, paint) } }
                        anchors.forEach { source -> mapping.map(source).let { p -> assertEquals(Color.BLACK, bitmap.getPixel(p.x.toInt(), p.y.toInt())) } }
                        save(bitmap, File(output, "overlay-$index.png"))
                    } finally { bitmap.recycle() }
                } }
            }
        }
        assertEquals(before, sha(fixture))
    }
}
