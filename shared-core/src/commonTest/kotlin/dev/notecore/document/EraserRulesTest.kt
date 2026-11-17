package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class EraserRulesTest {
    private val pageA = PageDescriptor(PageId("page-a"), LayerId("layer-a"), 1000.0, 1400.0)
    private val pageB = PageDescriptor(PageId("page-b"), LayerId("layer-b"), 1000.0, 1400.0)

    private fun ink(id: String, layer: LayerId, kind: BrushKind = BrushKind.MONOLINE, width: Double = 4.0,
                    xs: DoubleArray, ys: DoubleArray, transform: Affine2D = Affine2D()) = StrokeObject(
        ObjectId(id), 1, layer, BrushSpec(kind, 1, width, -1),
        StrokeSamples.copyOf(xs, ys, FloatArray(xs.size) { 0.5f }, LongArray(xs.size) { it.toLong() }), transform)

    private fun root(objects: List<StrokeObject>, revision: Long = 3) = DocumentRoot.compose(
        "eraser", SceneSnapshot.restored(SceneId("eraser-doc"), revision, objects), listOf(pageA, pageB))

    private fun path(vararg points: Pair<Double, Double>) = points.map { PointD(it.first, it.second) }

    private fun horizontal(id: String, y: Double, layer: LayerId = pageA.layerId, kind: BrushKind = BrushKind.MONOLINE) =
        ink(id, layer, kind, 4.0, doubleArrayOf(100.0, 200.0), doubleArrayOf(y, y))

    private fun plan(root: DocumentRoot, path: List<PointD>, radius: Double = 6.0, maxPairTests: Int = 100_000) =
        EraserRules.plan(root, root.id, root.revision, pageA.id, path, radius, maxPairTests)

    @Test fun straightPathHitsStrokeAndRevalidates() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val result = plan(root, path(150.0 to 90.0, 150.0 to 110.0))
        val plan = assertNotNull(result.plan)
        assertEquals(listOf(ObjectId("s1")), plan.objectIds())
        assertEquals(root.id, plan.documentId)
        assertEquals(root.revision, plan.expectedRootRevision)
        assertNull(EraserRules.revalidate(root, plan))
    }

    @Test fun distantPathReportsNoHit() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val result = plan(root, path(500.0 to 500.0, 500.0 to 520.0))
        assertNull(result.plan)
        assertEquals(EraserFailure.NO_HIT, result.failure)
    }

    @Test fun tangentBoundaryHitsAndBeyondMisses() {
        val root = root(listOf(horizontal("s1", 100.0)))
        // Stroke radius 2 + eraser radius 6 = threshold 8; path at y=108 is exactly tangent.
        assertNotNull(plan(root, path(100.0 to 108.0, 200.0 to 108.0)).plan)
        val beyond = plan(root, path(100.0 to 108.01, 200.0 to 108.01))
        assertNull(beyond.plan)
        assertEquals(EraserFailure.NO_HIT, beyond.failure)
    }

    @Test fun highlighterAndPressureStrokesShareTheSameHitPath() {
        val root = root(listOf(
            horizontal("mono", 100.0),
            horizontal("highlight", 200.0, kind = BrushKind.HIGHLIGHTER),
            horizontal("pressure", 300.0, kind = BrushKind.PRESSURE)))
        val result = plan(root, path(150.0 to 50.0, 150.0 to 350.0))
        assertEquals(listOf(ObjectId("mono"), ObjectId("highlight"), ObjectId("pressure")),
            assertNotNull(result.plan).objectIds())
    }

    @Test fun singlePointStrokeAndSinglePointPath() {
        val root = root(listOf(ink("dot", pageA.layerId, xs = doubleArrayOf(100.0), ys = doubleArrayOf(100.0))))
        assertNotNull(plan(root, path(104.0 to 100.0)).plan)
        assertEquals(EraserFailure.NO_HIT, plan(root, path(109.0 to 100.0)).failure)
    }

    @Test fun multipleHitsKeepCanonicalSceneOrderInOnePlan() {
        val root = root(listOf(horizontal("s1", 100.0), horizontal("s2", 300.0), horizontal("s3", 500.0)))
        val result = plan(root, path(150.0 to 50.0, 150.0 to 350.0))
        // One plan is the input for one delete command and therefore one undo entry.
        assertEquals(listOf(ObjectId("s1"), ObjectId("s2")), assertNotNull(result.plan).objectIds())
    }

    @Test fun transformedStrokeHitsInPageCoordinates() {
        val moved = ink("moved", pageA.layerId, xs = doubleArrayOf(0.0, 100.0), ys = doubleArrayOf(0.0, 0.0),
            transform = Affine2D(tx = 1000.0, ty = 500.0))
        val root = root(listOf(moved))
        assertNotNull(plan(root, path(1040.0 to 490.0, 1060.0 to 510.0)).plan)
        assertEquals(EraserFailure.NO_HIT, plan(root, path(40.0 to 490.0, 60.0 to 510.0)).failure)
    }

    @Test fun scaledStrokeWidensHitThresholdByLargestSingularValue() {
        val scaled = ink("scaled", pageA.layerId, xs = doubleArrayOf(100.0, 200.0), ys = doubleArrayOf(100.0, 100.0),
            transform = Affine2D(a = 2.0, d = 2.0))
        val root = root(listOf(scaled))
        // Page-space stroke is y=200, visual radius 4, eraser radius 6: threshold 10.
        assertNotNull(plan(root, path(250.0 to 210.0, 350.0 to 210.0)).plan)
        assertEquals(EraserFailure.NO_HIT, plan(root, path(250.0 to 210.01, 350.0 to 210.01)).failure)
    }

    @Test fun otherPageStrokesAreNotHit() {
        val root = root(listOf(horizontal("a1", 100.0), horizontal("b1", 100.0, layer = pageB.layerId)))
        val result = plan(root, path(150.0 to 90.0, 150.0 to 110.0))
        assertEquals(listOf(ObjectId("a1")), assertNotNull(result.plan).objectIds())
    }

    @Test fun admissionFailuresAreTyped() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val goodPath = path(150.0 to 90.0, 150.0 to 110.0)
        assertEquals(EraserFailure.WRONG_DOCUMENT,
            EraserRules.plan(root, SceneId("other"), 3, pageA.id, goodPath, 6.0, 100).failure)
        assertEquals(EraserFailure.STALE_ROOT,
            EraserRules.plan(root, root.id, 2, pageA.id, goodPath, 6.0, 100).failure)
        assertEquals(EraserFailure.MISSING_PAGE,
            EraserRules.plan(root, root.id, 3, PageId("missing"), goodPath, 6.0, 100).failure)
        assertEquals(EraserFailure.INVALID_BUDGET, plan(root, goodPath, maxPairTests = 0).failure)
        assertEquals(EraserFailure.INVALID_BUDGET, plan(root, goodPath, maxPairTests = 4_000_001).failure)
    }

    @Test fun invalidPathsAreRejected() {
        val root = root(listOf(horizontal("s1", 100.0)))
        assertEquals(EraserFailure.INVALID_PATH, plan(root, emptyList()).failure)
        assertEquals(EraserFailure.INVALID_PATH,
            plan(root, List(EraserRules.MAX_PATH_POINTS + 1) { PointD(150.0, 90.0 + it) }).failure)
        assertEquals(EraserFailure.INVALID_PATH, plan(root, path(150.0 to 90.0), radius = 0.0).failure)
        assertEquals(EraserFailure.INVALID_PATH, plan(root, path(150.0 to 90.0), radius = Double.NaN).failure)
        assertEquals(EraserFailure.INVALID_PATH, plan(root, path(1e10 to 90.0)).failure)
    }

    @Test fun capacityRefusesWholePlanWithoutPartialHits() {
        val root = root(listOf(horizontal("s1", 100.0), horizontal("s2", 300.0)))
        val crossing = path(150.0 to 50.0, 150.0 to 350.0)
        val refused = plan(root, crossing, maxPairTests = 1)
        assertNull(refused.plan)
        assertEquals(EraserFailure.CAPACITY, refused.failure)
        assertEquals(listOf(ObjectId("s1"), ObjectId("s2")), assertNotNull(plan(root, crossing, maxPairTests = 2).plan).objectIds())
    }

    @Test fun zeroLengthSegmentsDoNotBreakHitTesting() {
        val root = root(listOf(ink("dup", pageA.layerId,
            xs = doubleArrayOf(100.0, 100.0, 200.0), ys = doubleArrayOf(100.0, 100.0, 100.0))))
        assertNotNull(plan(root, path(150.0 to 90.0, 150.0 to 110.0)).plan)
        assertEquals(EraserFailure.NO_HIT, plan(root, path(500.0 to 500.0, 500.0 to 520.0)).failure)
    }

    @Test fun revalidateDetectsDocumentRevisionAndPageChanges() {
        val root = root(listOf(horizontal("s1", 100.0)))
        val plan = assertNotNull(plan(root, path(150.0 to 90.0, 150.0 to 110.0)).plan)
        assertNull(EraserRules.revalidate(root, plan))
        val advanced = root(root.scene.objects(), revision = 4)
        assertEquals(EraserFailure.STALE_ROOT, EraserRules.revalidate(advanced, plan))
        val withoutPage = DocumentRoot.compose("eraser",
            SceneSnapshot.restored(SceneId("eraser-doc"), 3, emptyList()), listOf(pageB))
        assertEquals(EraserFailure.MISSING_PAGE, EraserRules.revalidate(withoutPage, plan))
        val other = DocumentRoot.compose("eraser",
            SceneSnapshot.restored(SceneId("other-doc"), 3, emptyList()), listOf(pageA))
        assertEquals(EraserFailure.WRONG_DOCUMENT, EraserRules.revalidate(other, plan))
    }
}
