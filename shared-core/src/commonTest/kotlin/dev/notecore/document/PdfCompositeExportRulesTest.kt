package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PdfCompositeExportRulesTest {
    private val source = PdfSourcePages("ab".repeat(32), 4)
    private val crop = PdfCropBox(40.0, 50.0, 340.0, 230.0)

    private fun page(id: String, width: Double = 300.0, height: Double = 180.0) =
        PageDescriptor(PageId(id), LayerId("layer-$id"), width, height)

    private fun model(): Pair<DocumentRoot, PdfPageCatalog> {
        val pages = listOf(page("first"), page("blank", 200.0, 100.0), page("rotated", 180.0, 300.0))
        val root = DocumentRoot.compose("PDF", SceneSnapshot.empty(SceneId("pdf")), pages)
        val catalog = PdfPageCatalog("local", root.id, 7, true, listOf(source), listOf(
            PdfPageRef(pages[0].id, source.sha256, 0), PdfPageRef(pages[2].id, source.sha256, 1)))
        return root to catalog
    }

    private fun metadata(reference: PdfPageRef) = PdfSourcePageGeometry(source, reference.sourceIndex,
        crop, reference.sourceIndex * 90, 1.0)

    private fun request(
        ranges: List<PdfPageRange> = listOf(PdfPageRange(1, 3)),
        operation: CommandId = CommandId("export"),
        target: PdfCompositeTarget = PdfCompositeTarget.PDF_DOCUMENT,
        scale: Double = 2.0,
        maxPixels: Long = 300_000,
        retention: PdfCompositeRasterRetention = PdfCompositeRasterRetention.ONE_PAGE_AT_A_TIME,
        maxRetainedBytes: Long = Long.MAX_VALUE,
    ) = PdfCompositeExportRequest(operation, target, ranges, scale, maxPixels, retention, maxRetainedBytes)

    private fun plan(
        root: DocumentRoot,
        catalog: PdfPageCatalog,
        request: PdfCompositeExportRequest = request(),
        revision: Long = catalog.revision,
        resolver: (PdfPageRef) -> PdfSourcePageGeometry? = ::metadata,
    ) = PdfCompositeExportRules.plan(root, catalog, "local", revision, request, resolver)

    @Test fun rangesAreNormalizedWithoutChangingCurrentPageOrderOrDuplicatingTasks() {
        val pages = (0..4).map { page("p$it", 100.0, 100.0) }
        val root = DocumentRoot.compose("blank", SceneSnapshot.empty(SceneId("pdf")), pages)
        val catalog = PdfPageCatalog("local", root.id, 7, true, listOf(source), emptyList())
        val result = plan(root, catalog, request(
            ranges = listOf(PdfPageRange(4, 5), PdfPageRange(2, 3), PdfPageRange(2, 4)),
            target = PdfCompositeTarget.PAGE_IMAGES,
        )) { error("blank pages must not request PDF metadata") }
        val plan = assertNotNull(result.plan)
        assertEquals(listOf(PdfPageRange(2, 5)), plan.request.ranges)
        assertEquals(listOf("p1", "p2", "p3", "p4"), plan.tasks.map { it.page.id.value })
        assertEquals(listOf(1, 2, 3, 4), plan.tasks.map { it.outputNumber })
        assertEquals(listOf(2, 3, 4, 5), plan.tasks.map { it.displayNumber })
        assertTrue(plan.tasks.all { it.background == null })
        assertEquals(PdfCompositeTarget.PAGE_IMAGES, plan.request.target)
        assertFalse(plan.disclosure.editableObjects)
        assertFalse(plan.disclosure.searchableText)
        assertTrue(plan.disclosure.sourceReadOnly)
    }

    @Test fun sourceAndBlankPagesShareOneBoundedPlanWithIndependentGeometryAnchors() {
        val (root, catalog) = model()
        val plan = assertNotNull(plan(root, catalog).plan)
        assertEquals(3, plan.tasks.size)
        val first = plan.tasks[0]
        val blank = plan.tasks[1]
        val rotated = plan.tasks[2]
        assertNull(blank.background)
        assertEquals(0, assertNotNull(first.background).reference.sourceIndex)
        assertEquals(1, assertNotNull(rotated.background).reference.sourceIndex)
        assertEquals(PointD(16.0, 164.0), first.background!!.geometry.pdfToPage.map(PointD(56.0, 66.0)))
        assertEquals(PointD(16.0, 16.0), rotated.background!!.geometry.pdfToPage.map(PointD(56.0, 66.0)))
        assertEquals(PointD(56.0, 66.0), first.background!!.geometry.pageToPdf.map(PointD(16.0, 164.0)))
        assertEquals(600 to 360, first.pixelWidth to first.pixelHeight)
        assertEquals(400 to 200, blank.pixelWidth to blank.pixelHeight)
        assertEquals(360 to 600, rotated.pixelWidth to rotated.pixelHeight)
        assertEquals(864_000L, first.argbByteUpperBound)
        assertEquals(0, plan.documentRevision); assertEquals(7, plan.metadataRevision)
        assertEquals(catalog, plan.catalog); assertEquals(7, catalog.revision)
        assertFalse(plan.disclosure.editableObjects); assertFalse(plan.disclosure.searchableText)
        val blankOnly = assertNotNull(plan(root, catalog, request(ranges = listOf(PdfPageRange(2, 2)))) {
            error("unselected source pages must not be read")
        }.plan)
        assertEquals(listOf("blank"), blankOnly.tasks.map { it.page.id.value })
        assertNull(blankOnly.tasks.single().background)
    }

    @Test fun everyRotationUsesTheSameSourceAndAnnotationMappingForExport() {
        val expected = listOf(PointD(16.0, 164.0), PointD(16.0, 16.0),
            PointD(284.0, 16.0), PointD(164.0, 284.0))
        for (rotation in listOf(0, 90, 180, 270)) {
            val page = if (rotation % 180 == 0) page("p") else page("p", 180.0, 300.0)
            val root = DocumentRoot.compose("PDF", SceneSnapshot.empty(SceneId("pdf")), listOf(page))
            val catalog = PdfPageCatalog("local", root.id, 0, true, listOf(source),
                listOf(PdfPageRef(page.id, source.sha256, rotation / 90)))
            val result = plan(root, catalog, request(ranges = listOf(PdfPageRange(1, 1)), scale = 1.0)) { reference ->
                PdfSourcePageGeometry(source, reference.sourceIndex, crop, rotation, 1.0)
            }
            val task = assertNotNull(result.plan).tasks.single()
            val background = assertNotNull(task.background)
            assertEquals(expected[rotation / 90], background.geometry.pdfToPage.map(PointD(56.0, 66.0)))
            assertEquals(PointD(56.0, 66.0), background.geometry.pageToPdf.map(expected[rotation / 90]))
            assertEquals(page.width.toInt() to page.height.toInt(), task.pixelWidth to task.pixelHeight)
        }
    }

    @Test fun invalidRangesQualityBudgetAndOperationNeverProducePartialPlans() {
        val (root, catalog) = model()
        assertEquals(PdfCompositeExportFailure.EMPTY_SELECTION,
            plan(root, catalog, request(ranges = emptyList())).failure)
        for (range in listOf(PdfPageRange(0, 1), PdfPageRange(2, 1), PdfPageRange(1, 4))) {
            val result = plan(root, catalog, request(ranges = listOf(range)))
            assertNull(result.plan); assertEquals(PdfCompositeExportFailure.INVALID_RANGES, result.failure)
        }
        for (scale in listOf(0.0, -1.0, 0.2, 4.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            val result = plan(root, catalog, request(scale = scale))
            assertNull(result.plan); assertEquals(PdfCompositeExportFailure.INVALID_QUALITY, result.failure)
        }
        for (budget in listOf(0L, -1L, Long.MAX_VALUE)) {
            val result = plan(root, catalog, request(maxPixels = budget))
            assertNull(result.plan); assertEquals(PdfCompositeExportFailure.INVALID_PIXEL_BUDGET, result.failure)
        }
        val invalidOperation = plan(root, catalog, request(operation = CommandId("\uD800")))
        assertNull(invalidOperation.plan); assertEquals(PdfCompositeExportFailure.INVALID_OPERATION, invalidOperation.failure)
        val tooManyRanges = plan(root, catalog, request(ranges = List(4097) { PdfPageRange(1, 1) }))
        assertNull(tooManyRanges.plan); assertEquals(PdfCompositeExportFailure.RANGE_CAPACITY, tooManyRanges.failure)
        val pixelLimited = plan(root, catalog, request(maxPixels = 100_000))
        assertNull(pixelLimited.plan); assertEquals(PdfCompositeExportFailure.PIXEL_CAPACITY, pixelLimited.failure)
        assertEquals(3, root.pageCount); assertEquals(2, catalog.references.size)
    }

    @Test fun selectionCapacityIsCheckedBeforeAllocatingExportTasks() {
        val pages = (0..4096).map { page("p$it", 1.0, 1.0) }
        val root = DocumentRoot.compose("large", SceneSnapshot.empty(SceneId("pdf")), pages)
        val catalog = PdfPageCatalog("local", root.id, 0, true, listOf(source), emptyList())
        val result = plan(root, catalog, request(ranges = listOf(PdfPageRange(1, 4097)), maxPixels = 1)) {
            error("no source pages")
        }
        assertNull(result.plan); assertEquals(PdfCompositeExportFailure.PAGE_CAPACITY, result.failure)
        assertEquals(4097, root.pageCount)
    }

    @Test fun everySelectedPageFitsThePixelBudgetBeforeAnySourceMetadataIsRead() {
        val (root, catalog) = model()
        val largerBlank = root.page(1).copy(width = 350.0, height = 200.0)
        val expanded = DocumentRoot.compose(root.title, root.scene, listOf(root.page(0), largerBlank, root.page(2)))
        val reads = mutableListOf<PdfPageRef>()
        fun resolver(reference: PdfPageRef): PdfSourcePageGeometry {
            reads.add(reference)
            return metadata(reference)
        }

        val denied = plan(expanded, catalog, request(maxPixels = 279_999), resolver = ::resolver)
        assertNull(denied.plan)
        assertEquals(PdfCompositeExportFailure.PIXEL_CAPACITY, denied.failure)
        assertTrue(reads.isEmpty())

        val admitted = assertNotNull(plan(expanded, catalog, request(maxPixels = 280_000), resolver = ::resolver).plan)
        assertEquals(catalog.references, reads)
        assertEquals(1_120_000L, admitted.tasks[1].argbByteUpperBound)
    }

    @Test fun separatelyAccountsRetainingWriterAndPageAtATimeWriterBeforeMetadataReads() {
        val (root, catalog) = model()
        val blank = root.page(1).copy(height = 120.0)
        val sample = DocumentRoot.compose(root.title, root.scene, listOf(root.page(0), blank, root.page(2)))
        for ((mode, exact) in listOf(PdfCompositeRasterRetention.ONE_PAGE_AT_A_TIME to 864_000L,
                                     PdfCompositeRasterRetention.UNTIL_EXPORT_FINISHED to 2_112_000L)) {
            val denied = plan(sample, catalog, request(retention = mode, maxRetainedBytes = exact - 1)) {
                error("over-budget export must not read source metadata")
            }
            assertEquals(PdfCompositeExportFailure.RETAINED_ARGB_CAPACITY, denied.failure)
            assertNull(denied.plan)

            val accepted = assertNotNull(plan(sample, catalog, request(retention = mode, maxRetainedBytes = exact)).plan)
            assertEquals(exact, accepted.retainedArgbByteUpperBound)
            assertEquals(accepted, PdfCompositeExportRules.revalidate(accepted, accepted.operationId).plan)
            val tampered = accepted.copy(retainedArgbByteUpperBound = exact - 1)
            assertEquals(PdfCompositeExportFailure.STALE_PLAN,
                PdfCompositeExportRules.revalidate(tampered, tampered.operationId).failure)
        }
    }

    @Test fun metadataCallbackCannotChangeTheCatalogAndSelectionUsedByThePlan() {
        val (root, catalog) = model()
        val sources = catalog.sources.toMutableList()
        val references = catalog.references.toMutableList()
        val ranges = mutableListOf(PdfPageRange(1, 3))
        val reads = mutableListOf<PdfPageRef>()
        val planned = assertNotNull(plan(root, catalog.copy(sources = sources, references = references), request(ranges)) {
            reads.add(it)
            sources.clear()
            references.clear()
            ranges.clear()
            metadata(it)
        }.plan)

        assertEquals(catalog.references, reads)
        assertEquals(catalog, planned.catalog)
        assertEquals(listOf(PdfPageRange(1, 3)), planned.request.ranges)
        assertEquals(catalog.references, planned.tasks.mapNotNull { it.background?.reference })
        assertEquals(planned, PdfCompositeExportRules.revalidate(planned, planned.operationId).plan)
    }

    @Test fun missingChangedOrInvalidSourceGeometryRejectsTheWholePlan() {
        val (root, catalog) = model()
        val missing = plan(root, catalog) { null }
        assertNull(missing.plan); assertEquals(PdfCompositeExportFailure.MISSING_SOURCE_METADATA, missing.failure)
        val changed = plan(root, catalog) { reference -> metadata(reference).copy(source = source.copy(sha256 = "cd".repeat(32))) }
        assertNull(changed.plan); assertEquals(PdfCompositeExportFailure.SOURCE_GEOMETRY_REJECTED, changed.failure)
        assertEquals(PdfPageCatalogFailure.SOURCE_CHANGED, changed.catalogFailure)
        val wrongPage = plan(root, catalog) { reference -> metadata(reference).copy(sourceIndex = 3) }
        assertNull(wrongPage.plan); assertEquals(PdfAnnotationGeometryFailure.SOURCE_MISMATCH, wrongPage.annotationFailure)
        val invalid = plan(root, catalog) { reference -> metadata(reference).copy(crop = crop.copy(left = Double.NaN)) }
        assertNull(invalid.plan); assertEquals(PdfPageGeometryFailure.INVALID_BOX, invalid.geometryFailure)
        val wrongSize = plan(root, catalog) { reference -> metadata(reference).copy(userUnit = 2.0) }
        assertNull(wrongSize.plan); assertEquals(PdfAnnotationGeometryFailure.PAGE_SIZE_MISMATCH, wrongSize.annotationFailure)
        val stale = plan(root, catalog, revision = 8)
        assertNull(stale.plan); assertEquals(PdfPageCatalogFailure.STALE_METADATA, stale.catalogFailure)
        assertEquals(3, root.pageCount); assertEquals(2, catalog.references.size)
    }

    @Test fun snapshotSurvivesLaterDocumentEditsAndCancellationIsOperationScoped() {
        val (root, catalog) = model()
        val candidate = assertNotNull(plan(root, catalog).plan)
        val owner = MemoryDocumentAuthority(root)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Rename(CommandId("rename"), root.id, 0, "new title")))
        assertEquals(1, owner.snapshot().revision)
        assertEquals(0, candidate.root.revision); assertEquals("PDF", candidate.root.title)
        assertEquals(candidate, PdfCompositeExportRules.revalidate(candidate, CommandId("export")).plan)
        val cancelled = PdfCompositeExportRules.revalidate(candidate, CommandId("other"))
        assertNull(cancelled.plan); assertEquals(PdfCompositeExportFailure.CANCELLED, cancelled.failure)
        assertEquals(candidate, PdfCompositeExportRules.revalidate(candidate, CommandId("export")).plan)
        assertEquals(7, candidate.catalog.revision); assertEquals(3, candidate.tasks.size)
    }

    @Test fun revalidationRejectsTamperedTasksDisclosureAndMissingBackground() {
        val (root, catalog) = model()
        val candidate = assertNotNull(plan(root, catalog).plan)
        for (changed in listOf(
            candidate.copy(tasks = candidate.tasks.mapIndexed { index, task ->
                if (index == 0) task.copy(pixelWidth = task.pixelWidth + 1) else task
            }),
            candidate.copy(disclosure = candidate.disclosure.copy(editableObjects = true)),
            candidate.copy(disclosure = candidate.disclosure.copy(searchableText = true)),
            candidate.copy(request = candidate.request.copy(pixelsPerPageUnit = 1.0)),
        )) {
            val result = PdfCompositeExportRules.revalidate(changed, CommandId("export"))
            assertNull(result.plan); assertEquals(PdfCompositeExportFailure.STALE_PLAN, result.failure)
        }
        val missingBackground = candidate.copy(tasks = candidate.tasks.mapIndexed { index, task ->
            if (index == 0) task.copy(background = null) else task
        })
        assertNull(PdfCompositeExportRules.revalidate(missingBackground, CommandId("export")).plan)
        assertEquals(3, candidate.tasks.size); assertFalse(candidate.disclosure.editableObjects)
    }
}
