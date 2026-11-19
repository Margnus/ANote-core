package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class LassoFailure {
    WRONG_DOCUMENT, STALE_ROOT, MISSING_PAGE, INVALID_PATH, INVALID_BUDGET, CAPACITY,
    NO_HIT, EMPTY_SELECTION, INVALID_SELECTION, MISSING_OBJECT, WRONG_PAGE, NO_CHANGE, INVALID_TRANSFORM,
}

/** Immutable proposed lasso selection, never an accepted command or a second scene authority. */
internal class LassoSelectionPlan(
    val documentId: SceneId, val expectedRootRevision: Long, val pageId: PageId, hits: List<ObjectId>,
) {
    private val ids = hits.toList()
    fun objectIds(): List<ObjectId> = ids.toList()
}
internal data class LassoSelectionResult(val plan: LassoSelectionPlan? = null, val failure: LassoFailure? = null)

/** Immutable per-object transform proposal; one plan is one command and therefore one undo entry. */
internal class SelectionTransformPlan(
    val documentId: SceneId, val expectedRootRevision: Long, val pageId: PageId,
    changes: List<Pair<ObjectId, Affine2D>>,
) {
    private val items = changes.toList()
    fun changes(): List<Pair<ObjectId, Affine2D>> = items.toList()
}
internal data class SelectionTransformResult(val plan: SelectionTransformPlan? = null, val failure: LassoFailure? = null)

/**
 * DEV-042 E2 lasso candidate (INK-07 stroke-only subset; text/image objects belong to the mixed
 * content line). Selection: any sampled point inside the closed polygon, or any stroke segment
 * within its page-space visual radius of a polygon edge. The latter also covers a small polygon
 * lying entirely on a thick stroke. Transform: a single composed translate/anchor-uniform-scale
 * matrix per selected object, never accumulated per gesture frame. Business-rule candidate only.
 */
internal object LassoRules {
    const val MAX_PATH_POINTS = 4_096

    fun select(root: DocumentRoot, documentId: SceneId, expectedRevision: Long, pageId: PageId,
               polygon: List<PointD>, maxPairTests: Int): LassoSelectionResult {
        if (root.id != documentId) return LassoSelectionResult(failure = LassoFailure.WRONG_DOCUMENT)
        if (expectedRevision < 0 || root.revision != expectedRevision) return LassoSelectionResult(failure = LassoFailure.STALE_ROOT)
        val page = root.findPage(pageId) ?: return LassoSelectionResult(failure = LassoFailure.MISSING_PAGE)
        if (polygon.size < 3 || polygon.size > MAX_PATH_POINTS ||
            polygon.any { !it.x.isFinite() || !it.y.isFinite() || abs(it.x) > 1e9 || abs(it.y) > 1e9 })
            return LassoSelectionResult(failure = LassoFailure.INVALID_PATH)
        if (maxPairTests !in 1..4_000_000) return LassoSelectionResult(failure = LassoFailure.INVALID_BUDGET)

        var polygonBounds = BoundsD(polygon[0].x, polygon[0].y, polygon[0].x, polygon[0].y)
        for (i in 1 until polygon.size)
            polygonBounds = polygonBounds.union(BoundsD(polygon[i].x, polygon[i].y, polygon[i].x, polygon[i].y))

        var tests = 0
        val hits = mutableListOf<ObjectId>()
        for (stroke in root.pageObjects(pageId)) {
            val visualRadius = stroke.brush.width / 2.0 * PolylineGeometry.maxScale(stroke.transform)
            val widened = BoundsD(polygonBounds.left - visualRadius, polygonBounds.top - visualRadius,
                polygonBounds.right + visualRadius, polygonBounds.bottom + visualRadius)
            if (!PolylineGeometry.overlaps(widened, stroke.bounds())) continue
            val needed = PolylineGeometry.segmentCount(stroke.samples.size) * polygon.size
            if (tests + needed > maxPairTests) return LassoSelectionResult(failure = LassoFailure.CAPACITY)
            tests += needed
            if (selectionHit(stroke, polygon, visualRadius)) hits.add(stroke.id)
        }
        if (hits.isEmpty()) return LassoSelectionResult(failure = LassoFailure.NO_HIT)
        return LassoSelectionResult(LassoSelectionPlan(root.id, root.revision, page.id, hits))
    }

    /** Translate, uniformly scale and rotate the selection around an anchor, composed as one matrix per object. */
    fun transform(root: DocumentRoot, documentId: SceneId, expectedRevision: Long, pageId: PageId,
                  selection: List<ObjectId>, dx: Double, dy: Double,
                  scaleFactor: Double = 1.0, anchor: PointD = PointD(0.0, 0.0),
                  angleRadians: Double = 0.0): SelectionTransformResult {
        if (root.id != documentId) return SelectionTransformResult(failure = LassoFailure.WRONG_DOCUMENT)
        if (expectedRevision < 0 || root.revision != expectedRevision) return SelectionTransformResult(failure = LassoFailure.STALE_ROOT)
        val page = root.findPage(pageId) ?: return SelectionTransformResult(failure = LassoFailure.MISSING_PAGE)
        if (selection.isEmpty()) return SelectionTransformResult(failure = LassoFailure.EMPTY_SELECTION)
        if (selection.any { it.value.isBlank() || it.value.length > 128 } || selection.toSet().size != selection.size)
            return SelectionTransformResult(failure = LassoFailure.INVALID_SELECTION)
        if (!dx.isFinite() || !dy.isFinite() || abs(dx) > 1e9 || abs(dy) > 1e9 ||
            !scaleFactor.isFinite() || scaleFactor !in 0.05..20.0 ||
            !angleRadians.isFinite() || abs(angleRadians) > 1e6 ||
            !anchor.x.isFinite() || !anchor.y.isFinite() || abs(anchor.x) > 1e9 || abs(anchor.y) > 1e9)
            return SelectionTransformResult(failure = LassoFailure.INVALID_TRANSFORM)
        if (dx == 0.0 && dy == 0.0 && scaleFactor == 1.0 && angleRadians == 0.0)
            return SelectionTransformResult(failure = LassoFailure.NO_CHANGE)
        if (selection.size > 100_000) return SelectionTransformResult(failure = LassoFailure.CAPACITY)

        // Page-space edit: rotate, scale around the anchor, then translate; one composed matrix.
        val pageEdit = Affine2D(tx = -anchor.x, ty = -anchor.y)
            .then(Affine2D(cos(angleRadians), sin(angleRadians), -sin(angleRadians), cos(angleRadians), 0.0, 0.0))
            .then(Affine2D(a = scaleFactor, d = scaleFactor))
            .then(Affine2D(tx = anchor.x + dx, ty = anchor.y + dy))
        if (pageEdit.inverseOrNull() == null) return SelectionTransformResult(failure = LassoFailure.INVALID_TRANSFORM)

        val chosen = selection.toSet()
        val changes = mutableListOf<Pair<ObjectId, Affine2D>>()
        for (stroke in root.pageObjects(pageId)) {
            if (stroke.id !in chosen) continue
            val next = stroke.transform.then(pageEdit)
            if (next.inverseOrNull() == null) return SelectionTransformResult(failure = LassoFailure.INVALID_TRANSFORM)
            changes.add(stroke.id to next)
        }
        if (changes.size != chosen.size) {
            val found = changes.map { it.first }.toSet()
            if (chosen.any { root.scene.find(it) == null }) return SelectionTransformResult(failure = LassoFailure.MISSING_OBJECT)
            if (found != chosen) return SelectionTransformResult(failure = LassoFailure.WRONG_PAGE)
        }
        return SelectionTransformResult(SelectionTransformPlan(root.id, root.revision, page.id, changes))
    }

    /** Same-document revision must still belong to the original single authority; no fork adoption. */
    fun revalidate(root: DocumentRoot, plan: LassoSelectionPlan): LassoFailure? = when {
        root.id != plan.documentId -> LassoFailure.WRONG_DOCUMENT
        root.revision != plan.expectedRootRevision -> LassoFailure.STALE_ROOT
        root.findPage(plan.pageId) == null -> LassoFailure.MISSING_PAGE
        else -> null
    }

    fun revalidate(root: DocumentRoot, plan: SelectionTransformPlan): LassoFailure? = when {
        root.id != plan.documentId -> LassoFailure.WRONG_DOCUMENT
        root.revision != plan.expectedRootRevision -> LassoFailure.STALE_ROOT
        root.findPage(plan.pageId) == null -> LassoFailure.MISSING_PAGE
        else -> null
    }

    private fun selectionHit(stroke: StrokeObject, polygon: List<PointD>, visualRadius: Double): Boolean {
        val limit = visualRadius * visualRadius
        val points = stroke.samples
        var anyInside = false
        for (i in 0 until points.size) {
            if (PolylineGeometry.pointInPolygon(stroke.transform.map(PointD(points.x(i), points.y(i))), polygon)) {
                anyInside = true
                break
            }
        }
        if (anyInside) return true
        for (i in 0 until PolylineGeometry.segmentCount(points.size)) {
            val a = stroke.transform.map(PointD(points.x(i), points.y(i)))
            val b = stroke.transform.map(PointD(points.x(kotlin.math.min(i + 1, points.size - 1)), points.y(kotlin.math.min(i + 1, points.size - 1))))
            var previous = polygon[polygon.size - 1]
            for (vertex in polygon) {
                if (PolylineGeometry.segmentDistanceSquared(a, b, previous, vertex) <= limit) return true
                previous = vertex
            }
        }
        return false
    }
}
