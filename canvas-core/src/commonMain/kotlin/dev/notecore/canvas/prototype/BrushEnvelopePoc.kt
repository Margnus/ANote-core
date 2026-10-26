package dev.notecore.canvas.prototype

import kotlin.math.sqrt

internal sealed interface BrushEnvelopeBuild {
    data class Ready(val envelope: BrushEnvelopePoc) : BrushEnvelopeBuild
    data class Rejected(val reason: String) : BrushEnvelopeBuild
}

/** M-2 draft: continuous union of linearly interpolated discs, round caps/joins.
 * This is an analytic coverage oracle, NOT a tessellator or an app rendering backend.
 * Draft rules must not be persisted as a promise about the existing brush algorithmVersion.
 */
internal class BrushEnvelopePoc private constructor(
    val ruleId: String,
    private val samples: StrokeSamples,
    private val radii: DoubleArray,
    private val inverse: Affine2D,
    val paintedBounds: BoundsD,
    val sourceAlpha: Double,
) {
    val sampleCount: Int get() = samples.size
    /** Alpha is applied once to the union mask, never once per segment/disc.
     * Different stroke objects still composite in canonical z order using source-over.
     */
    fun alphaAtScene(point: PointD): Double = if (containsScene(point)) sourceAlpha else 0.0

    fun containsScene(point: PointD): Boolean {
        if (point.x < paintedBounds.left || point.x > paintedBounds.right || point.y < paintedBounds.top || point.y > paintedBounds.bottom) return false
        val x = inverse.a * point.x + inverse.c * point.y + inverse.tx
        val y = inverse.b * point.x + inverse.d * point.y + inverse.ty
        if (!x.isFinite() || !y.isFinite()) return false
        if (samples.size == 1) return withinRadius(x - samples.x(0), y - samples.y(0), radii[0])
        for (i in 1 until samples.size) {
            val dx = samples.x(i) - samples.x(i - 1); val dy = samples.y(i) - samples.y(i - 1)
            val px = x - samples.x(i - 1); val py = y - samples.y(i - 1)
            val r = radii[i - 1]; val dr = radii[i] - r
            if (withinRadius(px, py, r) || withinRadius(x - samples.x(i), y - samples.y(i), radii[i])) return true
            // Minimize |p - (a+t*d)|^2 - (r+t*dr)^2 over closed t=[0,1].
            // Concave/linear cases attain their minimum at an endpoint (including containment).
            // A common positive scale preserves the minimizer while preventing tiny scene units
            // from turning every squared coefficient into zero. Source samples/radii stay unchanged.
            val scale = maxOf(maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)),
                maxOf(kotlin.math.abs(px), kotlin.math.abs(py)), maxOf(r, radii[i]))
            val sx = dx / scale; val sy = dy / scale; val spx = px / scale; val spy = py / scale
            val sr = r / scale; val sdr = dr / scale
            val a = squared(sx, sy) - sdr * sdr
            val b = -2 * (spx * sx + spy * sy + sr * sdr)
            if (a > 0) {
                val t = (-b / (2 * a)).coerceIn(0.0, 1.0)
                // Evaluate geometrically rather than subtracting large quadratic coefficients.
                val radius = r + t * dr
                if (withinRadius(px - t * dx, py - t * dy, radius)) return true
            }
        }
        return false
    }

    companion object {
        private fun squared(x: Double, y: Double) = x * x + y * y
        private fun withinRadius(x: Double, y: Double, radius: Double): Boolean {
            val scale = maxOf(kotlin.math.abs(x), kotlin.math.abs(y), radius)
            if (scale == 0.0) return true
            val r = radius / scale
            return squared(x / scale, y / scale) <= r * r
        }
        fun build(samples: StrokeSamples, brush: BrushSpec, transform: Affine2D = Affine2D(), maxSamples: Int = 200000): BrushEnvelopeBuild {
            require(maxSamples in 1..1_000_000)
            if (samples.size > maxSamples) return BrushEnvelopeBuild.Rejected("sample-budget")
            if (brush.algorithmVersion != 1) return BrushEnvelopeBuild.Rejected("unsupported-brush-version")
            val inverse = transform.inverseOrNull() ?: return BrushEnvelopeBuild.Rejected("noninvertible-transform")
            // Radius=width/2 for monoline/highlighter; pressure draft has 25% minimum width.
            fun radius(i: Int) =
                brush.width / 2 * if (brush.kind == BrushKind.PRESSURE) 0.25 + 0.75 * samples.pressure(i) else 1.0
            // A positive canonical width may round to a zero effective radius. Refuse explicitly
            // before allocating the derived array rather than claiming a zero-width Ready envelope.
            for (i in 0 until samples.size) if (radius(i) == 0.0) return BrushEnvelopeBuild.Rejected("radius-underflow")
            val radii = DoubleArray(samples.size, ::radius)
            // A transformed disc has x/y extents r*norm(row). The swept envelope's
            // extrema occur at sample endpoints because both center and radius vary linearly.
            fun norm(a: Double, b: Double): Double {
                val scale = maxOf(kotlin.math.abs(a), kotlin.math.abs(b))
                if (scale == 0.0) return 0.0
                return scale * sqrt(squared(a / scale, b / scale))
            }
            val sx = norm(transform.a, transform.c); val sy = norm(transform.b, transform.d)
            var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
            var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
            for (i in 0 until samples.size) {
                val x = transform.a * samples.x(i) + transform.c * samples.y(i) + transform.tx
                val y = transform.b * samples.x(i) + transform.d * samples.y(i) + transform.ty
                left = minOf(left, x - radii[i] * sx); right = maxOf(right, x + radii[i] * sx)
                top = minOf(top, y - radii[i] * sy); bottom = maxOf(bottom, y + radii[i] * sy)
            }
            if (!listOf(left, top, right, bottom).all { it.isFinite() }) return BrushEnvelopeBuild.Rejected("painted-bounds-overflow")
            val alpha = (brush.argb ushr 24) / 255.0 * if (brush.kind == BrushKind.HIGHLIGHTER) 0.25 else 1.0
            return BrushEnvelopeBuild.Ready(BrushEnvelopePoc("swept-discs-draft-1", samples, radii, inverse, BoundsD(left, top, right, bottom), alpha))
        }
    }
}
