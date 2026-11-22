package dev.notecore.canvas.prototype

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import dev.notecore.app.NativeBrushRenderer
import java.io.File

/** Dedicated synthetic library process. Does not open the preview App or its files. */
class SweptDiscRasterInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            val fixture = SweptDiscRasterFixture()
            val directory = File(targetContext.cacheDir, "swept-disc-raster").apply { mkdirs() }
            val rows = mutableListOf<String>()
            for (i in 0 until fixture.count) {
                val start = System.nanoTime(); val plan = fixture.plan(i); val built = System.nanoTime()
                val renderer = NativeBrushRenderer.prepare(plan); val prepared = System.nanoTime()
                File(directory, fixture.name(i) + ".json").writeText(fixture.describe(i, plan), Charsets.UTF_8)
                var drawNs = 0L
                var allocated = 0
                for (times in 1..2) {
                    val bitmap = Bitmap.createBitmap(fixture.width(i), fixture.height(i), Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        val before = System.nanoTime(); repeat(times) { renderer.draw(canvas) }; drawNs += System.nanoTime() - before
                        allocated = bitmap.allocationByteCount
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val bytes = ByteArray(pixels.size * 4)
                        for (p in pixels.indices) {
                            val color = pixels[p]; val alpha = Color.alpha(color)
                            // getPixels returns unpremultiplied colors. Reconstruct rounded RGBA for comparison.
                            bytes[p * 4] = ((Color.red(color) * alpha + 127) / 255).toByte()
                            bytes[p * 4 + 1] = ((Color.green(color) * alpha + 127) / 255).toByte()
                            bytes[p * 4 + 2] = ((Color.blue(color) * alpha + 127) / 255).toByte()
                            bytes[p * 4 + 3] = alpha.toByte()
                        }
                        File(directory, fixture.name(i) + "-$times.rgba").writeBytes(bytes)
                    } finally { bitmap.recycle() }
                }
                rows.add("{\"name\":\"${fixture.name(i)}\",\"build_plan_ns\":${built-start},\"prepare_native_ns\":${prepared-built},\"three_draws_ns\":$drawNs,\"bitmap_allocation_bytes\":$allocated}")
            }
            File(directory, "costs.json").writeText(rows.joinToString(prefix = "[", postfix = "]"), Charsets.UTF_8)
            output.putString("swept_raster_status", "passed"); output.putInt("swept_raster_cases", fixture.count)
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            output.putString("swept_raster_status", "failed"); output.putString("failure", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
