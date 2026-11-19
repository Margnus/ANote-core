package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class LassoPasteRulesTest {
    private val pageA = PageDescriptor(PageId("src"), LayerId("layer-src"), 1000.0, 1400.0)
    private val pageB = PageDescriptor(PageId("dst"), LayerId("layer-dst"), 1000.0, 1400.0)
    private val pageC = PageDescriptor(PageId("other"), LayerId("layer-other"), 1000.0, 1400.0)

    private fun ink(id: String, layer: LayerId = pageA.layerId, y: Double = 100.0) = StrokeObject(
        ObjectId(id), 1, layer, BrushSpec(BrushKind.MONOLINE, 1, 4.0, -1),
        StrokeSamples.copyOf(doubleArrayOf(100.0, 200.0), doubleArrayOf(y, y),
            FloatArray(2) { 0.5f }, LongArray(2) { it.toLong() }))

    private val s1 = ink("s1", y = 100.0)
    private val s2 = ink("s2", y = 200.0)
    private val other = ink("other", layer = pageC.layerId, y = 100.0)

    private fun root() = DocumentRoot.compose("paste",
        SceneSnapshot.restored(SceneId("paste-doc"), 3, listOf(s1, s2, other)),
        listOf(pageA, pageB, pageC))

    private fun copy(root: DocumentRoot = root(), source: PageId = pageA.id,
                     selection: List<ObjectId> = listOf(ObjectId("s1"), ObjectId("s2")),
                     target: PageId = pageB.id,
                     mapping: Map<ObjectId, ObjectId> = mapOf(
                         ObjectId("s1") to ObjectId("c1"), ObjectId("s2") to ObjectId("c2")),
                     offset: PointD = PointD(0.0, 0.0),
                     docId: SceneId = root.id, rev: Long = root.revision) =
        LassoPasteRules.copy(root, docId, rev, source, selection, target, mapping, offset)

    @Test fun basicCopyCreatesNewObjectsOnTargetLayerWithFreshIds() {
        val plan = assertNotNull(copy().plan)
        assertEquals(2, plan.newObjects().size)
        for (obj in plan.newObjects()) {
            assertEquals(pageB.layerId, obj.layerId)
            assertEquals(1, obj.objectRevision)
        }
        assertEquals(ObjectId("c1"), plan.newObjects()[0].id)
        assertEquals(ObjectId("c2"), plan.newObjects()[1].id)
        assertEquals(BrushSpec(BrushKind.MONOLINE, 1, 4.0, -1), plan.newObjects()[0].brush)
        assertEquals(s1.samples, plan.newObjects()[0].samples)
    }

    @Test fun relativePositionsPreservedAndOffsetShiftsAllObjects() {
        val offset = PointD(50.0, 30.0)
        val plan = assertNotNull(copy(offset = offset).plan)
        assertEquals(2, plan.newObjects().size)
        val first = plan.newObjects()[0]
        val second = plan.newObjects()[1]
        val p1a = first.transform.map(PointD(s1.samples.x(0), s1.samples.y(0)))
        val p2a = second.transform.map(PointD(s2.samples.x(0), s2.samples.y(0)))
        assertEquals(100.0 + 50.0, p1a.x, 1e-9); assertEquals(100.0 + 30.0, p1a.y, 1e-9)
        assertEquals(100.0 + 50.0, p2a.x, 1e-9); assertEquals(200.0 + 30.0, p2a.y, 1e-9)
        val distBefore = s2.samples.y(0) - s1.samples.y(0)
        val distAfter = p2a.y - p1a.y
        assertEquals(distBefore, distAfter, 1e-9)
    }

    @Test fun mappingConflictAndInvalidMappingAreTyped() {
        assertEquals(LassoPasteFailure.INVALID_MAPPING, copy(
            mapping = mapOf(ObjectId("s1") to ObjectId("c1"))).failure)
        assertEquals(LassoPasteFailure.MAPPING_CONFLICT, copy(
            mapping = mapOf(ObjectId("s1") to ObjectId("c1"), ObjectId("s2") to ObjectId("c1"))).failure)
        assertEquals(LassoPasteFailure.MAPPING_CONFLICT, copy(
            mapping = mapOf(ObjectId("s1") to ObjectId("s2"), ObjectId("s2") to ObjectId("c2"))).failure)
        assertEquals(LassoPasteFailure.MAPPING_CONFLICT, copy(
            mapping = mapOf(ObjectId("s1") to ObjectId("other"), ObjectId("s2") to ObjectId("c2"))).failure)
    }

    @Test fun admissionFailuresAreTyped() {
        val r = root()
        assertEquals(LassoPasteFailure.WRONG_DOCUMENT,
            copy(docId = SceneId("other")).failure)
        assertEquals(LassoPasteFailure.STALE_ROOT, copy(rev = 2).failure)
        assertEquals(LassoPasteFailure.SOURCE_PAGE_MISSING, copy(source = PageId("gone")).failure)
        assertEquals(LassoPasteFailure.TARGET_PAGE_MISSING, copy(target = PageId("gone")).failure)
        assertEquals(LassoPasteFailure.EMPTY_SELECTION, copy(selection = emptyList()).failure)
        assertEquals(LassoPasteFailure.INVALID_SELECTION, copy(
            selection = listOf(ObjectId("s1"), ObjectId("s1"))).failure)
        assertEquals(LassoPasteFailure.MISSING_OBJECT, copy(
            selection = listOf(ObjectId("gone")), mapping = mapOf(ObjectId("gone") to ObjectId("c1"))).failure)
        assertEquals(LassoPasteFailure.WRONG_PAGE, copy(
            selection = listOf(ObjectId("other")),
            mapping = mapOf(ObjectId("other") to ObjectId("c1"))).failure)
        assertEquals(LassoPasteFailure.INVALID_OFFSET, copy(offset = PointD(1e10, 0.0)).failure)
    }

    @Test fun duplicateSelectionAndOversizedAreRejected() {
        assertEquals(LassoPasteFailure.INVALID_SELECTION, copy(
            selection = List(2) { ObjectId("s1") },
            mapping = mapOf(ObjectId("s1") to ObjectId("c1"))).failure)
        assertEquals(LassoPasteFailure.CAPACITY, copy(
            selection = List(100_001) { ObjectId("s1") },
            mapping = (0 until 100_001).associate { ObjectId("x$it") to ObjectId("y$it") }).failure)
    }

    @Test fun originalSceneAndObjectsUnchanged() {
        val r = root()
        assertNotNull(copy(r).plan)
        assertEquals(3, r.scene.objectCount)
        assertSame(s1, r.scene.findObject(ObjectId("s1")))
        assertSame(s2, r.scene.findObject(ObjectId("s2")))
        assertEquals(3, r.revision)
        assertEquals(3, r.pageCount)
    }
}
