package dev.notecore.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Read-only actual system-font consumer; not a text-box, font catalog, or platform lifetime protocol. */
internal object FontFallbackArtChecks {
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                check(n > 0); digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun verify(context: Context): String {
        check(Build.VERSION.SDK_INT >= 31)
        val primary = File("/system/fonts/RobotoStatic-Regular.ttf").canonicalFile
        check(primary.isFile)
        val hashes = linkedMapOf(primary.path to sha(primary))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.createFromFile(primary); textSize = 48f; color = Color.BLACK
        }
        val samples = listOf("A", "笔", "😀", "e\u0301")
        val rows = JSONArray()
        val image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val output = File(context.cacheDir, "font-fallback-${UUID.randomUUID()}")
        check(output.mkdirs())
        try {
            image.eraseColor(Color.WHITE)
            val canvas = Canvas(image)
            for ((index, text) in samples.withIndex()) {
                val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 64f, false, paint)
                check(glyphs.glyphCount() > 0 && glyphs.advance.isFinite() && glyphs.advance > 0)
                val fonts = JSONArray()
                var fallback = false
                repeat(glyphs.glyphCount()) { i ->
                    check(glyphs.getGlyphId(i) != 0)
                    val font = glyphs.getFont(i)
                    val file = checkNotNull(font.file).canonicalFile
                    check(file.isFile)
                    if (!hashes.containsKey(file.path)) hashes[file.path] = sha(file)
                    fallback = fallback || hashes.getValue(file.path) != hashes.getValue(primary.path) || font.ttcIndex != 0
                    fonts.put(JSONObject().put("file", file.path).put("ttc_index", font.ttcIndex)
                        .put("glyph_id", glyphs.getGlyphId(i)).put("x", glyphs.getGlyphX(i)).put("y", glyphs.getGlyphY(i)))
                }
                rows.put(JSONObject().put("text", text).put("utf16_length", text.length)
                    .put("has_glyph", paint.hasGlyph(text)).put("glyph_count", glyphs.glyphCount())
                    .put("advance", glyphs.advance.toDouble()).put("fallback_observed", fallback).put("fonts", fonts))
                canvas.drawText(text, 24f, (index + 1) * 72f, paint)
            }
            check(rows.getJSONObject(0).getBoolean("has_glyph") && !rows.getJSONObject(0).getBoolean("fallback_observed"))
            check(rows.getJSONObject(1).getBoolean("has_glyph") && rows.getJSONObject(1).getBoolean("fallback_observed"))
            check(rows.getJSONObject(2).getBoolean("has_glyph") && rows.getJSONObject(2).getBoolean("fallback_observed"))
            File(output, "samples.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
        for ((path, before) in hashes) check(sha(File(path)) == before)
        return JSONObject().put("primary_font", primary.path).put("font_sha256_before_and_after", JSONObject(hashes as Map<*, *>))
            .put("samples", rows).put("cache_directory", output.name).put("input_text_unchanged", true)
            .put("app_text_box_or_cross_platform_layout_verified", false).put("native_font_release_verified", false).toString()
    }
}
