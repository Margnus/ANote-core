package dev.notecore.app

import android.content.Context
import android.graphics.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Actual ART counterpart of the existing odd-image host profile; not a production decoder. */
object ImageDecodeBudgetArtChecks {
    private fun plan(width: Int, height: Int, bytes: Long): Any? {
        val rules = Class.forName("dev.notecore.document.ImageDecodeRules")
        val result = rules.getMethod("plan", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(rules.getField("INSTANCE").get(null), width, height, 6, 40, 60, 1_000_000L, bytes)
        return result.javaClass.getMethod("getPlan").invoke(result)
    }
    private fun number(plan: Any, name: String) = plan.javaClass.getMethod("get$name").invoke(plan) as Number
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun verify(context: Context): String {
        val output = File(context.cacheDir, "image-budget-art-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val rows = JSONArray()
        for ((format, name) in listOf(Bitmap.CompressFormat.JPEG to "jpeg", Bitmap.CompressFormat.PNG to "png")) {
            val source = Bitmap.createBitmap(257, 193, Bitmap.Config.ARGB_8888)
            val encoded = try {
                source.eraseColor(if (name == "png") Color.argb(128, 255, 0, 0) else Color.RED)
                Canvas(source).drawRect(128f, 0f, 257f, 193f, Paint().apply { color = Color.BLUE })
                ByteArrayOutputStream().use { buffer -> check(source.compress(format, 100, buffer)); buffer.toByteArray() }
            } finally { source.recycle() }
            val digest = sha(encoded)
            val file = File(output, "odd.$name").apply { writeBytes(encoded) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
            check(BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds) == null)
            check(bounds.outWidth == 257 && bounds.outHeight == 193)
            val expected = checkNotNull(plan(bounds.outWidth, bounds.outHeight, 1_000_000L))
            val sample = number(expected, "SampleSize").toInt(); check(sample == 4)
            val maxBytes = number(expected, "ArgbByteUpperBound").toLong()
            var decodeCalls = 0
            fun admittedDecode(bytes: Long): Bitmap? {
                val accepted = plan(bounds.outWidth, bounds.outHeight, bytes) ?: return null
                decodeCalls++
                return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, BitmapFactory.Options().apply {
                    inScaled = false; inSampleSize = number(accepted, "SampleSize").toInt()
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
            check(admittedDecode(maxBytes - 1) == null && decodeCalls == 0)
            val decoded = checkNotNull(admittedDecode(maxBytes))
            try {
                check(decodeCalls == 1 && decoded.config == Bitmap.Config.ARGB_8888)
                check(decoded.width in (257 / sample)..number(expected, "MaxWidth").toInt())
                check(decoded.height in (193 / sample)..number(expected, "MaxHeight").toInt())
                check(decoded.allocationByteCount.toLong() <= maxBytes)
                check(decoded.height >= 40 && decoded.width >= 60)
                rows.put(JSONObject().put("format", name).put("source_sha256", digest).put("sample", sample)
                    .put("width", decoded.width).put("height", decoded.height).put("row_bytes", decoded.rowBytes)
                    .put("allocation_bytes", decoded.allocationByteCount).put("planned_argb_bytes", maxBytes)
                    .put("rejected_full_decode_calls", 0).put("successful_full_decode_calls", decodeCalls))
                File(output, "$name-decoded.png").outputStream().use { check(decoded.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { decoded.recycle() }
            check(sha(encoded) == digest && sha(file.readBytes()) == digest)
        }
        val result = JSONObject().put("status", "passed").put("output", output.absolutePath).put("rows", rows)
            .put("scope", "Actual API35 BitmapFactory profile; orientation6 is trusted test metadata, no EXIF/heap/scratch/owner/App acceptance")
        File(output, "result.json").writeText(result.toString(2) + "\n", Charsets.UTF_8)
        return result.toString()
    }
}
