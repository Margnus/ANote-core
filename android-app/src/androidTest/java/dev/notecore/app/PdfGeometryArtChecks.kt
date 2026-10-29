package dev.notecore.app

import android.content.Context
import android.content.res.AssetManager
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.notecore.canvas.prototype.Affine2D
import dev.notecore.canvas.prototype.PointD
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Port of the existing host probe to actual system PdfRenderer; isolated test data, no App PDF feature. */
internal object PdfGeometryArtChecks {
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private fun mapping(rotation: Int, userUnit: Double): Affine2D {
        // Test-only invocation of the same compiled internal rule, without exporting a new facade.
        val cropClass = Class.forName("dev.notecore.document.PdfCropBox")
        val crop = cropClass.getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType).newInstance(40.0, 50.0, 340.0, 230.0)
        val rules = Class.forName("dev.notecore.document.PdfPageGeometryRules")
        val result = rules.getMethod("resolve", cropClass, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .invoke(rules.getField("INSTANCE").get(null), crop, rotation, userUnit)
        val geometry = checkNotNull(result.javaClass.getMethod("getGeometry").invoke(result))
        return geometry.javaClass.getMethod("getPdfToPage").invoke(geometry) as Affine2D
    }
    fun verify(context: Context, assets: AssetManager, fixture: String = "crop-rotations.pdf", userUnit: Double = 1.0): String {
        val directory = File(context.cacheDir, "pdf-geometry-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val source = File(directory, "source.pdf")
        assets.open("pdf/$fixture").use { input -> source.outputStream().use { input.copyTo(it) } }
        val recipe = JSONObject(assets.open("pdf/recipe.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        val before = sha(source)
        val anchors = listOf(PointD(56.0, 66.0), PointD(324.0, 66.0), PointD(56.0, 214.0), PointD(324.0, 214.0))
        val observations = JSONArray()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                check(renderer.pageCount == 4)
                repeat(4) { index -> renderer.openPage(index).use { page ->
                    val expected = recipe.getJSONArray("expected_pages").getJSONObject(index)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(directory, "background-$index.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        check(page.width == (expected.getInt("width") * userUnit).toInt() && page.height == (expected.getInt("height") * userUnit).toInt()) {
                            "System PDF $fixture page $index size ${page.width}x${page.height} disagrees with CropBox/UserUnit $userUnit recipe"
                        }
                        val transform = mapping(expected.getInt("rotation"), userUnit)
                        val pixels = JSONArray()
                        anchors.forEachIndexed { i, sourcePoint ->
                            val p = transform.map(sourcePoint)
                            val xy = expected.getJSONArray("marker_xy").getJSONArray(i)
                            check(kotlin.math.abs(xy.getDouble(0) * userUnit - p.x) < 1e-8 && kotlin.math.abs(xy.getDouble(1) * userUnit - p.y) < 1e-8)
                            val color = recipe.getJSONArray("marker_rgb").getJSONArray(i)
                            val actual = bitmap.getPixel(p.x.toInt(), p.y.toInt())
                            val rgb = listOf(Color.red(actual), Color.green(actual), Color.blue(actual))
                            check(rgb.indices.all { kotlin.math.abs(rgb[it] - color.getInt(it)) <= 2 }) {
                                "System PDF page $index marker $i at $p has $rgb"
                            }
                            pixels.put(JSONObject().put("x", p.x).put("y", p.y).put("rgb", JSONArray(rgb)))
                        }
                        val canvas = Canvas(bitmap)
                        val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
                        anchors.forEach { transform.map(it).let { p -> canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), 3f, paint) } }
                        anchors.forEach { transform.map(it).let { p -> check(bitmap.getPixel(p.x.toInt(), p.y.toInt()) == Color.BLACK) } }
                        File(directory, "overlay-$index.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        observations.put(JSONObject().put("page", index).put("rotation", expected.getInt("rotation"))
                            .put("width", page.width).put("height", page.height).put("anchors", pixels))
                    } finally { bitmap.recycle() }
                } }
            }
        }
        check(sha(source) == before)
        return JSONObject().put("pages", observations).put("source_sha256_before", before)
            .put("source_sha256_after", sha(source)).put("cache_directory", directory.name)
            .put("fixture", fixture).put("user_unit", userUnit).put("app_annotation_or_export_verified", false).toString()
    }
}
