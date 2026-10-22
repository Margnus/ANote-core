package dev.notecore.canvas.prototype

import kotlin.math.*

internal sealed interface BrushPathBuild {
    data class Ready(val path: BrushPathPoc) : BrushPathBuild
    data class Rejected(val reason: String) : BrushPathBuild
    data object Cancelled : BrushPathBuild
}

/** M-2B derived geometry: equally oriented convex contours, filled ONCE with nonzero winding.
 * Inscribed round contours + exact external tangent quads approximate the swept-disc oracle.
 * No canonical samples are dropped; all admission/cancellation failures leave them with caller.
 */
internal class BrushPathPoc private constructor(
    private val coordinates: DoubleArray, private val offsets: IntArray,
    val sourceAlpha: Double, val maxSceneDeviation: Double,
) {
    val contourCount: Int get() = offsets.size - 1
    val vertexCount: Int get() = coordinates.size / 2
    val logicalBytes: Long get() = coordinates.size * 8L + offsets.size * 4L
    fun contourStart(index: Int) = offsets[index]
    fun contourEnd(index: Int) = offsets[index + 1]
    fun x(index: Int) = coordinates[index * 2]
    fun y(index: Int) = coordinates[index * 2 + 1]

    companion object {
        fun build(
            samples: StrokeSamples, brush: BrushSpec, transform: Affine2D = Affine2D(),
            sceneTolerance: Double = 0.05, maxVertices: Int = 500000, maxBytes: Long = 16L * 1024 * 1024,
            cancelled: () -> Boolean = { false },
        ): BrushPathBuild {
            require(sceneTolerance.isFinite() && sceneTolerance in 0.0001..4.0)
            require(maxVertices in 1..2000000 && maxBytes > 0)
            if (cancelled()) return BrushPathBuild.Cancelled
            if (samples.size > 200000) return BrushPathBuild.Rejected("sample-budget")
            if (brush.algorithmVersion != 1) return BrushPathBuild.Rejected("unsupported-brush-version")
            if (transform.inverseOrNull() == null) return BrushPathBuild.Rejected("noninvertible-transform")
            val scale = maxOf(abs(transform.a), abs(transform.b), abs(transform.c), abs(transform.d))
            val norm = scale * sqrt((transform.a / scale).pow(2) + (transform.b / scale).pow(2) +
                (transform.c / scale).pow(2) + (transform.d / scale).pow(2))
            if (!norm.isFinite()) return BrushPathBuild.Rejected("transform-overflow")
            fun radius(i: Int) = brush.width / 2 * if (brush.kind == BrushKind.PRESSURE) 0.25 + 0.75 * samples.pressure(i) else 1.0
            fun sides(i: Int): Int {
                val radius = radius(i) * norm
                if (!radius.isFinite()) return 4097
                if (radius == 0.0) return 8
                val angle = acos(1 - minOf(1.0, sceneTolerance / radius))
                if (angle == 0.0) return 4097
                return maxOf(8, ceil(PI / angle).toInt())
            }
            fun bridge(i: Int): Boolean {
                val dx = samples.x(i) - samples.x(i - 1); val dy = samples.y(i) - samples.y(i - 1)
                return sqrt(dx * dx + dy * dy) > abs(radius(i) - radius(i - 1))
            }
            // Preflight output counts, tolerance and native-safe coordinates before arrays.
            var vertices = 0L; var contours = 0
            for (i in 0 until samples.size) {
                if (i % 256 == 0 && cancelled()) return BrushPathBuild.Cancelled
                val n = sides(i)
                if (n > 4096) return BrushPathBuild.Rejected("tolerance-budget")
                val x = transform.a * samples.x(i) + transform.c * samples.y(i) + transform.tx
                val y = transform.b * samples.x(i) + transform.d * samples.y(i) + transform.ty
                if (!x.isFinite() || !y.isFinite() || abs(x) + radius(i) * norm > 1e12 || abs(y) + radius(i) * norm > 1e12)
                    return BrushPathBuild.Rejected("coordinate-range")
                vertices += n; contours++
                if (i > 0 && bridge(i)) { vertices += 4; contours++ }
                if (vertices > maxVertices || vertices * 16 + (contours + 1L) * 4 > maxBytes) return BrushPathBuild.Rejected("output-budget")
            }
            if (cancelled()) return BrushPathBuild.Cancelled
            val xy = DoubleArray(vertices.toInt() * 2); val offsets = IntArray(contours + 1)
            var cursor = 0; var contour = 0
            fun point(x: Double, y: Double) {
                xy[cursor * 2] = transform.a * x + transform.c * y + transform.tx
                xy[cursor * 2 + 1] = transform.b * x + transform.d * y + transform.ty
                cursor++
            }
            for (i in 0 until samples.size) {
                if (i % 256 == 0 && cancelled()) return BrushPathBuild.Cancelled
                offsets[contour++] = cursor
                val x = samples.x(i); val y = samples.y(i); val r = radius(i); val n = sides(i)
                repeat(n) { j -> val angle = 2 * PI * j / n; point(x + r * cos(angle), y + r * sin(angle)) }
                if (i > 0 && bridge(i)) {
                    offsets[contour++] = cursor
                    val ax = samples.x(i - 1); val ay = samples.y(i - 1); val ar = radius(i - 1)
                    val dx = x - ax; val dy = y - ay; val length = sqrt(dx * dx + dy * dy)
                    val ux = dx / length; val uy = dy / length; val slope = (ar - r) / length
                    val side = sqrt(maxOf(0.0, 1 - slope * slope))
                    val nx = slope * ux - side * uy; val ny = slope * uy + side * ux
                    val mx = slope * ux + side * uy; val my = slope * uy - side * ux
                    point(ax + nx * ar, ay + ny * ar); point(ax + mx * ar, ay + my * ar)
                    point(x + mx * r, y + my * r); point(x + nx * r, y + ny * r)
                }
            }
            if (cancelled()) return BrushPathBuild.Cancelled
            offsets[contour] = cursor
            check(cursor == vertices.toInt() && contour == contours)
            val alpha = (brush.argb ushr 24) / 255.0 * if (brush.kind == BrushKind.HIGHLIGHTER) 0.25 else 1.0
            return BrushPathBuild.Ready(BrushPathPoc(xy, offsets, alpha, sceneTolerance))
        }
    }
}
