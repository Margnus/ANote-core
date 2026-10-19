package dev.notecore.canvas.prototype

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class BrushFillRule { NON_ZERO }
enum class BrushBlendMode { SOURCE_OVER }
enum class BrushGeometryRejection { UNSUPPORTED_ALGORITHM, SAMPLE_CAPACITY, VERTEX_CAPACITY, BYTE_CAPACITY, NUMERIC_RANGE }

data class BrushGeometryBudget(
    val maxSamples: Int = 20_000,
    val maxVertices: Int = 400_000,
    val logicalBytes: Long = 8L * 1024 * 1024,
) {
    init { require(maxSamples in 1..1_000_000 && maxVertices in 3..1_000_000 && logicalBytes in 0..64L * 1024 * 1024) }
}

sealed interface BrushGeometryResult {
    data class Ready(val geometry: BrushGeometry) : BrushGeometryResult
    data class Rejected(val reason: BrushGeometryRejection) : BrushGeometryResult
}

/** Numeric contours in sample-local space. Fill ALL contours once, never each contour separately. */
class BrushGeometry internal constructor(
    val origin: PointD, val brush: BrushSpec, val sourcePointCount: Int, val argb: Int,
    private val positions: DoubleArray, private val offsets: IntArray,
    val bounds: BoundsD, val logicalByteCharge: Long,
) {
    val fillRule: BrushFillRule get() = BrushFillRule.NON_ZERO
    val blendMode: BrushBlendMode get() = BrushBlendMode.SOURCE_OVER
    val vertexCount: Int get() = positions.size / 2
    val contourCount: Int get() = offsets.size - 1
    fun x(vertex: Int): Double { require(vertex in 0 until vertexCount); return positions[vertex * 2] }
    fun y(vertex: Int): Double { require(vertex in 0 until vertexCount); return positions[vertex * 2 + 1] }
    fun contourStart(contour: Int): Int { require(contour in 0 until contourCount); return offsets[contour] }
    fun contourEnd(contour: Int): Int { require(contour in 0 until contourCount); return offsets[contour + 1] }
}

/** Versioned reference geometry only. No platform paths, smoothing, persistence or publication. */
object BrushGeometryBuilder {
    const val ALGORITHM_VERSION = 2
    private const val DISC_VERTICES = 16
    private val directions = doubleArrayOf(
        1.0, 0.0, 0.9238795325112867, 0.3826834323650898,
        0.7071067811865476, 0.7071067811865476, 0.3826834323650898, 0.9238795325112867,
        0.0, 1.0, -0.3826834323650898, 0.9238795325112867,
        -0.7071067811865476, 0.7071067811865476, -0.9238795325112867, 0.3826834323650898,
        -1.0, 0.0, -0.9238795325112867, -0.3826834323650898,
        -0.7071067811865476, -0.7071067811865476, -0.3826834323650898, -0.9238795325112867,
        0.0, -1.0, 0.3826834323650898, -0.9238795325112867,
        0.7071067811865476, -0.7071067811865476, 0.9238795325112867, -0.3826834323650898,
    )

    fun build(samples: StrokeSamples, brush: BrushSpec, budget: BrushGeometryBudget = BrushGeometryBudget()): BrushGeometryResult {
        fun reject(reason: BrushGeometryRejection) = BrushGeometryResult.Rejected(reason)
        if (brush.algorithmVersion != ALGORITHM_VERSION) return reject(BrushGeometryRejection.UNSUPPORTED_ALGORITHM)
        if (samples.size > budget.maxSamples) return reject(BrushGeometryRejection.SAMPLE_CAPACITY)
        var segments = 0
        for (i in 1 until samples.size) if (samples.x(i) != samples.x(i - 1) || samples.y(i) != samples.y(i - 1)) segments++
        val vertices = samples.size.toLong() * DISC_VERTICES + segments.toLong() * 4
        if (vertices > budget.maxVertices) return reject(BrushGeometryRejection.VERTEX_CAPACITY)
        val contours = samples.size + segments
        val charge = 256L + vertices * 16 + (contours.toLong() + 1) * 4
        if (charge > budget.logicalBytes) return reject(BrushGeometryRejection.BYTE_CAPACITY)

        // All capacity checks precede output allocation. Arrays never escape until complete.
        val positions = DoubleArray(vertices.toInt() * 2)
        val offsets = IntArray(contours + 1)
        val origin = PointD(samples.x(0), samples.y(0))
        var vertex = 0; var contour = 0
        var left = Double.POSITIVE_INFINITY; var top = left
        var right = Double.NEGATIVE_INFINITY; var bottom = right
        fun point(x: Double, y: Double) {
            positions[vertex * 2] = x; positions[vertex * 2 + 1] = y; vertex++
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        fun finishContour(): Boolean {
            val start = offsets[contour]
            // Translate before the shoelace sum to avoid cancellation at large scene origins.
            val x0 = positions[start * 2]; val y0 = positions[start * 2 + 1]
            var area = 0.0
            for (i in start until vertex) {
                val next = if (i + 1 == vertex) start else i + 1
                val x = positions[i * 2]; val y = positions[i * 2 + 1]
                val nx = positions[next * 2]; val ny = positions[next * 2 + 1]
                if (!x.isFinite() || !y.isFinite() || x == nx && y == ny) return false
                area += (x - x0) * (ny - y0) - (nx - x0) * (y - y0)
            }
            if (!area.isFinite() || area <= 0.0) return false
            offsets[++contour] = vertex
            return true
        }
        fun radius(i: Int): Double = brush.width / 2.0 *
            if (brush.kind == BrushKind.PRESSURE) 0.25 + 0.75 * samples.pressure(i).toDouble() else 1.0

        for (i in 0 until samples.size) {
            val x = samples.x(i) - origin.x; val y = samples.y(i) - origin.y; val r = radius(i)
            for (direction in 0 until DISC_VERTICES) point(x + r * directions[direction * 2], y + r * directions[direction * 2 + 1])
            if (!finishContour()) return reject(BrushGeometryRejection.NUMERIC_RANGE)
            if (i == 0) continue
            val dx = samples.x(i) - samples.x(i - 1); val dy = samples.y(i) - samples.y(i - 1)
            val scale = max(abs(dx), abs(dy))
            if (scale == 0.0) continue // Both real samples still have their own disc.
            val sx = dx / scale; val sy = dy / scale; val length = sqrt(sx * sx + sy * sy)
            val nx = -sy / length; val ny = sx / length
            val px = samples.x(i - 1) - origin.x; val py = samples.y(i - 1) - origin.y; val previous = radius(i - 1)
            point(px - nx * previous, py - ny * previous); point(x - nx * r, y - ny * r)
            point(x + nx * r, y + ny * r); point(px + nx * previous, py + ny * previous)
            if (!finishContour()) return reject(BrushGeometryRejection.NUMERIC_RANGE)
        }
        check(vertex == vertices.toInt() && contour == contours)
        val alpha = brush.argb ushr 24
        val argb = if (brush.kind == BrushKind.HIGHLIGHTER) (brush.argb and 0x00ffffff) or (minOf(alpha, 96) shl 24) else brush.argb
        return BrushGeometryResult.Ready(BrushGeometry(origin, brush, samples.size, argb, positions, offsets,
            BoundsD(left, top, right, bottom), charge))
    }
}
