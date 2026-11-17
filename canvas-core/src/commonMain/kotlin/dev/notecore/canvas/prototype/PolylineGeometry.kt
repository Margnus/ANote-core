package dev.notecore.canvas.prototype

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shared deterministic polyline/polygon predicates for DEV-042 editing rule candidates.
 * Pure functions on immutable inputs; no scene state, budgets or platform geometry version.
 * Public within the unfrozen prototype package, same as PointD/Affine2D; not a stable SDK surface.
 */
object PolylineGeometry {
    fun segmentCount(points: Int): Int = if (points <= 1) 1 else points - 1

    fun overlaps(a: BoundsD, b: BoundsD): Boolean =
        a.left <= b.right && a.right >= b.left && a.top <= b.bottom && a.bottom >= b.top

    /** Largest singular value of the linear part: exact for rotation/uniform scale, conservative otherwise. */
    fun maxScale(t: Affine2D): Double {
        val frob2 = t.a * t.a + t.b * t.b + t.c * t.c + t.d * t.d
        val det = t.a * t.d - t.b * t.c
        val disc = frob2 * frob2 - 4.0 * det * det
        return sqrt((frob2 + sqrt(max(0.0, disc))) / 2.0)
    }

    fun distanceSquared(a: PointD, b: PointD): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    fun pointSegmentDistanceSquared(p: PointD, a: PointD, b: PointD): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return distanceSquared(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return distanceSquared(p, PointD(a.x + t * dx, a.y + t * dy))
    }

    fun segmentDistanceSquared(p1: PointD, p2: PointD, q1: PointD, q2: PointD): Double {
        if (p1 == p2 && q1 == q2) return distanceSquared(p1, q1)
        if (p1 == p2) return pointSegmentDistanceSquared(p1, q1, q2)
        if (q1 == q2) return pointSegmentDistanceSquared(q1, p1, p2)
        if (segmentsIntersect(p1, p2, q1, q2)) return 0.0
        return minOf(
            pointSegmentDistanceSquared(p1, q1, q2), pointSegmentDistanceSquared(p2, q1, q2),
            pointSegmentDistanceSquared(q1, p1, p2), pointSegmentDistanceSquared(q2, p1, p2))
    }

    fun polylinesWithin(a: List<PointD>, b: List<PointD>, threshold: Double): Boolean {
        val limit = threshold * threshold
        for (i in 0 until segmentCount(a.size)) {
            val a1 = a[i]; val a2 = a[min(i + 1, a.size - 1)]
            for (j in 0 until segmentCount(b.size)) {
                val b1 = b[j]; val b2 = b[min(j + 1, b.size - 1)]
                if (segmentDistanceSquared(a1, a2, b1, b2) <= limit) return true
            }
        }
        return false
    }

    /** Even-odd ray cast. Points exactly on an edge are covered by segment distance checks instead. */
    fun pointInPolygon(p: PointD, polygon: List<PointD>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val xCross = a.x + (p.y - a.y) / (b.y - a.y) * (b.x - a.x)
                if (p.x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun segmentsIntersect(p1: PointD, p2: PointD, q1: PointD, q2: PointD): Boolean {
        val d1 = cross(q1, q2, p1); val d2 = cross(q1, q2, p2)
        val d3 = cross(p1, p2, q1); val d4 = cross(p1, p2, q2)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
        if (d1 == 0.0 && onSegment(q1, q2, p1)) return true
        if (d2 == 0.0 && onSegment(q1, q2, p2)) return true
        if (d3 == 0.0 && onSegment(p1, p2, q1)) return true
        if (d4 == 0.0 && onSegment(p1, p2, q2)) return true
        return false
    }

    private fun cross(a: PointD, b: PointD, c: PointD): Double =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun onSegment(a: PointD, b: PointD, p: PointD): Boolean =
        min(a.x, b.x) <= p.x && p.x <= max(a.x, b.x) && min(a.y, b.y) <= p.y && p.y <= max(a.y, b.y)
}
