package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import dev.notecore.canvas.prototype.LayerId
import dev.notecore.canvas.prototype.PointD
import dev.notecore.canvas.prototype.SceneId
import dev.notecore.canvas.prototype.SceneSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PdfAnnotationGeometryRulesTest {
    private val source = PdfSourcePages("ab".repeat(32), 4)
    private val example = PdfSourcePageGeometry(source, 0, PdfCropBox(40.0, 50.0, 340.0, 230.0), 0, 1.0)

    private fun page(id: String, width: Double = 300.0, height: Double = 180.0) =
        PageDescriptor(PageId(id), LayerId("layer-$id"), width, height)

    private fun root(vararg pages: PageDescriptor) =
        DocumentRoot.compose("PDF", SceneSnapshot.empty(SceneId("pdf")), pages.toList())

    private fun catalog(root: DocumentRoot, vararg refs: PdfPageRef) =
        PdfPageCatalog("local", root.id, 0, true, listOf(source), refs.toList())

    private fun bind(root: DocumentRoot, catalog: PdfPageCatalog, id: PageId = root.page(0).id,
                     metadata: PdfSourcePageGeometry = example, revision: Long = catalog.revision) =
        PdfAnnotationGeometryRules.bind(root, catalog, "local", revision, id, metadata)

    private fun assertPoint(expected: PointD, actual: PointD) {
        assertEquals(expected.x, actual.x, 1e-8)
        assertEquals(expected.y, actual.y, 1e-8)
    }

    @Test fun sourceAnchorsAndCanonicalInkPointsAgreeInBothDirectionsForAllRotations() {
        // The positive case uses the fixture's bottom-left color center. The second case
        // uses a different frame and an asymmetric offset of 23 from left, 37 from bottom.
        val fixtureExpected = listOf(PointD(16.0, 164.0), PointD(16.0, 16.0),
            PointD(284.0, 16.0), PointD(164.0, 284.0))
        val negativeExpected = listOf(PointD(57.5, 107.5), PointD(92.5, 57.5),
            PointD(242.5, 92.5), PointD(107.5, 242.5))
        for ((index, rotation) in listOf(0, 90, 180, 270).withIndex()) {
            val metadata = example.copy(sourceIndex = index, rotation = rotation)
            val negative = metadata.copy(crop = PdfCropBox(-120.0, -80.0, 0.0, 0.0), userUnit = 2.5)
            verifyAnchor(metadata, 300.0, 180.0, PointD(56.0, 66.0), fixtureExpected[index])
            verifyAnchor(negative, 300.0, 200.0, PointD(-97.0, -43.0), negativeExpected[index])
        }
    }

    private fun verifyAnchor(metadata: PdfSourcePageGeometry, width: Double, height: Double,
                             pdf: PointD, ink: PointD) {
        val swapped = metadata.rotation == 90 || metadata.rotation == 270
        val page = if (swapped) page("p", height, width) else page("p", width, height)
        val root = root(page)
        val ref = PdfPageRef(page.id, source.sha256, metadata.sourceIndex)
        val binding = assertNotNull(bind(root, catalog(root, ref), metadata = metadata).binding)
        assertEquals(ref, binding.reference)
        assertEquals(page, binding.page)
        val geometry = binding.geometry
        assertPoint(ink, geometry.pdfToPage.map(pdf))
        assertPoint(pdf, geometry.pageToPdf.map(ink))
        assertPoint(ink, geometry.pdfToPage.map(geometry.pageToPdf.map(ink)))
        assertPoint(pdf, geometry.pageToPdf.map(geometry.pdfToPage.map(pdf)))
    }

    @Test fun repeatedSourcePageKeepsDistinctPageAndLayerBindingsThroughPageOrderChanges() {
        val first = page("first")
        val repeated = page("repeat")
        val blank = page("blank")
        val singlePageSource = source.copy(pageCount = 1)
        val metadata = example.copy(source = singlePageSource)
        val model = assertNotNull(PdfPageCatalogRules.createDocument("local", SceneId("pdf"), "PDF",
            singlePageSource, listOf(PdfSourcePage(first, 0))).model)
        val initial = assertNotNull(bind(model.root, model.catalog, metadata = metadata).binding)
        val owner = MemoryDocumentAuthority(model.root)
        val repeatPlan = assertNotNull(PdfPageCatalogRules.planInsert(model.root, model.catalog, "local", 0,
            repeated, null, singlePageSource, 0, CommandId("repeat")).insertion)
        assertIs<DocumentCommit.Accepted>(owner.commit(repeatPlan.command))
        val blankPlan = assertNotNull(PdfPageCatalogRules.planInsert(owner.snapshot(), repeatPlan.after, "local", 1,
            blank, first.id, null, null, CommandId("blank")).insertion)
        assertIs<DocumentCommit.Accepted>(owner.commit(blankPlan.command))
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.MovePage(CommandId("move"), model.root.id,
            owner.snapshot().revision, repeated.id, first.id)))
        val reordered = owner.snapshot()
        val updated = blankPlan.after
        assertEquals(listOf(blank.id, repeated.id, first.id), reordered.pages().map { it.id })
        val firstBinding = assertNotNull(bind(reordered, updated, first.id, metadata).binding)
        val repeatedBinding = assertNotNull(bind(reordered, updated, repeated.id, metadata).binding)
        assertEquals(initial.reference, firstBinding.reference)
        assertEquals(PdfPageRef(repeated.id, source.sha256, 0), repeatedBinding.reference)
        assertEquals(firstBinding.geometry, repeatedBinding.geometry)
        assertNotEquals(firstBinding.page.layerId, repeatedBinding.page.layerId)
        val blankBinding = bind(reordered, updated, blank.id, metadata)
        assertNull(blankBinding.binding)
        assertEquals(PdfPageCatalogFailure.NOT_SOURCE_PAGE, blankBinding.catalogFailure)
        val wrongPage = PdfAnnotationGeometryRules.revalidate(reordered, updated, "local", repeated.id,
            metadata, firstBinding)
        assertNull(wrongPage.binding)
        assertEquals(PdfAnnotationGeometryFailure.STALE_BINDING, wrongPage.failure)
    }

    @Test fun rejectsUnusableCatalogAndMismatchedSourceIdentity() {
        val root = root(page("p"))
        val ref = PdfPageRef(root.page(0).id, source.sha256, 0)
        val catalog = catalog(root, ref)
        val rejected = listOf(
            bind(root, catalog, revision = 1) to PdfPageCatalogFailure.STALE_METADATA,
            bind(root, catalog.copy(documentId = SceneId("other"))) to PdfPageCatalogFailure.WRONG_DOCUMENT,
            bind(root, catalog.copy(spaceId = "elsewhere")) to PdfPageCatalogFailure.WRONG_SPACE,
            bind(root, catalog.copy(complete = false)) to PdfPageCatalogFailure.INCOMPLETE,
            bind(root, catalog.copy(references = listOf(ref, ref))) to PdfPageCatalogFailure.CAPACITY,
            bind(root, catalog.copy(references = listOf(ref.copy(sourceIndex = 4)))) to PdfPageCatalogFailure.INVALID_REFERENCES,
        )
        for ((result, expected) in rejected) {
            assertNull(result.binding)
            assertEquals(expected, result.catalogFailure)
        }
        val missingPage = bind(root, catalog, PageId("missing"))
        assertNull(missingPage.binding)
        assertEquals(PdfAnnotationGeometryFailure.MISSING_PAGE, missingPage.failure)
        for (other in listOf(example.copy(source = source.copy(sha256 = "cd".repeat(32))),
            example.copy(source = source.copy(pageCount = 5)))) {
            val result = bind(root, catalog, metadata = other)
            assertNull(result.binding)
            assertEquals(PdfPageCatalogFailure.SOURCE_CHANGED, result.catalogFailure)
        }
        for (other in listOf(example.copy(sourceIndex = 1), example.copy(sourceIndex = -1))) {
            val result = bind(root, catalog, metadata = other)
            assertNull(result.binding)
            assertEquals(PdfAnnotationGeometryFailure.SOURCE_MISMATCH, result.failure)
        }
        val otherSource = source.copy(sha256 = "cd".repeat(32))
        val wrongRegisteredSource = bind(root, catalog.copy(sources = listOf(source, otherSource)),
            metadata = example.copy(source = otherSource))
        assertNull(wrongRegisteredSource.binding)
        assertEquals(PdfAnnotationGeometryFailure.SOURCE_MISMATCH, wrongRegisteredSource.failure)
    }

    @Test fun rejectsInvalidGeometryAndUnscaledOrUnrotatedPageDimensions() {
        val root = root(page("p"))
        val catalog = catalog(root, PdfPageRef(root.page(0).id, source.sha256, 0))
        for ((metadata, expected) in listOf(
            example.copy(crop = example.crop.copy(left = Double.NaN)) to PdfPageGeometryFailure.INVALID_BOX,
            example.copy(rotation = 45) to PdfPageGeometryFailure.INVALID_ROTATION,
            example.copy(userUnit = 0.0) to PdfPageGeometryFailure.INVALID_USER_UNIT,
            example.copy(userUnit = 100.0) to PdfPageGeometryFailure.SIZE_LIMIT)) {
            val result = bind(root, catalog, metadata = metadata)
            assertNull(result.binding)
            assertEquals(expected, result.geometryFailure)
        }
        for (metadata in listOf(example.copy(userUnit = 2.0), example.copy(rotation = 90))) {
            val result = bind(root, catalog, metadata = metadata)
            assertNull(result.binding)
            assertEquals(PdfAnnotationGeometryFailure.PAGE_SIZE_MISMATCH, result.failure)
        }
    }

    @Test fun revalidationRejectsChangedRevisionOrSameSizeGeometry() {
        val page = page("p")
        val root = root(page)
        val catalog = catalog(root, PdfPageRef(page.id, source.sha256, 0))
        val initial = assertNotNull(bind(root, catalog).binding)
        val equivalent = PdfAnnotationGeometryRules.revalidate(root, catalog, "local", page.id,
            example.copy(rotation = 360), initial)
        assertEquals(initial, equivalent.binding)
        val owner = MemoryDocumentAuthority(root)
        val renamed = owner.commit(DocumentCommand.Rename(CommandId("rename"), root.id,
            root.revision, "renamed"))
        assertIs<DocumentCommit.Accepted>(renamed)
        val movedCrop = example.copy(crop = PdfCropBox(42.0, 50.0, 342.0, 230.0))
        for (result in listOf(
            PdfAnnotationGeometryRules.revalidate(owner.snapshot(), catalog, "local", page.id, example, initial),
            PdfAnnotationGeometryRules.revalidate(root, catalog.copy(revision = 1), "local", page.id, example, initial),
            PdfAnnotationGeometryRules.revalidate(root, catalog, "local", page.id, movedCrop, initial),
        )) {
            assertNull(result.binding)
            assertEquals(PdfAnnotationGeometryFailure.STALE_BINDING, result.failure)
        }
    }
}
