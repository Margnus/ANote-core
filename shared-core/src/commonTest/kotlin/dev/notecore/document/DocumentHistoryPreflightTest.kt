package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class DocumentHistoryPreflightTest {
    private val doc = SceneId("doc")
    private val page = PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)
    private val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)
    private val samples = StrokeSamples.copyOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 3.0), floatArrayOf(.5f, .5f), longArrayOf(0, 1))
    private fun root() = DocumentRoot.empty(doc, "title", page)
    private fun add(revision: Long = 0) = DocumentCommand.Canvas(page.id,
        CanvasCommand.AddStroke(CommandId("add"), revision, StrokeTarget(doc, page.layerId, brush), ObjectId("ink"), samples))

    @Test fun exactIndependentHistoryBoundaryRemains1704BytesForTwoPointStroke() {
        val change = ObjectChange(null, StrokeObject(ObjectId("ink"), 1, page.layerId, brush, samples))
        val projected = DocumentDelta.projectedCharge(root(), "title", listOf(page.copy(contentRevision = 1)), listOf(change), setOf(page.id))
        // 512 + titles20 + orders112 + affected56 + changed descriptors484 + object520.
        assertEquals(1704L, projected)
        val owner = MemoryDocumentAuthority(root(), DocumentBudget(historyBytes = 1704))
        assertIs<DocumentCommit.Accepted>(owner.commit(add()))
        assertEquals(1704L, owner.status().historyByteCharge)
    }
    @Test fun oversizedHistoryRefusesAtomicallyAndDoesNotConsumeOperationIdentity() {
        val owner = MemoryDocumentAuthority(root(), DocumentBudget(historyBytes = 1703))
        val before = owner.snapshot(); val status = owner.status()
        repeat(3) {
            assertEquals(DocumentRejection.HISTORY_CAPACITY, assertIs<DocumentCommit.Rejected>(owner.commit(add())).reason)
            assertSame(before, owner.snapshot()); assertEquals(status, owner.status())
        }
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Rename(CommandId("add"), doc, 0, "a")))
        assertEquals(1, owner.status().operationCount)
    }
    @Test fun externalPreparedPatchStillReportsStaleObjectBeforeHistoryCapacity() {
        val original = StrokeObject(ObjectId("ink"), 1, page.layerId, brush, samples)
        val scene = SceneSnapshot.restored(doc, 1, listOf(original))
        val owner = MemoryDocumentAuthority(DocumentRoot.compose("title", scene, listOf(page.copy(contentRevision = 1))), DocumentBudget(historyBytes = 0))
        val other = SceneSnapshot.restored(doc, 1, listOf(original.copy(brush = brush.copy(argb = 123))))
        val action = CanvasCommand.TransformStroke(CommandId("foreign"), doc, 1, original.id, 1, Affine2D(tx = 1.0))
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(other, action)).patch
        val before = owner.snapshot()
        val rejection = assertIs<DocumentCommit.Rejected>(owner.commit(DocumentCommand.PreparedCanvas(page.id, patch)))
        assertEquals(DocumentRejection.SCENE_REJECTED, rejection.reason); assertEquals(Rejection.STALE_OBJECT, rejection.sceneReason)
        assertSame(before, owner.snapshot()); assertEquals(0, owner.status().operationCount)
    }
    @Test fun projectedChargeMatchesActualHistoryAcrossMetadataAndMembershipEdits() {
        val owner = MemoryDocumentAuthority(root(), DocumentBudget(historyEntries = 1))
        fun apply(command: DocumentCommand) {
            val before = owner.snapshot()
            val accepted = assertIs<DocumentCommit.Accepted>(owner.commit(command))
            val after = owner.snapshot()
            val left = before.scene.objects().associateBy { it.id }; val right = after.scene.objects().associateBy { it.id }
            val changes = (left.keys + right.keys).mapNotNull { id -> if (left[id] == right[id]) null else ObjectChange(left[id], right[id]) }
            val projected = DocumentDelta.projectedCharge(before, after.title, after.pages(), changes, accepted.receipt.affectedPages())
            assertEquals(projected, owner.status().historyByteCharge); assertEquals(1, owner.status().undoCount)
        }
        apply(DocumentCommand.Rename(CommandId("rename"), doc, 0, "new title"))
        val second = PageDescriptor(PageId("second"), LayerId("second-layer"), 600.0, 800.0)
        apply(DocumentCommand.InsertPage(CommandId("insert"), doc, 1, second, null))
        apply(add(2))
        apply(DocumentCommand.CopyPage(CommandId("copy"), doc, 3, page.id, owner.snapshot().page(0).contentRevision,
            PageId("copy"), LayerId("copy-layer"), mapOf(ObjectId("ink") to ObjectId("ink-copy")), null))
        apply(DocumentCommand.MovePage(CommandId("move"), doc, 4, PageId("copy"), page.id))
        apply(DocumentCommand.SetPaper(CommandId("paper"), doc, 5, page.id, 700.0, 800.0, PaperKind.GRID))
        apply(DocumentCommand.DeletePage(CommandId("delete"), doc, 6, PageId("copy")))
    }
}
