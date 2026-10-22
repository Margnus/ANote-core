package dev.notecore.app

import android.content.res.AssetManager
import android.graphics.*
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure offscreen Android rasterizer consumer of actual common contour output. */
internal object BrushPathOffscreen {
    fun verify(assets: AssetManager): Int {
        val fixture = JSONObject(assets.open("brush-paths.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        check(fixture.getString("fill_rule") == "nonzero-once")
        val cases = fixture.getJSONArray("cases"); check(cases.length() == 6)
        val width = fixture.getInt("width"); val height = fixture.getInt("height")
        var testedPixels = 0
        for (caseIndex in 0 until cases.length()) {
            val c = cases.getJSONObject(caseIndex)
            val path = Path().apply { fillType = Path.FillType.WINDING }
            val contours = c.getJSONArray("contours")
            for (i in 0 until contours.length()) {
                val points = contours.getJSONArray(i)
                for (j in 0 until points.length()) {
                    val p = points.getJSONArray(j); val x = p.getDouble(0).toFloat(); val y = p.getDouble(1).toFloat()
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            for (antialias in listOf(false, true)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val alpha = (c.getDouble("alpha") * 255).roundToInt()
                    val paint = Paint().apply { isAntiAlias = antialias; color = Color.WHITE; this.alpha = alpha; style = Paint.Style.FILL }
                    Canvas(bitmap).drawPath(path, paint) // ONE fill; never draw each contour separately.
                    val mask = c.getJSONArray("oracle_mask")
                    for (y in 0 until height) for (x in 0 until width) {
                        val expected = mask.getString(y)[x]
                        if (expected == '?') continue
                        val actual = Color.alpha(bitmap.getPixel(x, y))
                        val wanted = if (expected == '#') alpha else 0
                        check(abs(actual - wanted) <= 1) { "${c.getString("name")} AA=$antialias x=$x y=$y expected=$wanted actual=$actual" }
                        testedPixels++
                    }
                } finally { bitmap.recycle() }
            }
        }
        check(testedPixels > 20000)
        return testedPixels
    }
}
