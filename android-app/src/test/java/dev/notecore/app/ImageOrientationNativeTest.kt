package dev.notecore.app

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import dev.notecore.canvas.prototype.Affine2D
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Actual encoded fixtures and host native decoder/Canvas; not ART, Apple or App acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOrientationNativeTest {
    // AndroidX synthesizes UNDEFINED (0) for absent orientation; defaultValue alone is insufficient.
    // Other values pass through to the shared strict validation, never clamp arbitrary corrupt tags.
    private fun normalizedOrientation(exif: ExifInterface): Int {
        val raw = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        return if (raw == ExifInterface.ORIENTATION_UNDEFINED) ExifInterface.ORIENTATION_NORMAL else raw
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sharedMapping(orientation: Int): Affine2D {
        val rules = Class.forName("dev.notecore.document.ImageOrientationRules")
        val result = rules.getMethod("resolve", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType).invoke(rules.getField("INSTANCE").get(null), 80, 120, orientation, 9600L)
        val mapping = assertNotNull(result.javaClass.getMethod("getMapping").invoke(result))
        return mapping.javaClass.getMethod("getSourceToDisplay").invoke(mapping) as Affine2D
    }
    private fun checkPixels(bitmap: Bitmap, samples: JSONArray, label: String) {
        repeat(samples.length()) { index ->
            val sample = samples.getJSONObject(index); val expected = sample.getJSONArray("rgba")
            val actual = bitmap.getPixel(sample.getInt("x"), sample.getInt("y"))
            assertEquals(expected.getInt(3), Color.alpha(actual), "$label alpha $index")
            if (Color.alpha(actual) > 0) {
                for ((channel, value) in listOf(Color.red(actual), Color.green(actual), Color.blue(actual)).withIndex())
                    assertTrue(kotlin.math.abs(value - expected.getInt(channel)) <= 3, "$label colour $index/$channel: $value")
            }
        }
    }
    private fun verify(format: String) {
        val directory = File(checkNotNull(System.getProperty("notecore.image.fixtures")))
        val output = File(checkNotNull(System.getProperty("notecore.image.outputs"))).apply { mkdirs() }
        val cases = JSONObject(File(directory, "manifest.json").readText(Charsets.UTF_8)).getJSONArray("cases")
        var count = 0
        repeat(cases.length()) { index ->
            val entry = cases.getJSONObject(index)
            if (entry.getString("format") != format) return@repeat
            count++
            val file = File(directory, entry.getString("file")); val bytes = file.readBytes()
            assertEquals(entry.getString("sha256"), sha(bytes))
            // Manifest is an oracle only: the transform consumes metadata parsed from the bytes.
            val orientation = bytes.inputStream().use {
                normalizedOrientation(ExifInterface(it))
            }
            assertEquals(entry.getInt("orientation"), orientation, file.name + " EXIF")
            val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val decoded = assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
            try {
                assertEquals(80, decoded.width); assertEquals(120, decoded.height)
                checkPixels(decoded, entry.getJSONArray("raw_samples"), file.name + " raw")
                val size = entry.getJSONArray("display_size")
                val display = Bitmap.createBitmap(size.getInt(0), size.getInt(1), Bitmap.Config.ARGB_8888)
                try {
                    display.eraseColor(Color.TRANSPARENT)
                    val m = sharedMapping(orientation)
                    val matrix = Matrix().apply { setValues(floatArrayOf(m.a.toFloat(), m.c.toFloat(), m.tx.toFloat(),
                        m.b.toFloat(), m.d.toFloat(), m.ty.toFloat(), 0f, 0f, 1f)) }
                    val canvas = Canvas(display); canvas.concat(matrix)
                    canvas.drawBitmap(decoded, 0f, 0f, Paint().apply { isFilterBitmap = false; isAntiAlias = false })
                    checkPixels(display, entry.getJSONArray("display_samples"), file.name + " display")
                    File(output, file.name + ".png").outputStream().use { assertTrue(display.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { display.recycle() }
            } finally { decoded.recycle() }
            assertEquals(entry.getString("sha256"), sha(file.readBytes()))
        }
        assertEquals(8, count)
    }
    @Test fun jpegDecoderLeavesDirectionToAdapterAndSharedMatrixMatchesPillow() = verify("jpeg")
    @Test fun pngAlphaAndOrientationArePreservedThroughNativeCanvas() = verify("png")

    @Test fun encoderWithoutOrientationNeedsExplicitUndefinedNormalization() {
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
            val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
            val bytes = try {
                java.io.ByteArrayOutputStream().use {
                    assertTrue(bitmap.compress(format, 100, it))
                    it.toByteArray()
                }
            } finally { bitmap.recycle() }
            val originalHash = sha(bytes)
            bytes.inputStream().use {
                val exif = ExifInterface(it)
                assertEquals(0, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
                assertEquals(1, normalizedOrientation(exif))
                assertEquals(Affine2D(), sharedMapping(normalizedOrientation(exif)))
            }
            assertEquals(originalHash, sha(bytes))
        }
    }
}
