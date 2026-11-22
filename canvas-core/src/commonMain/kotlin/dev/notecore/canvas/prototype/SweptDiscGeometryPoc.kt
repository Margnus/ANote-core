package dev.notecore.canvas.prototype

import kotlin.math.*

/** An isolated profile, never an interpretation of a persisted algorithm number. */
internal sealed interface SweptDiscBuild {
    data class Ready(val geometry: BrushGeometry, val ruleId: String, val discVertices: Int,
                     val targetDeviationBound: Double) : SweptDiscBuild
    data class Rejected(val reason: BrushGeometryRejection) : SweptDiscBuild
}

/**
 * Continuous linearly varying disc coverage: endpoint discs plus their external tangent bridge.
 * Inscribed discs have a bounded geometric deficit. Target CTM controls subdivision, not content.
 * Color explicitly follows preview half-alpha, independently of the analytic oracle's quarter-alpha.
 * No smoothing, changed samples, official version, native allocation or publication.
 */
internal object SweptDiscGeometryPoc {
    const val RULE_ID = "swept-discs-tangent-adaptive-draft-1"

    fun build(samples: StrokeSamples, brush: BrushSpec, sceneToTarget: Affine2D = Affine2D(),
              maxTargetDeviation: Double = 0.125, budget: BrushGeometryBudget = BrushGeometryBudget()): SweptDiscBuild {
        require(maxTargetDeviation.isFinite() && maxTargetDeviation > 0.0)
        fun reject(reason: BrushGeometryRejection) = SweptDiscBuild.Rejected(reason)
        if (brush.algorithmVersion != 1) return reject(BrushGeometryRejection.UNSUPPORTED_ALGORITHM)
        if (samples.size > budget.maxSamples) return reject(BrushGeometryRejection.SAMPLE_CAPACITY)
        if (sceneToTarget.inverseOrNull() == null) return reject(BrushGeometryRejection.NUMERIC_RANGE)
        val matrixScale = maxOf(abs(sceneToTarget.a), abs(sceneToTarget.b), abs(sceneToTarget.c), abs(sceneToTarget.d))
        val a = sceneToTarget.a / matrixScale; val b = sceneToTarget.b / matrixScale
        val c = sceneToTarget.c / matrixScale; val d = sceneToTarget.d / matrixScale
        // Frobenius norm is a conservative upper bound on the largest singular value.
        val norm = matrixScale * sqrt(a * a + b * b + c * c + d * d)
        if (!norm.isFinite() || norm <= 0.0) return reject(BrushGeometryRejection.NUMERIC_RANGE)
        fun radius(i: Int) = brush.width / 2.0 *
            if (brush.kind == BrushKind.PRESSURE) 0.25 + 0.75 * samples.pressure(i).toDouble() else 1.0
        var maxRadius = 0.0
        for (i in 0 until samples.size) {
            val r = radius(i)
            if (r == 0.0) return reject(BrushGeometryRejection.NUMERIC_RANGE)
            maxRadius = max(maxRadius, r)
        }
        var coordinateMagnitude = maxRadius
        for (i in 0 until samples.size) {
            coordinateMagnitude = max(coordinateMagnitude, max(abs(samples.x(i) - samples.x(0)), abs(samples.y(i) - samples.y(0))) + maxRadius)
        }
        // Conservative cancellation guard, separate from the analytic polygon deficit.
        val numericGuard = coordinateMagnitude * norm * (64.0 * 2.220446049250313e-16)
        if (!numericGuard.isFinite() || numericGuard >= maxTargetDeviation) return reject(BrushGeometryRejection.NUMERIC_RANGE)
        // 1-cos(theta) would lose precision for small theta. This equivalent form stays positive.
        fun deficit(vertices: Int) = maxRadius * 2.0 * sin(PI / (2.0 * vertices)).let { it * it } * norm
        var sides = 16
        while (deficit(sides) + numericGuard > maxTargetDeviation) {
            if (sides.toLong() * 2 * samples.size > budget.maxVertices) return reject(BrushGeometryRejection.VERTEX_CAPACITY)
            sides *= 2
        }
        if (deficit(sides) == 0.0) return reject(BrushGeometryRejection.NUMERIC_RANGE)
        fun needsBridge(i: Int): Boolean {
            val dx = samples.x(i) - samples.x(i - 1); val dy = samples.y(i) - samples.y(i - 1)
            val scale = max(abs(dx), abs(dy))
            if (scale == 0.0) return false
            val distance = scale * sqrt((dx / scale).let { it * it } + (dy / scale).let { it * it })
            return abs(radius(i) - radius(i - 1)) < distance
        }
        var bridges = 0
        for (i in 1 until samples.size) if (needsBridge(i)) bridges++
        val vertices = samples.size.toLong() * sides + bridges.toLong() * 4
        if (vertices > budget.maxVertices) return reject(BrushGeometryRejection.VERTEX_CAPACITY)
        val contours = samples.size + bridges
        val charge = 256L + vertices * 16 + (contours.toLong() + 1) * 4
        if (charge > budget.logicalBytes) return reject(BrushGeometryRejection.BYTE_CAPACITY)

        val positions = DoubleArray(vertices.toInt() * 2)
        val offsets = IntArray(contours + 1)
        val origin = PointD(samples.x(0), samples.y(0))
        var vertex = 0; var contour = 0
        var left = Double.POSITIVE_INFINITY; var top = left
        var right = Double.NEGATIVE_INFINITY; var bottom = right
        fun point(x: Double, y: Double) {
            positions[vertex * 2] = x; positions[vertex * 2 + 1] = y; vertex++
            left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
        }
        fun finish(): Boolean {
            val start = offsets[contour]
            val x0 = positions[start * 2]; val y0 = positions[start * 2 + 1]
            var area = 0.0
            for (i in start until vertex) {
                val j = if (i + 1 == vertex) start else i + 1
                val x = positions[i * 2]; val y = positions[i * 2 + 1]
                val nx = positions[j * 2]; val ny = positions[j * 2 + 1]
                if (!x.isFinite() || !y.isFinite() || x == nx && y == ny) return false
                area += (x - x0) * (ny - y0) - (nx - x0) * (y - y0)
            }
            if (!area.isFinite() || area <= 0.0) return false
            offsets[++contour] = vertex
            return true
        }
        for (i in 0 until samples.size) {
            val x = samples.x(i) - origin.x; val y = samples.y(i) - origin.y; val r = radius(i)
            for (j in 0 until sides) {
                val angle = 2 * PI * j / sides
                point(x + r * cos(angle), y + r * sin(angle))
            }
            if (!finish()) return reject(BrushGeometryRejection.NUMERIC_RANGE)
            if (i == 0 || !needsBridge(i)) continue
            val px = samples.x(i - 1) - origin.x; val py = samples.y(i - 1) - origin.y
            val pr = radius(i - 1)
            val dx = samples.x(i) - samples.x(i - 1); val dy = samples.y(i) - samples.y(i - 1)
            val scale = max(abs(dx), abs(dy))
            val sx = dx / scale; val sy = dy / scale; val length = sqrt(sx * sx + sy * sy)
            val ux = sx / length; val uy = sy / length
            val q = ((r - pr) / scale) / length
            val perpendicular = sqrt((1.0 - q) * (1.0 + q))
            // n dot direction = -dr/distance: each side is a common external tangent.
            val lowX = -q * ux + perpendicular * uy; val lowY = -q * uy - perpendicular * ux
            val highX = -q * ux - perpendicular * uy; val highY = -q * uy + perpendicular * ux
            point(px + pr * lowX, py + pr * lowY); point(x + r * lowX, y + r * lowY)
            point(x + r * highX, y + r * highY); point(px + pr * highX, py + pr * highY)
            if (!finish()) return reject(BrushGeometryRejection.NUMERIC_RANGE)
        }
        check(vertex == vertices.toInt() && contour == contours)
        return SweptDiscBuild.Ready(BrushGeometry(origin, brush, samples.size, BrushDisplayRules.displayArgb(brush),
            positions, offsets, BoundsD(left, top, right, bottom), charge), RULE_ID, sides, deficit(sides) + numericGuard)
    }
}
