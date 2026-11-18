package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class SceneObjectOrderTest {
    private data class ImageObjectTest(
        override val id: ObjectId,
        override val objectRevision: Long,
        override val layerId: LayerId,
        val resourceHash: String,
        override val transform: Affine2D = Affine2D(),
    ) : SceneObject {
        override fun bounds() = BoundsD(0.0, 0.0, 10.0, 10.0)
    }

    private data class TextObjectTest(
        override val id: ObjectId,
        override val objectRevision: Long,
        override val layerId: LayerId,
        val text: String,
        override val transform: Affine2D = Affine2D(),
    ) : SceneObject {
        override fun bounds() = BoundsD(0.0, 0.0, 20.0, 10.0)
    }

    private val pageA = PageDescriptor(PageId("a"), LayerId("layer-a"), 300.0, 200.0)
    private val pageB = PageDescriptor(PageId("b"), LayerId("layer-b"), 300.0, 200.0)
    private val textA = TextObjectTest(ObjectId("text-a"), 1, pageA.layerId, "alpha")
    private val imageA = ImageObjectTest(ObjectId("image-a"), 1, pageA.layerId, "ab".repeat(32))
    private val strokeA = StrokeObject(ObjectId("stroke-a"), 1, pageA.layerId,
        BrushSpec(BrushKind.MONOLINE, 1, 2.0, 0xff112233.toInt()),
        StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(1.0f), longArrayOf(0)))
    private val textB = TextObjectTest(ObjectId("text-b"), 1, pageB.layerId, "other")
    private val objects = listOf(textA, imageA, strokeA, textB)
    private val initialIds = objects.map { it.id }

    private fun root() = DocumentRoot.compose("mixed",
        SceneSnapshot.restoredObjects(SceneId("doc"), 3, objects), listOf(pageA, pageB))

    private fun ids(scene: SceneSnapshot) = scene.sceneObjects().map { it.id }

    @Test fun mixedTypesKeepStableSelectionOrderOtherPageSlotsAndPayloadIdentity() {
        val root = root()
        val front = assertNotNull(ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            listOf(strokeA.id, textA.id), ObjectOrderDirection.FRONT, 10).plan)
        assertEquals(listOf(imageA.id, textA.id, strokeA.id, textB.id), front.objectIds())
        val reordered = SceneSnapshot.reordered(root.scene, front.objectIds())
        assertEquals(front.objectIds(), ids(reordered))
        for (expected in objects) assertSame(expected, reordered.findObject(expected.id))
        assertEquals(initialIds, ids(root.scene))
        val back = assertNotNull(ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            listOf(textA.id, strokeA.id), ObjectOrderDirection.BACK, 10).plan)
        assertEquals(listOf(textA.id, strokeA.id, imageA.id, textB.id), back.objectIds())
        assertEquals(textB.id, back.objectIds().last())
    }

    @Test fun sparsePageOrderDeltaRestoresMixedOrderWithoutReplacingObjects() {
        val root = root()
        val plan = assertNotNull(ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            listOf(textA.id, strokeA.id), ObjectOrderDirection.FRONT, 10).plan)
        val delta = PageObjectOrder(pageA.id, listOf(textA.id, imageA.id, strokeA.id),
            listOf(imageA.id, textA.id, strokeA.id))
        val afterScene = delta.apply(root.scene, pageA.layerId, false)
        val afterRoot = DocumentRoot.compose(root.title, afterScene, root.pages())
        assertEquals(plan.objectIds(), ids(afterScene))
        assertTrue(delta.matches(afterRoot, true)); assertFalse(delta.matches(afterRoot, false))
        val restored = delta.apply(afterScene, pageA.layerId, true)
        assertEquals(initialIds, ids(restored))
        for (expected in objects) assertSame(expected, restored.findObject(expected.id))
    }

    @Test fun legacyStrokeReadsFailFastInsteadOfSilentlyDroppingMixedObjects() {
        val root = root()
        assertEquals(4, root.scene.objectCount)
        assertEquals(initialIds, ids(root.scene))
        assertSame(textA, root.scene.findObject(textA.id))
        assertFailsWith<IllegalArgumentException> { root.scene.objects() }
        assertFailsWith<IllegalArgumentException> { root.scene.find(textA.id) }
        assertFailsWith<IllegalArgumentException> { root.scene.forEachObject { } }
        var visited = 0
        root.scene.forEachSceneObject { visited++ }
        assertEquals(4, visited)
    }

    @Test fun mixedIdentityChecksReturnTypedDuplicateInsteadOfThrowing() {
        val root = root()
        val request = ImageInsertRequest(CommandId("insert-image"), root.id, root.revision,
            pageA.id, textA.id, PointD(0.0, 0.0), 1.0)
        val original = OriginalImageRef("cd".repeat(32), 10, EncodedImageFormat.PNG, 1, 1, 1)
        val result = ImageInsertRules.prepare(root, request, original, 100)
        assertEquals(ImageInsertFailure.DUPLICATE_OBJECT, assertIs<ImageInsertResult.Rejected>(result).reason)
        assertEquals(initialIds, ids(root.scene))
    }

    @Test fun mixedOrderingKeepsExistingIdentityAndNoChangeRejections() {
        val root = root()
        assertEquals(ObjectOrderFailure.INVALID_SELECTION, ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            emptyList(), ObjectOrderDirection.FRONT, 10).failure)
        assertEquals(ObjectOrderFailure.INVALID_SELECTION, ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            listOf(textA.id, textA.id), ObjectOrderDirection.FRONT, 10).failure)
        assertEquals(ObjectOrderFailure.MISSING_OBJECT, ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            listOf(ObjectId("missing")), ObjectOrderDirection.FRONT, 10).failure)
        assertEquals(ObjectOrderFailure.WRONG_PAGE, ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            listOf(textB.id), ObjectOrderDirection.FRONT, 10).failure)
        assertEquals(ObjectOrderFailure.NO_CHANGE, ObjectOrderRules.plan(root, root.id, 3, pageA.id,
            listOf(strokeA.id), ObjectOrderDirection.FRONT, 10).failure)
        assertEquals(initialIds, ids(root.scene))
    }
}
