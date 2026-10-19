package dev.notecore.canvas.prototype

import kotlin.math.abs

enum class BrushPathPrecision { FLOAT32, FLOAT64 }
enum class BrushPathRejection { VERTEX_CAPACITY, INVALID_TRANSFORM, NON_FINITE, PRECISION_LOSS, DEGENERATE_CONTOUR }
sealed interface BrushPathPlanResult {
    data class Ready(val plan: BrushPathPlan) : BrushPathPlanResult
    data class Rejected(val reason: BrushPathRejection) : BrushPathPlanResult
}

/** Immutable preflighted target-pixel path; retains the input geometry, not a second coordinate array. */
class BrushPathPlan internal constructor(
    private val geometry: BrushGeometry, private val transform: Affine2D,
    private val originX: Double, private val originY: Double, val precision: BrushPathPrecision,
) {
    val vertexCount: Int get() = geometry.vertexCount
    val contourCount: Int get() = geometry.contourCount
    val argb: Int get() = geometry.argb
    val fillRule: BrushFillRule get() = geometry.fillRule
    val blendMode: BrushBlendMode get() = geometry.blendMode
    fun contourStart(contour: Int): Int = geometry.contourStart(contour)
    fun contourEnd(contour: Int): Int = geometry.contourEnd(contour)
    internal fun rawX(vertex: Int): Double = transform.a * geometry.x(vertex) + transform.c * geometry.y(vertex) + originX
    internal fun rawY(vertex: Int): Double = transform.b * geometry.x(vertex) + transform.d * geometry.y(vertex) + originY
    private fun quantize(value: Double): Double = if (precision == BrushPathPrecision.FLOAT32) value.toFloat().toDouble() else value
    fun x(vertex: Int): Double = quantize(rawX(vertex))
    fun y(vertex: Int): Double = quantize(rawY(vertex))
}

object BrushPathPlanner {
    const val FLOAT32_MAX_ERROR = 1.0 / 1024.0

    /** Compose object+viewport into sceneToTarget before calling. Native paths allocate only after Ready. */
    fun prepare(geometry: BrushGeometry, sceneToTarget: Affine2D = Affine2D(),
                precision: BrushPathPrecision = BrushPathPrecision.FLOAT32, maxVertices: Int = 400_000): BrushPathPlanResult {
        require(maxVertices in 3..400_000)
        fun reject(reason: BrushPathRejection) = BrushPathPlanResult.Rejected(reason)
        if (geometry.vertexCount > maxVertices) return reject(BrushPathRejection.VERTEX_CAPACITY)
        if (sceneToTarget.inverseOrNull() == null) return reject(BrushPathRejection.INVALID_TRANSFORM)
        // Fold the rebased origin and target translation in Double BEFORE any Float conversion.
        val ox = sceneToTarget.a * geometry.origin.x + sceneToTarget.c * geometry.origin.y + sceneToTarget.tx
        val oy = sceneToTarget.b * geometry.origin.x + sceneToTarget.d * geometry.origin.y + sceneToTarget.ty
        if (!ox.isFinite() || !oy.isFinite()) return reject(BrushPathRejection.NON_FINITE)
        val plan = BrushPathPlan(geometry, sceneToTarget, ox, oy, precision)
        for (vertex in 0 until plan.vertexCount) {
            val x = plan.x(vertex); val y = plan.y(vertex)
            if (!x.isFinite() || !y.isFinite()) return reject(BrushPathRejection.NON_FINITE)
            if (precision == BrushPathPrecision.FLOAT32 &&
                (abs(x - plan.rawX(vertex)) > FLOAT32_MAX_ERROR || abs(y - plan.rawY(vertex)) > FLOAT32_MAX_ERROR))
                return reject(BrushPathRejection.PRECISION_LOSS)
        }
        var orientation = 0
        for (contour in 0 until plan.contourCount) {
            val start = plan.contourStart(contour); val end = plan.contourEnd(contour)
            val x0 = plan.x(start); val y0 = plan.y(start)
            var area = 0.0
            for (i in start until end) {
                val next = if (i + 1 == end) start else i + 1
                val x = plan.x(i); val y = plan.y(i); val nx = plan.x(next); val ny = plan.y(next)
                if (x == nx && y == ny) return reject(BrushPathRejection.DEGENERATE_CONTOUR)
                area += (x - x0) * (ny - y0) - (nx - x0) * (y - y0)
            }
            if (!area.isFinite()) return reject(BrushPathRejection.NON_FINITE)
            if (area == 0.0) return reject(BrushPathRejection.DEGENERATE_CONTOUR)
            val sign = if (area > 0.0) 1 else -1
            if (orientation != 0 && orientation != sign) return reject(BrushPathRejection.DEGENERATE_CONTOUR)
            orientation = sign
        }
        return BrushPathPlanResult.Ready(plan)
    }
}
