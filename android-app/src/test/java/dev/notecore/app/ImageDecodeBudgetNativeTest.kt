package dev.notecore.app

import android.graphics.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

/** Host codec profile and admission call ordering; allocationByteCount is not process/native heap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDecodeBudgetNativeTest {
    private fun sharedPlan(width: Int, height: Int, bytes: Long): Any? {
        val rules = Class.forName("dev.notecore.document.ImageDecodeRules")
        val result = rules.getMethod("plan", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(rules.getField("INSTANCE").get(null), width, height, 6, 40, 60, 1_000_000L, bytes)
        return result.javaClass.getMethod("getPlan").invoke(result)
    }
    private fun number(plan: Any, name: String) = plan.javaClass.getMethod("get$name").invoke(plan) as Number
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun verify(format: Bitmap.CompressFormat, name: String) {
        val output = File(checkNotNull(System.getProperty("notecore.image.budget.outputs"))).apply { mkdirs() }
        val source = Bitmap.createBitmap(257, 193, Bitmap.Config.ARGB_8888)
        val encoded = try {
            source.eraseColor(if (name == "png") Color.argb(128, 255, 0, 0) else Color.RED)
            Canvas(source).drawRect(128f, 0f, 257f, 193f, Paint().apply { color = Color.BLUE })
            ByteArrayOutputStream().use { buffer -> assertTrue(source.compress(format, 100, buffer)); buffer.toByteArray() }
        } finally { source.recycle() }
        val originalHash = sha(encoded); val sourceFile = File(output, "odd.$name").apply { writeBytes(encoded) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        assertNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds))
        assertEquals(257, bounds.outWidth); assertEquals(193, bounds.outHeight)
        val plan = assertNotNull(sharedPlan(bounds.outWidth, bounds.outHeight, 1_000_000))
        val sample = number(plan, "SampleSize").toInt(); assertEquals(4, sample)
        val maxBytes = number(plan, "ArgbByteUpperBound").toLong()
        var fullDecodeCalls = 0
        fun admittedDecode(budget: Long): Bitmap? {
            val admitted = sharedPlan(bounds.outWidth, bounds.outHeight, budget) ?: return null
            fullDecodeCalls++
            return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply {
                inScaled = false; inSampleSize = number(admitted, "SampleSize").toInt(); inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
        assertNull(admittedDecode(maxBytes - 1)); assertEquals(0, fullDecodeCalls)
        val decoded = assertNotNull(admittedDecode(maxBytes))
        try {
            assertEquals(1, fullDecodeCalls); assertEquals(Bitmap.Config.ARGB_8888, decoded.config)
            assertTrue(decoded.width in (257 / sample)..number(plan, "MaxWidth").toInt())
            assertTrue(decoded.height in (193 / sample)..number(plan, "MaxHeight").toInt())
            assertTrue(decoded.allocationByteCount.toLong() <= maxBytes)
            // Raw axes swap after orientation 6; conservative quality bounds remain satisfied.
            assertTrue(decoded.height >= 40 && decoded.width >= 60)
            val observation = JSONObject().put("format", name).put("source_sha256", originalHash)
                .put("sample", sample).put("width", decoded.width).put("height", decoded.height)
                .put("row_bytes", decoded.rowBytes).put("allocation_bytes", decoded.allocationByteCount)
                .put("planned_argb_bytes", maxBytes).put("rejected_full_decode_calls", 0).put("successful_full_decode_calls", fullDecodeCalls)
            File(output, "$name.json").writeText(observation.toString(2) + "\n", Charsets.UTF_8)
        } finally { decoded.recycle() }
        assertEquals(originalHash, sha(encoded)); assertEquals(originalHash, sha(sourceFile.readBytes()))
    }
    @Test fun oddJpegDecodeFitsConservativeAdmissionAndRejectsBeforeFullDecode() = verify(Bitmap.CompressFormat.JPEG, "jpeg")
    @Test fun oddPngDecodeFitsConservativeAdmissionAndRejectsBeforeFullDecode() = verify(Bitmap.CompressFormat.PNG, "png")
}
