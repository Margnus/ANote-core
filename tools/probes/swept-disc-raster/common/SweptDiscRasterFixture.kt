package dev.notecore.canvas.prototype

/** Added only to an isolated test build by verify_swept_disc_raster.py. Never a production API. */
class SweptDiscRasterFixture {
    val count: Int get() = 6
    fun name(index: Int): String = listOf("retrace-marker", "pressure-taper", "reflected-marker", "wide-dot", "large-origin-dot", "stationary-pressure")[index]
    fun width(index: Int): Int = if (index == 3) 640 else 256
    fun height(index: Int): Int = if (index == 3) 640 else 192
    fun plan(index: Int): BrushPathPlan {
        val xs = when (index) {
            0, 2 -> doubleArrayOf(0.0, 20.0, 0.0)
            1 -> doubleArrayOf(0.0, 10.0)
            3 -> doubleArrayOf(0.0)
            4 -> doubleArrayOf(1e9)
            5 -> doubleArrayOf(0.0, 0.0, 8.0)
            else -> error("Unknown synthetic raster case")
        }
        val ys = if (index == 4) doubleArrayOf(-1e9) else DoubleArray(xs.size)
        val pressures = when (index) { 1 -> floatArrayOf(0f, 1f); 5 -> floatArrayOf(0f, 1f, .2f); else -> FloatArray(xs.size) { 1f } }
        val kind = when (index) { 0, 2 -> BrushKind.HIGHLIGHTER; 1, 5 -> BrushKind.PRESSURE; else -> BrushKind.MONOLINE }
        val color = if (kind == BrushKind.HIGHLIGHTER) 0xff336699.toInt() else 0xff3568be.toInt()
        val transform = when (index) {
            0 -> Affine2D(a = 4.0, d = 4.0, tx = 48.0, ty = 64.0)
            1 -> Affine2D(a = 8.0, d = 8.0, tx = 48.0, ty = 64.0)
            2 -> Affine2D(a = -4.0, b = .5, c = 1.0, d = 3.0, tx = 180.0, ty = 80.0)
            3 -> Affine2D(a = 4.0, d = 4.0, tx = 320.0, ty = 320.0)
            4 -> Affine2D(a = 4.0, d = 4.0, tx = 128 - 4e9, ty = 96 + 4e9)
            else -> Affine2D(a = 8.0, d = 8.0, tx = 64.0, ty = 80.0)
        }
        val samples = StrokeSamples.copyOf(xs, ys, pressures, LongArray(xs.size) { it.toLong() })
        val result = SweptDiscGeometryPoc.build(samples, BrushSpec(kind, 1, if (index == 3) 128.0 else 8.0, color), transform)
        val geometry = checkNotNull(result as? SweptDiscBuild.Ready).geometry
        return checkNotNull(BrushPathPlanner.prepare(geometry, transform) as? BrushPathPlanResult.Ready).plan
    }
    fun describe(index: Int, plan: BrushPathPlan): String = buildString {
        append("{\"name\":\""); append(name(index)); append("\",\"width\":"); append(width(index))
        append(",\"height\":"); append(height(index)); append(",\"argb\":"); append(plan.argb)
        append(",\"vertices\":[")
        for (i in 0 until plan.vertexCount) { if (i > 0) append(','); append('['); append(plan.x(i)); append(','); append(plan.y(i)); append(']') }
        append("],\"offsets\":[")
        for (i in 0 until plan.contourCount) { if (i > 0) append(','); append(plan.contourStart(i)) }
        append(','); append(plan.vertexCount); append("]}")
    }
}
