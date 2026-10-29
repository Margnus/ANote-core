package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PdfRenderSelectionRulesTest {
    private val pages = (0..4).map { PageDescriptor(PageId("p$it"), LayerId("l$it"), 595.0, 842.0) }
    private val root = DocumentRoot.compose("pdf", SceneSnapshot.empty(SceneId("doc")), pages)
    private val source = PdfSourcePages("ab".repeat(32), 10)
    private val catalog = PdfPageCatalog("local", root.id, 7, true, listOf(source),
        pages.mapIndexed { index, page -> PdfPageRef(page.id, source.sha256, index + 2) })
    private fun plan(current: Int, limit: Int = 2, s: PdfPageCatalog = catalog) =
        assertNotNull(PdfRenderSelectionRules.plan(root, s, "local", 7, pages[current].id, limit).plan)

    @Test fun currentComesFirstAndOnlyTwoImmediateNeighborsAreSelected() {
        val plan = plan(2)
        assertEquals(listOf("p2", "p3", "p1"), plan.tasks.map { it.pageId.value })
        assertEquals(listOf(4, 5, 3), plan.tasks.map { it.sourceIndex })
        assertEquals(listOf(PdfRenderRole.CURRENT, PdfRenderRole.PREFETCH, PdfRenderRole.PREFETCH), plan.tasks.map { it.role })
        assertEquals(root.id, plan.documentId); assertEquals(0, plan.documentRevision); assertEquals(7, plan.metadataRevision)
        assertNotNull(PdfRenderSelectionRules.validate(root, catalog, "local", pages[2].id, plan).plan)
        assertEquals(5, root.pageCount); assertEquals(5, catalog.references.size)
    }

    @Test fun firstLastAndReducedBudgetsNeverFetchBeyondAdjacency() {
        assertEquals(listOf("p0", "p1"), plan(0).tasks.map { it.pageId.value })
        assertEquals(listOf("p4", "p3"), plan(4).tasks.map { it.pageId.value })
        assertEquals(listOf("p2"), plan(2, 0).tasks.map { it.pageId.value })
        assertEquals(listOf("p2", "p3"), plan(2, 1).tasks.map { it.pageId.value })
    }

    @Test fun blankPagesAreSkippedWithoutSearchingFartherAndDuplicateSourcePagesStayDistinct() {
        val blankNeighbors = catalog.copy(references = catalog.references.filterNot { it.pageId in setOf(pages[1].id, pages[3].id) })
        assertEquals(listOf("p2"), plan(2, s = blankNeighbors).tasks.map { it.pageId.value })
        val blankCurrent = catalog.copy(references = catalog.references.filterNot { it.pageId == pages[2].id })
        assertEquals(listOf("p3", "p1"), plan(2, s = blankCurrent).tasks.map { it.pageId.value })
        assertTrue(plan(2, 0, blankCurrent).tasks.isEmpty())
        val duplicated = catalog.copy(references = catalog.references.map { it.copy(sourceIndex = 0) })
        val tasks = plan(2, s = duplicated).tasks
        assertEquals(3, tasks.map { it.pageId }.toSet().size); assertTrue(tasks.all { it.sourceIndex == 0 })
    }

    @Test fun pageSwitchReorderAndEitherRevisionChangeInvalidateOldPlan() {
        val candidate = plan(2)
        assertEquals(PdfRenderSelectionFailure.STALE_PLAN,
            PdfRenderSelectionRules.validate(root, catalog, "local", pages[3].id, candidate).failure)
        val owner = MemoryDocumentAuthority(root)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.MovePage(CommandId("move"), root.id, 0, pages[3].id, pages[0].id)))
        assertEquals(PdfRenderSelectionFailure.STALE_PLAN,
            PdfRenderSelectionRules.validate(owner.snapshot(), catalog, "local", pages[2].id, candidate).failure)
        assertEquals(PdfRenderSelectionFailure.STALE_PLAN,
            PdfRenderSelectionRules.validate(root, catalog.copy(revision = 8), "local", pages[2].id, candidate).failure)
        val changed = catalog.copy(references = catalog.references.map { if (it.pageId == pages[2].id) it.copy(sourceIndex = 9) else it })
        assertEquals(PdfRenderSelectionFailure.STALE_PLAN,
            PdfRenderSelectionRules.validate(root, changed, "local", pages[2].id, candidate).failure)
    }

    @Test fun malformedCatalogMissingPageAndInvalidLimitsNeverReturnTasks() {
        for (limit in listOf(-1, 3)) {
            val result = PdfRenderSelectionRules.plan(root, catalog, "local", 7, pages[2].id, limit)
            assertNull(result.plan); assertEquals(PdfRenderSelectionFailure.INVALID_PREFETCH_LIMIT, result.failure)
        }
        val missing = PdfRenderSelectionRules.plan(root, catalog, "local", 7, PageId("missing"))
        assertNull(missing.plan); assertEquals(PdfRenderSelectionFailure.MISSING_PAGE, missing.failure)
        for ((state, reason) in listOf(catalog.copy(complete = false) to PdfPageCatalogFailure.INCOMPLETE,
            catalog.copy(spaceId = "other") to PdfPageCatalogFailure.WRONG_SPACE,
            catalog.copy(revision = 8) to PdfPageCatalogFailure.STALE_METADATA,
            catalog.copy(references = listOf(catalog.references.first().copy(sourceIndex = 10))) to PdfPageCatalogFailure.INVALID_REFERENCES)) {
            val result = PdfRenderSelectionRules.plan(root, state, "local", 7, pages[2].id)
            assertNull(result.plan); assertEquals(reason, result.catalogFailure)
        }
    }

    @Test fun modifiedTaskPayloadAndForeignPlanCannotPassRevalidation() {
        val original = plan(2)
        for (candidate in listOf(original.copy(spaceId = "other"), original.copy(documentId = SceneId("other")),
            original.copy(tasks = original.tasks.reversed()),
            original.copy(tasks = original.tasks.map { it.copy(sourceSha256 = "cd".repeat(32)) }))) {
            assertEquals(PdfRenderSelectionFailure.STALE_PLAN,
                PdfRenderSelectionRules.validate(root, catalog, "local", pages[2].id, candidate).failure)
        }
        assertEquals(listOf(source), catalog.sources)
        assertEquals(0, root.scene.objectCount)
    }
}
