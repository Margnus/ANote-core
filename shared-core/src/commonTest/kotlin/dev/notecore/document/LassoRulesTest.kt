package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class LassoRulesTest {
    private val pageA = PageDescriptor(PageId("page-a"), LayerId("layer-a"), 1000.0, 1400.0)
    private val pageB = PageDescriptor(PageId("page-b"), LayerId("layer-b"), 1000.0, 1400.0)

    private fun ink(id: String, layer: LayerId = pageA.layerId, width: Double = 4.0,
                    xs: DoubleArray, ys: DoubleArray, transform: Affine2D = Affine2D()) = StrokeObject(
        ObjectId(id), 1, layer, BrushSpec(BrushKind.MONOLINE, 1, width, -1),
        StrokeSamples.copyOf(xs, ys, FloatArray(xs.size) { 0.5f }, LongArray(xs.size) { it.toLong() }), transform)

    private fun root(objects: List<StrokeObject>, revision: Long = 3) = DocumentRoot.compose(
        "lasso", SceneSnapshot.restored(SceneId("lasso-doc"), revision, objects), listOf(pageA, pageB))

    private fun path(vararg points: Pair<Double, Double>) = points.map { PointD(it.first, it.second) }

    private fun horizontal(id: String, y: Double, layer: LayerId = pageA.layerId, width: Double = 4.0) =
        ink(id, layer, width, doubleArrayOf(100.0, 200.0), doubleArrayOf(y, y))

    private val square = path(50.0 to 50.0, 300.0 to 50.0, 300.0 to 300.0, 50.0 to 300.0)

    private fun select(root: DocumentRoot, polygon: List<PointD> = square, maxPairTests: Int = 100_000) =
        LassoRules.select(root, root.id, root.revision, pageA.id, polygon, maxPairTests)

    @Test fun containedStrokeIsSelected() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val plan = assertNotNull(select(root).plan)
        assertEquals(listOf(ObjectId("s1")), plan.objectIds())
        assertNull(LassoRules.revalidate(root, plan))
    }

    @Test fun crossingStrokeWithBothEndpointsOutsideIsSelected() {
        val root = root(listOf(ink("cross", xs = doubleArrayOf(0.0, 400.0), ys = doubleArrayOf(150.0, 150.0))))
        assertEquals(listOf(ObjectId("cross")), assertNotNull(select(root).plan).objectIds())
    }

    @Test fun outsideStrokeReportsNoHit() {
        val root = root(listOf(horizontal("far", 500.0)))
        val result = select(root)
        assertNull(result.plan)
        assertEquals(LassoFailure.NO_HIT, result.failure)
    }

    @Test fun thickStrokeTouchingPolygonEdgeIsSelectedWithoutCrossing() {
        // Stroke y=100 with radius 10 is outside the polygon whose top edge is y=108: gap 8 <= 10.
        val touching = path(50.0 to 108.0, 300.0 to 108.0, 300.0 to 300.0, 50.0 to 300.0)
        val root = root(listOf(horizontal("thick", 100.0, width = 20.0)))
        assertEquals(listOf(ObjectId("thick")), assertNotNull(select(root, touching).plan).objectIds())
        val separated = path(50.0 to 115.0, 300.0 to 115.0, 300.0 to 300.0, 50.0 to 300.0)
        assertEquals(LassoFailure.NO_HIT, select(root, separated).failure)
    }

    @Test fun smallPolygonEntirelyOnThickStrokeIsSelected() {
        val root = root(listOf(horizontal("thick", 100.0, width = 40.0)))
        val small = path(120.0 to 95.0, 180.0 to 95.0, 180.0 to 105.0, 120.0 to 105.0)
        assertEquals(listOf(ObjectId("thick")), assertNotNull(select(root, small).plan).objectIds())
    }

    @Test fun multipleHitsKeepSceneOrderAndOtherPageIsolated() {
        val root = root(listOf(horizontal("s1", 100.0), horizontal("s2", 200.0),
            horizontal("b1", 100.0, layer = pageB.layerId)))
        assertEquals(listOf(ObjectId("s1"), ObjectId("s2")), assertNotNull(select(root).plan).objectIds())
    }

    @Test fun selectionAdmissionFailuresAreTyped() {
        val root = root(listOf(horizontal("s1", 100.0)))
        assertEquals(LassoFailure.WRONG_DOCUMENT,
            LassoRules.select(root, SceneId("other"), 3, pageA.id, square, 100).failure)
        assertEquals(LassoFailure.STALE_ROOT,
            LassoRules.select(root, root.id, 2, pageA.id, square, 100).failure)
        assertEquals(LassoFailure.MISSING_PAGE,
            LassoRules.select(root, root.id, 3, PageId("missing"), square, 100).failure)
        assertEquals(LassoFailure.INVALID_PATH, select(root, path(0.0 to 0.0, 1.0 to 1.0)).failure)
        assertEquals(LassoFailure.INVALID_PATH, select(root, path(1e10 to 0.0, 1.0 to 1.0, 2.0 to 0.0)).failure)
        assertEquals(LassoFailure.INVALID_BUDGET, select(root, maxPairTests = 0).failure)
    }

    @Test fun selectionCapacityRefusesWholePlanWithoutPartialHits() {
        val root = root(listOf(horizontal("s1", 100.0), horizontal("s2", 200.0)))
        val refused = select(root, maxPairTests = 4)
        assertNull(refused.plan)
        assertEquals(LassoFailure.CAPACITY, refused.failure)
        assertNotNull(select(root, maxPairTests = 8).plan)
    }

    @Test fun pureTranslationMovesOnlySelectedObjects() {
        val s1 = horizontal("s1", 100.0); val s2 = horizontal("s2", 200.0); val s3 = horizontal("s3", 250.0)
        val root = root(listOf(s1, s2, s3))
        val result = LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1"), ObjectId("s2")), 10.0, 20.0)
        val plan = assertNotNull(result.plan)
        assertEquals(2, plan.changes().size)
        for ((id, transform) in plan.changes()) {
            val original = root.scene.find(id)!!
            val before = original.transform.map(PointD(original.samples.x(0), original.samples.y(0)))
            val moved = transform.map(PointD(original.samples.x(0), original.samples.y(0)))
            assertEquals(before.x + 10.0, moved.x, 1e-9)
            assertEquals(before.y + 20.0, moved.y, 1e-9)
        }
        assertNull(LassoRules.revalidate(root, plan))
    }

    @Test fun uniformScaleAroundAnchorThenTranslation() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val result = LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1")), 10.0, 0.0, scaleFactor = 2.0, anchor = PointD(100.0, 100.0))
        val plan = assertNotNull(result.plan)
        val (_, transform) = plan.changes().single()
        // p' = 2*(p-anchor)+anchor+(10,0): (100,100)->(110,100), (200,100)->(310,100).
        assertEquals(PointD(110.0, 100.0), transform.map(PointD(100.0, 100.0)))
        assertEquals(PointD(310.0, 100.0), transform.map(PointD(200.0, 100.0)))
    }

    @Test fun transformAdmissionFailuresAreTyped() {
        val root = root(listOf(horizontal("s1", 100.0), horizontal("b1", 100.0, layer = pageB.layerId)))
        fun call(selection: List<ObjectId>, dx: Double = 1.0, dy: Double = 0.0, factor: Double = 1.0) =
            LassoRules.transform(root, root.id, root.revision, pageA.id, selection, dx, dy, factor)
        assertEquals(LassoFailure.EMPTY_SELECTION, call(emptyList()).failure)
        assertEquals(LassoFailure.INVALID_SELECTION, call(listOf(ObjectId("s1"), ObjectId("s1"))).failure)
        assertEquals(LassoFailure.MISSING_OBJECT, call(listOf(ObjectId("missing"))).failure)
        assertEquals(LassoFailure.WRONG_PAGE, call(listOf(ObjectId("b1"))).failure)
        assertEquals(LassoFailure.INVALID_TRANSFORM, call(listOf(ObjectId("s1")), factor = 0.0).failure)
        assertEquals(LassoFailure.INVALID_TRANSFORM, call(listOf(ObjectId("s1")), factor = 25.0).failure)
        assertEquals(LassoFailure.INVALID_TRANSFORM, call(listOf(ObjectId("s1")), dx = Double.NaN).failure)
        assertEquals(LassoFailure.NO_CHANGE, call(listOf(ObjectId("s1")), dx = 0.0, dy = 0.0).failure)
    }

    @Test fun revalidateDetectsStaleRevisionAndMissingPage() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val selection = assertNotNull(select(root).plan)
        val advanced = root(root.scene.objects(), revision = 4)
        assertEquals(LassoFailure.STALE_ROOT, LassoRules.revalidate(advanced, selection))
        val withoutPage = DocumentRoot.compose("lasso",
            SceneSnapshot.restored(SceneId("lasso-doc"), 3, emptyList()), listOf(pageB))
        assertEquals(LassoFailure.MISSING_PAGE, LassoRules.revalidate(withoutPage, selection))
        val transform = assertNotNull(LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1")), 5.0, 5.0).plan)
        assertEquals(LassoFailure.STALE_ROOT, LassoRules.revalidate(advanced, transform))
    }

    @Test fun quarterTurnRotationMovesPointAroundAnchor() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val angle = kotlin.math.PI / 2
        val result = LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1")), 0.0, 0.0, anchor = PointD(100.0, 100.0), angleRadians = angle)
        val plan = assertNotNull(result.plan)
        val (_, transform) = plan.changes().single()
        // (200,100) rotated 90° around (100,100) → (100,200).
        val moved = transform.map(PointD(200.0, 100.0))
        assertEquals(100.0, moved.x, 1e-9)
        assertEquals(200.0, moved.y, 1e-9)
    }

    @Test fun fullTurnIsApproximatelyIdentityAndOriginalInkUnchanged() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val twoPi = kotlin.math.PI * 2
        val result = LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1")), 0.0, 0.0, anchor = PointD(100.0, 100.0), angleRadians = twoPi)
        val plan = assertNotNull(result.plan)
        val (_, transform) = plan.changes().single()
        val original = root.scene.find(ObjectId("s1"))!!
        val p = PointD(original.samples.x(1), original.samples.y(1))
        val moved = transform.map(p)
        assertEquals(p.x, moved.x, 1e-6); assertEquals(p.y, moved.y, 1e-6)
        assertEquals(original.transform, root.scene.find(ObjectId("s1"))!!.transform)
    }

    @Test fun rotationWithScaleAndTranslationComposesCorrectly() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val angle = kotlin.math.PI / 2
        val result = LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1")), 10.0, 0.0, scaleFactor = 2.0,
            anchor = PointD(100.0, 100.0), angleRadians = angle)
        val plan = assertNotNull(result.plan)
        val (_, transform) = plan.changes().single()
        // (200,100): rotate 90° around (100,100) → (100,200); scale 2x → (100,300); translate → (110,300).
        val moved = transform.map(PointD(200.0, 100.0))
        assertEquals(110.0, moved.x, 1e-9)
        assertEquals(300.0, moved.y, 1e-9)
    }

    @Test fun rotationAroundNonOriginAnchorWithTranslation() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val angle = kotlin.math.PI
        val result = LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1")), 5.0, 0.0, anchor = PointD(150.0, 100.0), angleRadians = angle)
        val plan = assertNotNull(result.plan)
        val (_, transform) = plan.changes().single()
        // (200,100) rotated 180° around (150,100) → (100,100); translate 5 → (105,100).
        val moved = transform.map(PointD(200.0, 100.0))
        assertEquals(105.0, moved.x, 1e-9)
        assertEquals(100.0, moved.y, 1e-9)
    }

    @Test fun invalidAngleAndZeroRotationNoChange() {
        val root = root(listOf(horizontal("s1", 100.0)))
        assertEquals(LassoFailure.INVALID_TRANSFORM,
            LassoRules.transform(root, root.id, root.revision, pageA.id,
                listOf(ObjectId("s1")), 0.0, 0.0, angleRadians = Double.NaN).failure)
        assertEquals(LassoFailure.INVALID_TRANSFORM,
            LassoRules.transform(root, root.id, root.revision, pageA.id,
                listOf(ObjectId("s1")), 0.0, 0.0, angleRadians = Double.POSITIVE_INFINITY).failure)
        assertEquals(LassoFailure.NO_CHANGE,
            LassoRules.transform(root, root.id, root.revision, pageA.id,
                listOf(ObjectId("s1")), 0.0, 0.0, angleRadians = 0.0).failure)
    }

    @Test fun rotationDoesNotAffectUnselectedObjects() {
        val s1 = horizontal("s1", 100.0); val s2 = horizontal("s2", 200.0)
        val root = root(listOf(s1, s2))
        val angle = kotlin.math.PI / 2
        val result = LassoRules.transform(root, root.id, root.revision, pageA.id,
            listOf(ObjectId("s1")), 0.0, 0.0, anchor = PointD(100.0, 100.0), angleRadians = angle)
        assertNotNull(result.plan)
        assertSame(s2, root.scene.find(ObjectId("s2")))
    }
}
