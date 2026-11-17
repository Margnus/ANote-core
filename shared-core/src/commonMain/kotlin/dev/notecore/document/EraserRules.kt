package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.math.abs

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
            if (!PolylineGeometry.overlaps(eraserBounds, stroke.bounds())) continue
            // Page-space visual radius uses the largest singular value, never underestimating scale.
            val threshold = radius + stroke.brush.width / 2.0 * PolylineGeometry.maxScale(stroke.transform)
            val points = pagePoints(stroke)
            val needed = PolylineGeometry.segmentCount(points.size) * PolylineGeometry.segmentCount(path.size)
            // Refuse the whole plan instead of returning partially collected hits.
            if (tests + needed > maxPairTests) return EraserPlanResult(failure = EraserFailure.CAPACITY)
            tests += needed
            if (PolylineGeometry.polylinesWithin(path, points, threshold)) hits.add(stroke.id)
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

    private fun pagePoints(stroke: StrokeObject): List<PointD> {
        val result = ArrayList<PointD>(stroke.samples.size)
        for (i in 0 until stroke.samples.size)
            result.add(stroke.transform.map(PointD(stroke.samples.x(i), stroke.samples.y(i))))
        return result
    }
}
