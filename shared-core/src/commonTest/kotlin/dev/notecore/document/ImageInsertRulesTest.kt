package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class ImageInsertRulesTest {
    private val page = PageDescriptor(PageId("target"), LayerId("target-ink"), 100.0, 100.0)
    private val otherPage = page.copy(id = PageId("other"), layerId = LayerId("other-ink"))
    private val document = SceneId("note")
    private val original = OriginalImageRef("a".repeat(64), 400, EncodedImageFormat.JPEG, 5, 3, 6)
    private val request = ImageInsertRequest(CommandId("insert"), document, 0, page.id, ObjectId("picture"), PointD(10.0, 20.0), 2.0)

    private fun prepare(
        root: DocumentRoot = DocumentRoot.empty(document, "Note", page),
        request: ImageInsertRequest = this.request,
        original: OriginalImageRef = this.original,
        budget: Long = 15,
    ) = ImageInsertRules.prepare(root, request, original, budget)

    @Test fun preparationRetainsOriginalAndBindsToTheTargetPageAndOperation() {
        val root = DocumentRoot.empty(document, "Note", page)
        val plan = assertIs<ImageInsertResult.Prepared>(prepare(root)).plan
        assertEquals(request, plan.request)
        assertEquals(page, plan.page)
        assertEquals(original, plan.original)
        assertNull(ImageInsertRules.revalidate(root, plan, original.copy()))
        assertEquals(0, root.scene.objectCount)
        assertEquals(0L, root.revision)
    }

    @Test fun sourceAndAlreadyRotatedThumbnailsLandOnTheSamePagePoints() {
        val plan = assertIs<ImageInsertResult.Prepared>(prepare()).plan
        val source = assertNotNull(plan.decodedToPage(3, 2, DecodedImageOrientation.SOURCE))
        val display = assertNotNull(plan.decodedToPage(2, 3, DecodedImageOrientation.DISPLAY))
        val displayOrigin = PointD(10.0, 20.0)
        assertEquals(displayOrigin, source.map(PointD(0.0, 2.0)))
        assertEquals(displayOrigin, display.map(PointD(0.0, 0.0)))
        val sourceCorner = PointD(3.0, 0.0)
        val opposite = PointD(16.0, 30.0)
        assertEquals(opposite, source.map(sourceCorner))
        assertEquals(opposite, display.map(PointD(2.0, 3.0)))
        assertEquals(sourceCorner, assertNotNull(source.inverseOrNull()).map(opposite))
    }

    @Test fun equalDecodeDimensionsDoNotHideWhetherMirrorWasApplied() {
        val png = original.copy(format = EncodedImageFormat.PNG, exifOrientation = 2)
        val plan = assertIs<ImageInsertResult.Prepared>(prepare(original = png)).plan
        val source = assertNotNull(plan.decodedToPage(5, 3, DecodedImageOrientation.SOURCE))
        val display = assertNotNull(plan.decodedToPage(5, 3, DecodedImageOrientation.DISPLAY))
        assertEquals(PointD(19.0, 21.0), source.map(PointD(0.5, 0.5)))
        assertEquals(PointD(19.0, 21.0), display.map(PointD(4.5, 0.5)))
        assertNull(plan.decodedToPage(0, 3, DecodedImageOrientation.SOURCE))
        assertNull(plan.decodedToPage(5, 4, DecodedImageOrientation.DISPLAY))
    }

    @Test fun rejectsAnObjectIdOccupiedOnAnotherPageAtPrepareAndRevalidation() {
        val snapshot = SceneSnapshot.empty(document)
        val root = DocumentRoot.compose("Note", snapshot, listOf(page, otherPage))
        val plan = assertIs<ImageInsertResult.Prepared>(prepare(root)).plan
        val stroke = StrokeObject(
            request.objectId, 1, otherPage.layerId, BrushSpec(BrushKind.MONOLINE, 1, 1.0, -1),
            StrokeSamples.copyOf(doubleArrayOf(5.0), doubleArrayOf(5.0), floatArrayOf(0.5f), longArrayOf(0L)),
        )
        val occupied = DocumentRoot.compose("Note", SceneSnapshot.restored(document, 0, listOf(stroke)), root.pages())
        assertEquals(ImageInsertResult.Rejected(ImageInsertFailure.DUPLICATE_OBJECT), prepare(occupied))
        assertEquals(ImageInsertFailure.DUPLICATE_OBJECT, ImageInsertRules.revalidate(occupied, plan, original))
    }

    @Test fun revalidationRejectsStaleTargetsAndAnyChangedOriginalDescriptor() {
        val root = DocumentRoot.empty(document, "Note", page)
        val plan = assertIs<ImageInsertResult.Prepared>(prepare(root)).plan
        val other = DocumentRoot.empty(SceneId("different"), "Note", page)
        val stale = DocumentRoot.compose("Note", SceneSnapshot.restored(document, 1, emptyList()), listOf(page))
        val missing = DocumentRoot.empty(document, "Note", otherPage)
        val changed = DocumentRoot.empty(document, "Note", page.copy(layerId = otherPage.layerId))
        assertEquals(ImageInsertFailure.WRONG_DOCUMENT, ImageInsertRules.revalidate(other, plan, original))
        assertEquals(ImageInsertFailure.STALE_ROOT, ImageInsertRules.revalidate(stale, plan, original))
        assertEquals(ImageInsertFailure.MISSING_PAGE, ImageInsertRules.revalidate(missing, plan, original))
        assertEquals(ImageInsertFailure.PAGE_CHANGED, ImageInsertRules.revalidate(changed, plan, original))
        val substitutions = listOf(original.copy(sha256 = "b".repeat(64)), original.copy(byteCount = 401),
            original.copy(pixelWidth = 3, pixelHeight = 5), original.copy(exifOrientation = 1),
            original.copy(format = EncodedImageFormat.PNG))
        for (substitute in substitutions) {
            assertEquals(ImageInsertFailure.SOURCE_CHANGED, ImageInsertRules.revalidate(root, plan, substitute))
        }
    }

    @Test fun invalidSourcePlacementAndPixelBudgetNeverYieldPlans() {
        assertEquals(ImageInsertResult.Rejected(ImageInsertFailure.INVALID_SOURCE), prepare(original = original.copy(sha256 = "A".repeat(64))))
        assertEquals(ImageInsertResult.Rejected(ImageInsertFailure.INVALID_SOURCE), prepare(original = original.copy(byteCount = 0)))
        assertEquals(ImageInsertResult.SourceGeometryRejected(ImageOrientationFailure.UNKNOWN_ORIENTATION), prepare(original = original.copy(exifOrientation = 0)))
        assertEquals(ImageInsertResult.SourceGeometryRejected(ImageOrientationFailure.INVALID_DIMENSIONS), prepare(original = original.copy(pixelHeight = -1)))
        assertEquals(ImageInsertResult.SourceGeometryRejected(ImageOrientationFailure.PIXEL_CAPACITY), prepare(budget = 14))
        for (scale in listOf(Double.NaN, Double.MAX_VALUE, Double.MIN_VALUE, -1.0, 0.0)) {
            assertEquals(ImageInsertResult.Rejected(ImageInsertFailure.INVALID_PLACEMENT), prepare(request = request.copy(pageUnitsPerPixel = scale)))
        }
        val collapsed = request.copy(topLeft = PointD(1e30, 20.0))
        assertEquals(ImageInsertResult.Rejected(ImageInsertFailure.INVALID_PLACEMENT), prepare(request = collapsed))
        assertEquals(ImageInsertResult.Rejected(ImageInsertFailure.INVALID_ID), prepare(request = request.copy(operationId = CommandId("\uD800"))))
    }
}
