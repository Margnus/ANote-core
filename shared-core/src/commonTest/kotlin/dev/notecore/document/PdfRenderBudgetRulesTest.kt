package dev.notecore.document

import dev.notecore.canvas.prototype.LayerId
import dev.notecore.canvas.prototype.SceneId
import dev.notecore.canvas.prototype.SceneSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfRenderBudgetRulesTest {
    private val pages = (0..4).map { PageDescriptor(PageId("p$it"), LayerId("l$it"), 595.0, 842.0) }
    private val root = DocumentRoot.compose("pdf", SceneSnapshot.empty(SceneId("doc")), pages)
    private val source = PdfSourcePages("ab".repeat(32), 10)
    private val catalog = PdfPageCatalog(
        "local", root.id, 7, true, listOf(source),
        pages.mapIndexed { index, page -> PdfPageRef(page.id, source.sha256, index) },
    )
    private val current = pages[2].id
    private val next = PdfRenderRasterRequest(pages[3].id, 10, 5)
    private val previous = PdfRenderRasterRequest(pages[1].id, 5, 5)
    private val visible = PdfRenderRasterRequest(current, 10, 10)
    private val rasters = listOf(previous, next, visible)

    private fun selection(state: PdfPageCatalog = catalog, limit: Int = 2): PdfRenderPlan =
        assertNotNull(PdfRenderSelectionRules.plan(root, state, "local", state.revision, current, limit).plan)

    private fun admit(
        bytes: Long,
        sizes: List<PdfRenderRasterRequest> = rasters,
        state: PdfPageCatalog = catalog,
        activePage: PageId = current,
        candidate: PdfRenderPlan = selection(state),
    ): PdfRenderBudgetResult = PdfRenderBudgetRules.admit(root, state, "local", activePage, candidate, sizes, bytes)

    @Test
    fun exactBudgetAdmitsCurrentThenBothNeighborsRegardlessOfSizeInputOrder() {
        val sizes = rasters.toMutableList()
        val result = assertIs<PdfRenderBudgetResult.Admitted>(admit(700, sizes))
        sizes.clear()
        assertEquals(selection(), result.selection)
        assertEquals(listOf(visible, next, previous), result.tasks.map { it.raster })
        assertEquals(listOf(400L, 200L, 100L), result.tasks.map { it.packedBytes })
        assertEquals(700L, result.packedBytes)
        assertTrue(result.deferredPrefetch.isEmpty())
    }

    @Test
    fun currentPageShortByOneByteRejectsTheEntireBatch() {
        assertEquals(
            PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.CURRENT_PAGE_EXCEEDS_BUDGET),
            admit(399),
        )
        val exact = assertIs<PdfRenderBudgetResult.Admitted>(admit(400))
        assertEquals(listOf(visible), exact.tasks.map { it.raster })
        assertEquals(listOf(next.pageId, previous.pageId), exact.deferredPrefetch.map { it.pageId })
    }

    @Test
    fun neighborsUseRemainingBudgetInOrderAndAnOversizedNeighborDoesNotBlockASmallerOne() {
        val nextFits = assertIs<PdfRenderBudgetResult.Admitted>(admit(600))
        assertEquals(listOf(visible, next), nextFits.tasks.map { it.raster })
        assertEquals(listOf(previous.pageId), nextFits.deferredPrefetch.map { it.pageId })

        val onlyPreviousFits = assertIs<PdfRenderBudgetResult.Admitted>(admit(599))
        assertEquals(listOf(visible, previous), onlyPreviousFits.tasks.map { it.raster })
        assertEquals(listOf(next.pageId), onlyPreviousFits.deferredPrefetch.map { it.pageId })
        assertEquals(500L, onlyPreviousFits.packedBytes)
    }

    @Test
    fun blankCurrentPageUsesOnlyDirectPdfNeighborsAndAllowsZeroBudget() {
        val blank = catalog.copy(references = catalog.references.filterNot { it.pageId in listOf(current, next.pageId) })
        val withBudget = assertIs<PdfRenderBudgetResult.Admitted>(admit(100, listOf(previous), blank))
        assertEquals(listOf(previous), withBudget.tasks.map { it.raster })
        assertEquals(PdfRenderRole.PREFETCH, withBudget.tasks.single().task.role)
        val zero = assertIs<PdfRenderBudgetResult.Admitted>(admit(0, listOf(previous), blank))
        assertTrue(zero.tasks.isEmpty())
        assertEquals(listOf(previous.pageId), zero.deferredPrefetch.map { it.pageId })
        assertEquals(0L, zero.packedBytes)
        val disabled = assertIs<PdfRenderBudgetResult.Admitted>(
            admit(0, emptyList(), blank, candidate = selection(blank, limit = 0)),
        )
        assertTrue(disabled.tasks.isEmpty())
        assertTrue(disabled.deferredPrefetch.isEmpty())
    }

    @Test
    fun rejectsNegativeBudgetAndMissingDuplicateExtraOrInvalidRasters() {
        assertEquals(PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.INVALID_BUDGET), admit(-1))
        val unselected = next.copy(pageId = pages[4].id)
        for (badSizes in listOf(
            rasters.dropLast(1), rasters + unselected, listOf(visible, next, next),
            listOf(visible, next, unselected), listOf(visible, next, previous.copy(width = 0)),
            listOf(visible.copy(height = -1), next, previous),
        )) {
            assertEquals(
                PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.INVALID_RASTERS),
                admit(700, badSizes),
            )
        }
    }

    @Test
    fun oversizedIntDimensionsAndCombinedBytesCannotOverflowTheBudget() {
        val huge = visible.copy(width = Int.MAX_VALUE, height = Int.MAX_VALUE)
        assertEquals(
            PdfRenderBudgetResult.Rejected(PdfRenderBudgetFailure.CURRENT_PAGE_EXCEEDS_BUDGET),
            admit(Long.MAX_VALUE, listOf(huge, next, previous)),
        )
        val large = huge.copy(height = 1 shl 30)
        val result = assertIs<PdfRenderBudgetResult.Admitted>(
            admit(Long.MAX_VALUE, listOf(large, huge.copy(pageId = next.pageId), previous)),
        )
        assertEquals(listOf(large, previous), result.tasks.map { it.raster })
        assertEquals(listOf(next.pageId), result.deferredPrefetch.map { it.pageId })
        assertEquals(9_223_372_032_559_808_612L, result.packedBytes)
        val combined = assertIs<PdfRenderBudgetResult.Admitted>(
            admit(Long.MAX_VALUE, listOf(large, large.copy(pageId = next.pageId), previous)),
        )
        assertEquals(result.tasks, combined.tasks)
        assertEquals(result.deferredPrefetch, combined.deferredPrefetch)
    }

    @Test
    fun obsoletePageMetadataAndForgedSourceTasksAreRejectedBeforeAnyBudgetIsPlanned() {
        val candidate = selection()
        val forged = candidate.copy(tasks = candidate.tasks.map { it.copy(sourceIndex = 0) })
        for (result in listOf(
            admit(700, activePage = pages[1].id, candidate = candidate),
            admit(700, state = catalog.copy(revision = 8), candidate = candidate),
            admit(700, candidate = forged),
        )) {
            assertEquals(PdfRenderSelectionFailure.STALE_PLAN, assertIs<PdfRenderBudgetResult.SelectionRejected>(result).result.failure)
        }
        val invalidCatalog = assertIs<PdfRenderBudgetResult.SelectionRejected>(
            admit(700, state = catalog.copy(complete = false), candidate = candidate),
        )
        assertEquals(PdfPageCatalogFailure.INCOMPLETE, invalidCatalog.result.catalogFailure)
    }
}
