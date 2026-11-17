package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal enum class EraserFailure {
    WRONG_DOCUMENT, STALE_ROOT, MISSING_PAGE, INVALID_PATH, INVALID_BUDGET, CAPACITY, NO_HIT,
}

/** Immutable proposed whole-stroke erasure, never an accepted command or a second scene authority. */
internal class EraserPlan(
    val documentId: SceneId, val expectedRootRevision: Long, val pageId: PageId, hits: List<ObjectId>,
) {
    private val ids = hits.toList()
    fun objectIds(): List<ObjectId> = ids.toList()
}
internal data class EraserPlanResult(val plan: EraserPlan? = null, val failure: EraserFailure? = null)

/**
 * DEV-042 E1 whole-stroke eraser candidate (INK-05 subset). One plan carries every hit in canonical
 * scene order so the caller can delete them as one command with one history entry. Monoline,
 * pressure and highlighter strokes share the same hit path. This is a business-rule candidate,
 * not an App integration, a frozen geometry version, or a production acceptance.
 */
internal object EraserRules {
    const val MAX_PATH_POINTS = 4_096

    fun plan(root: DocumentRoot, documentId: SceneId, expectedRevision: Long, pageId: PageId,
             path: List<PointD>, radius: Double, maxPairTests: Int): EraserPlanResult {
        if (root.id != documentId) return EraserPlanResult(failure = EraserFailure.WRONG_DOCUMENT)
        if (expectedRevision < 0 || root.revision != expectedRevision) return EraserPlanResult(failure = EraserFailure.STALE_ROOT)
        val page = root.findPage(pageId) ?: return EraserPlanResult(failure = EraserFailure.MISSING_PAGE)
        if (path.isEmpty() || path.size > MAX_PATH_POINTS ||
            path.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > 1e9 || abs(it.y) > 1e9 })
            return EraserPlanResult(failure = EraserFailure.INVALID_PATH)
        if (!radius.isFinite() || radius <= 0.0 || radius > 256.0) return EraserPlanResult(failure = EraserFailure.INVALID_PATH)
        if (maxPairTests !in 1..4_000_000) return EraserPlanResult(failure = EraserFailure.INVALID_BUDGET)

        var eraserBounds = BoundsD(path[0].x, path[0].y, path[0].x, path[0].y)
        for (i in 1 until path.size) eraserBounds = eraserBounds.union(BoundsD(path[i].x, path[i].y, path[i].x, path[i].y))
        eraserBounds = BoundsD(eraserBounds.left - radius, eraserBounds.top - radius,
            eraserBounds.right + radius, eraserBounds.bottom + radius)

        var tests = 0
        val hits = mutableListOf<ObjectId>()
        for (stroke in root.pageObjects(pageId)) {
            if (!overlaps(eraserBounds, stroke.bounds())) continue
            // Page-space visual radius uses the largest singular value, never underestimating scale.
            val threshold = radius + stroke.brush.width / 2.0 * maxScale(stroke.transform)
            val points = pagePoints(stroke)
            val needed = segmentCount(points.size) * segmentCount(path.size)
            // Refuse the whole plan instead of returning partially collected hits.
            if (tests + needed > maxPairTests) return EraserPlanResult(failure = EraserFailure.CAPACITY)
            tests += needed
            if (polylinesWithin(path, points, threshold)) hits.add(stroke.id)
        }
        if (hits.isEmpty()) return EraserPlanResult(failure = EraserFailure.NO_HIT)
        return EraserPlanResult(EraserPlan(root.id, root.revision, page.id, hits))
    }

    /** Same-document revision must still belong to the original single authority; no fork adoption. */
    fun revalidate(root: DocumentRoot, plan: EraserPlan): EraserFailure? = when {
        root.id != plan.documentId -> EraserFailure.WRONG_DOCUMENT
        root.revision != plan.expectedRootRevision -> EraserFailure.STALE_ROOT
        root.findPage(plan.pageId) == null -> EraserFailure.MISSING_PAGE
        else -> null
    }

    private fun segmentCount(points: Int): Int = if (points <= 1) 1 else points - 1

    private fun overlaps(a: BoundsD, b: BoundsD): Boolean =
        a.left <= b.right && a.right >= b.left && a.top <= b.bottom && a.bottom >= b.top

    private fun pagePoints(stroke: StrokeObject): List<PointD> {
        val result = ArrayList<PointD>(stroke.samples.size)
        for (i in 0 until stroke.samples.size)
            result.add(stroke.transform.map(PointD(stroke.samples.x(i), stroke.samples.y(i))))
        return result
    }

    /** Largest singular value of the linear part: exact for rotation/uniform scale, conservative otherwise. */
    private fun maxScale(t: Affine2D): Double {
        val frob2 = t.a * t.a + t.b * t.b + t.c * t.c + t.d * t.d
        val det = t.a * t.d - t.b * t.c
        val disc = frob2 * frob2 - 4.0 * det * det
        return sqrt((frob2 + sqrt(max(0.0, disc))) / 2.0)
    }

    private fun polylinesWithin(a: List<PointD>, b: List<PointD>, threshold: Double): Boolean {
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

    private fun segmentDistanceSquared(p1: PointD, p2: PointD, q1: PointD, q2: PointD): Double {
        if (p1 == p2 && q1 == q2) return distanceSquared(p1, q1)
        if (p1 == p2) return pointSegmentDistanceSquared(p1, q1, q2)
        if (q1 == q2) return pointSegmentDistanceSquared(q1, p1, p2)
        if (segmentsIntersect(p1, p2, q1, q2)) return 0.0
        return minOf(
            pointSegmentDistanceSquared(p1, q1, q2), pointSegmentDistanceSquared(p2, q1, q2),
            pointSegmentDistanceSquared(q1, p1, p2), pointSegmentDistanceSquared(q2, p1, p2))
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

    private fun pointSegmentDistanceSquared(p: PointD, a: PointD, b: PointD): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return distanceSquared(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return distanceSquared(p, PointD(a.x + t * dx, a.y + t * dy))
    }

    private fun distanceSquared(a: PointD, b: PointD): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return dx * dx + dy * dy
    }
}
