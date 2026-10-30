package dev.notecore.document

import dev.notecore.canvas.prototype.*
import dev.notecore.canvas.preview.*
import kotlin.test.*

class ObjectOrderRulesTest {
    private val a = PageDescriptor(PageId("page-a"), LayerId("layer-a"), 595.0, 842.0)
    private val b = PageDescriptor(PageId("page-b"), LayerId("layer-b"), 595.0, 842.0)
    private val paper = PageDescriptor(PageId("paper-only"), LayerId("paper-layer"), 595.0, 842.0, PaperKind.GRID)
    private fun ink(id: String, layer: LayerId) = StrokeObject(ObjectId(id), 1, layer,
        BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1),
        StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1)))
    private val root = DocumentRoot.compose("order", SceneSnapshot.restored(SceneId("order-doc"), 3,
        listOf(ink("a1", a.layerId), ink("b1", b.layerId), ink("a2", a.layerId), ink("b2", b.layerId), ink("a3", a.layerId))),
        listOf(a, b, paper))
    private fun order(vararg ids: String) = ids.map(::ObjectId)
    private fun plan(selected: List<ObjectId>, direction: ObjectOrderDirection = ObjectOrderDirection.FRONT) =
        ObjectOrderRules.plan(root, root.id, root.revision, a.id, selected, direction, 5)

    @Test fun stableFrontAndBackKeepSelectionOrderAndOtherPageSlots() {
        val before = PagedPreviewCodec.encode(root)
        assertEquals(order("a2", "b1", "a1", "b2", "a3"), assertNotNull(plan(order("a3", "a1")).plan).objectIds())
        assertEquals(order("a1", "b1", "a3", "b2", "a2"),
            assertNotNull(plan(order("a3", "a1"), ObjectOrderDirection.BACK).plan).objectIds())
        assertEquals(before, PagedPreviewCodec.encode(root))
    }

    @Test fun selectedObjectsAndResultsCannotAliasCallerLists() {
        val selected = order("a1").toMutableList()
        val result = assertNotNull(plan(selected).plan)
        selected.clear()
        val exposed = result.objectIds() as MutableList<ObjectId>; exposed.reverse()
        assertEquals(order("a2", "b1", "a3", "b2", "a1"), result.objectIds())
        assertEquals(root.id, result.documentId); assertEquals(root.revision, result.expectedRootRevision)
    }

    @Test fun duplicateMissingCrossPageAndPaperSelectionsRefuseWholePlan() {
        for ((selected, expected) in listOf(
            emptyList<ObjectId>() to ObjectOrderFailure.INVALID_SELECTION,
            order("a1", "a1") to ObjectOrderFailure.INVALID_SELECTION,
            order("a1", "missing") to ObjectOrderFailure.MISSING_OBJECT,
            order("a1", "b1") to ObjectOrderFailure.WRONG_PAGE,
            order(paper.id.value) to ObjectOrderFailure.MISSING_OBJECT,
            order("\uD800") to ObjectOrderFailure.INVALID_SELECTION)) {
            val result = plan(selected); assertNull(result.plan); assertEquals(expected, result.failure)
        }
        assertEquals(5, root.scene.objectCount); assertEquals(PaperKind.GRID, root.findPage(paper.id)!!.paper)
    }

    @Test fun admissionChecksDocumentRevisionPageAndCountBeforePlanning() {
        assertEquals(ObjectOrderFailure.WRONG_DOCUMENT,
            ObjectOrderRules.plan(root, SceneId("other"), 3, a.id, order("a1"), ObjectOrderDirection.FRONT, 5).failure)
        assertEquals(ObjectOrderFailure.STALE_ROOT,
            ObjectOrderRules.plan(root, root.id, 2, a.id, order("a1"), ObjectOrderDirection.FRONT, 5).failure)
        assertEquals(ObjectOrderFailure.MISSING_PAGE,
            ObjectOrderRules.plan(root, root.id, 3, PageId("missing"), order("a1"), ObjectOrderDirection.FRONT, 5).failure)
        assertEquals(ObjectOrderFailure.INVALID_BUDGET,
            ObjectOrderRules.plan(root, root.id, 3, a.id, order("a1"), ObjectOrderDirection.FRONT, 0).failure)
        assertEquals(ObjectOrderFailure.CAPACITY,
            ObjectOrderRules.plan(root, root.id, 3, a.id, order("a1"), ObjectOrderDirection.FRONT, 4).failure)
    }

    @Test fun noChangeProducesNoPlanAndNewRootInvalidatesOldProposal() {
        assertEquals(ObjectOrderFailure.NO_CHANGE, plan(order("a3")).failure)
        assertEquals(ObjectOrderFailure.NO_CHANGE, plan(order("a1"), ObjectOrderDirection.BACK).failure)
        assertEquals(ObjectOrderFailure.NO_CHANGE, plan(order("a3", "a2", "a1")).failure)
        val proposal = assertNotNull(plan(order("a1")).plan)
        assertNull(ObjectOrderRules.revalidate(root, proposal))
        val note = PagedPreviewNotebook(root, "order-owner")
        assertTrue(note.rename("changed"))
        assertEquals(ObjectOrderFailure.STALE_ROOT, ObjectOrderRules.revalidate(note.snapshot(), proposal))
    }

    @Test fun existingFormatKeepsProposedOrderButLedgerRejectsUnversionedFork() {
        val proposal = assertNotNull(plan(order("a1")).plan)
        // Fixture composition only: deliberately not a new authority commit or undo implementation.
        val fixture = DocumentRoot.compose(root.title, SceneSnapshot.reordered(root.scene, proposal.objectIds()), root.pages())
        val decoded = PagedPreviewCodec.decode(PagedPreviewCodec.encode(fixture))
        assertEquals(proposal.objectIds(), decoded.scene.objects().map { it.id })
        assertEquals(root.pages(), decoded.pages()); assertEquals(root.revision, decoded.revision)
        assertFalse(root.scene.sameContent(decoded.scene))
        val ledger = PreviewSaveLedger()
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(PagedPreviewSnapshot(root)))
        assertEquals(PreviewSaveAdmission.VERSION_CONFLICT, ledger.offer(PagedPreviewSnapshot(fixture)))
    }
}
