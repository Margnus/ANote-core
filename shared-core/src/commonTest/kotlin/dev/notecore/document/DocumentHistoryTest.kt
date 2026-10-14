package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class DocumentHistoryTest {
    private val doc = SceneId("history-doc")
    private val a = PageDescriptor(PageId("a"), LayerId("ink-a"), 595.0, 842.0, PaperKind.RULED)
    private val b = PageDescriptor(PageId("b"), LayerId("ink-b"), 595.0, 842.0)
    private val ink = ObjectId("ink")
    private fun authority(budget: DocumentBudget = DocumentBudget()) = MemoryDocumentAuthority(DocumentRoot.empty(doc, "title", a), budget)
    private fun accept(owner: MemoryDocumentAuthority, command: DocumentCommand) = assertIs<DocumentCommit.Accepted>(owner.commit(command)).receipt
    private fun reject(owner: MemoryDocumentAuthority, command: DocumentCommand, reason: DocumentRejection) {
        val root = owner.snapshot(); val stats = owner.status()
        assertEquals(reason, assertIs<DocumentCommit.Rejected>(owner.commit(command)).reason)
        assertSame(root, owner.snapshot()); assertEquals(stats, owner.status())
    }
    private fun undo(owner: MemoryDocumentAuthority): DocumentCommand.Undo = DocumentCommand.Undo(
        CommandId("undo-${owner.snapshot().revision}"), doc, owner.snapshot().revision, owner.status().nextUndoOperationId!!)
    private fun redo(owner: MemoryDocumentAuthority): DocumentCommand.Redo = DocumentCommand.Redo(
        CommandId("redo-${owner.snapshot().revision}"), doc, owner.snapshot().revision, owner.status().nextRedoOperationId!!)
    private fun rename(owner: MemoryDocumentAuthority, title: String) = DocumentCommand.Rename(CommandId("name-${owner.snapshot().revision}"), doc, owner.snapshot().revision, title)
    private fun add(owner: MemoryDocumentAuthority): DocumentCommand.Canvas = DocumentCommand.Canvas(a.id, CanvasCommand.AddStroke(
        CommandId("add"), owner.snapshot().revision,
        StrokeTarget(doc, a.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, 0xff000000.toInt())), ink,
        StrokeSamples.copyOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0), floatArrayOf(0.5f, 0.7f), longArrayOf(1, 2))))
    private fun semantic(root: DocumentRoot): List<Any> = listOf(root.title, root.pages().map { it.copy(contentRevision = 0) },
        root.scene.objects().sortedBy { it.id.value }.map { it.copy(objectRevision = 1) })

    @Test fun mixedPageAndCanvasEditsRoundTripWithoutRewindingRootOrContentVersions() {
        val owner = authority(); val initial = semantic(owner.snapshot())
        accept(owner, add(owner))
        accept(owner, DocumentCommand.InsertPage(CommandId("insert"), doc, 1, b))
        accept(owner, DocumentCommand.MovePage(CommandId("move"), doc, 2, b.id, a.id))
        accept(owner, DocumentCommand.SetPaper(CommandId("paper"), doc, 3, a.id, 612.0, 792.0, PaperKind.DOTS))
        accept(owner, rename(owner, "edited"))
        val edited = semantic(owner.snapshot()); val retained = owner.snapshot()
        val sourceSamples = retained.scene.find(ink)!!.samples
        repeat(5) { accept(owner, undo(owner)) }
        assertEquals(initial, semantic(owner.snapshot())); assertEquals(10L, owner.snapshot().revision)
        assertEquals(0, owner.status().undoCount); assertEquals(5, owner.status().redoCount)
        repeat(5) { accept(owner, redo(owner)) }
        assertEquals(edited, semantic(owner.snapshot())); assertEquals(15L, owner.snapshot().revision)
        assertSame(sourceSamples, owner.snapshot().scene.find(ink)!!.samples)
        assertEquals(edited, semantic(retained)); assertEquals(5L, retained.revision)
        assertEquals(5, owner.status().undoCount); assertEquals(0, owner.status().redoCount)
    }

    @Test fun copyUndoRedoRestoresTheSameIdsAndSamplesWithFreshLifecycleEpochs() {
        val owner = authority(); accept(owner, add(owner))
        val source = owner.snapshot().scene.find(ink)!!; val copiedId = ObjectId("copy-ink")
        val copy = DocumentCommand.CopyPage(CommandId("copy"), doc, 1, a.id, 1, b.id, b.layerId, mapOf(ink to copiedId))
        val originalReceipt = accept(owner, copy)
        assertEquals(1L, owner.snapshot().scene.find(copiedId)!!.objectRevision)
        val historyCharge = owner.status().historyByteCharge
        repeat(3) {
            accept(owner, undo(owner)); assertNull(owner.snapshot().findPage(b.id)); assertNull(owner.snapshot().scene.find(copiedId))
            accept(owner, redo(owner)); val restored = owner.snapshot().scene.find(copiedId)!!
            assertEquals(3L + it * 2, restored.objectRevision); assertEquals(b.layerId, restored.layerId)
            assertSame(source.samples, restored.samples); assertEquals(historyCharge, owner.status().historyByteCharge)
        }
        assertSame(source, owner.snapshot().scene.find(ink))
        assertSame(originalReceipt, accept(owner, copy)); assertEquals(8L, owner.snapshot().revision)
    }

    @Test fun pageDeletionUndoRestoresMembershipOrderAndRedoDoesNotAllocateNewIdentity() {
        val owner = authority(); accept(owner, add(owner)); accept(owner, DocumentCommand.InsertPage(CommandId("insert"), doc, 1, b, a.id))
        val before = semantic(owner.snapshot())
        accept(owner, DocumentCommand.DeletePage(CommandId("delete"), doc, 2, a.id))
        assertEquals(listOf(b.id), owner.snapshot().pages().map { it.id })
        accept(owner, undo(owner)); assertEquals(before, semantic(owner.snapshot()))
        assertEquals(3L, owner.snapshot().scene.find(ink)!!.objectRevision)
        accept(owner, redo(owner)); assertNull(owner.snapshot().scene.find(ink))
        accept(owner, undo(owner)); assertEquals(5L, owner.snapshot().scene.find(ink)!!.objectRevision)
        assertEquals(before, semantic(owner.snapshot()))
    }

    @Test fun clearingLastPageUndoRestoresPaperAndInkWhileKeepingPageAndLayerIds() {
        val owner = authority(); accept(owner, add(owner)); val before = semantic(owner.snapshot())
        accept(owner, DocumentCommand.DeletePage(CommandId("clear"), doc, 1, a.id))
        assertEquals(PaperKind.BLANK, owner.snapshot().page(0).paper); assertEquals(0, owner.snapshot().scene.objectCount)
        accept(owner, undo(owner)); assertEquals(before, semantic(owner.snapshot()))
        assertEquals(owner.snapshot().revision, owner.snapshot().page(0).contentRevision)
        accept(owner, redo(owner)); assertEquals(a.id, owner.snapshot().page(0).id); assertEquals(a.layerId, owner.snapshot().page(0).layerId)
        assertEquals(0, owner.snapshot().scene.objectCount)
    }

    @Test fun transformUndoRedoAndDeleteRestorationAlwaysInvalidateOldObjectVersions() {
        val owner = authority(); accept(owner, add(owner))
        val transform = DocumentCommand.Canvas(a.id, CanvasCommand.TransformStroke(CommandId("transform"), doc, 1, ink, 1, Affine2D(tx = 10.0)))
        accept(owner, transform); accept(owner, undo(owner))
        assertEquals(Affine2D(), owner.snapshot().scene.find(ink)!!.transform); assertEquals(3L, owner.snapshot().scene.find(ink)!!.objectRevision)
        accept(owner, redo(owner)); assertEquals(4L, owner.snapshot().scene.find(ink)!!.objectRevision)
        val stale = DocumentCommand.Canvas(a.id, CanvasCommand.TransformStroke(CommandId("late-object"), doc, 4, ink, 2, Affine2D(tx = 20.0)))
        val result = assertIs<DocumentCommit.Rejected>(owner.commit(stale)); assertEquals(Rejection.STALE_OBJECT, result.sceneReason)
        accept(owner, DocumentCommand.Canvas(a.id, CanvasCommand.DeleteStroke(CommandId("delete"), doc, 4, ink, 4)))
        accept(owner, undo(owner)); assertEquals(6L, owner.snapshot().scene.find(ink)!!.objectRevision)
        assertEquals(Affine2D(tx = 10.0), owner.snapshot().scene.find(ink)!!.transform)
    }

    @Test fun successfulNewEditClearsRedoButRejectedEditsAndRetryReceiptsDoNot() {
        val owner = authority(); val command = add(owner); val receipt = accept(owner, command)
        accept(owner, undo(owner)); val retained = owner.status()
        reject(owner, rename(owner, "title"), DocumentRejection.NO_CHANGE)
        assertSame(receipt, accept(owner, command)); assertEquals(retained, owner.status())
        reject(owner, DocumentCommand.Canvas(a.id, (command.command as CanvasCommand.AddStroke).copy(id = CommandId("reuse"), baseRevision = 2)), DocumentRejection.REUSED_IDENTITY)
        accept(owner, rename(owner, "branch")); assertNull(owner.status().nextRedoOperationId); assertEquals(0, owner.status().redoCount)
        reject(owner, DocumentCommand.Redo(CommandId("lost-branch"), doc, 3, command.id), DocumentRejection.HISTORY_EMPTY)
    }

    @Test fun staleForeignAndWrongTopHistoryCommandsCannotMoveStacksOrConsumeReceipts() {
        val owner = authority(); accept(owner, add(owner)); accept(owner, rename(owner, "new"))
        val valid = undo(owner)
        reject(owner, valid.copy(expectedOperationId = CommandId("add")), DocumentRejection.HISTORY_TARGET)
        reject(owner, valid.copy(baseRevision = 1), DocumentRejection.STALE_ROOT)
        reject(owner, valid.copy(documentId = SceneId("other")), DocumentRejection.WRONG_DOCUMENT)
        val receipt = accept(owner, valid); accept(owner, undo(owner))
        assertSame(receipt, accept(owner, valid))
        reject(owner, valid.copy(expectedOperationId = CommandId("different")), DocumentRejection.REUSED_OPERATION_ID)
        reject(owner, DocumentCommand.Undo(CommandId("empty"), doc, 4, CommandId("add")), DocumentRejection.HISTORY_EMPTY)
    }

    @Test fun entryHorizonEvictsOnlyOldestUndoAndDoesNotRetireReceiptIdentity() {
        val owner = authority(DocumentBudget(historyEntries = 2)); val first = rename(owner, "first"); val receipt = accept(owner, first)
        accept(owner, rename(owner, "second")); accept(owner, rename(owner, "third"))
        assertEquals(2, owner.status().undoCount); assertEquals(1L, owner.status().evictedHistoryCount)
        accept(owner, undo(owner)); accept(owner, undo(owner)); assertEquals("first", owner.snapshot().title)
        assertSame(receipt, accept(owner, first)); assertEquals("first", owner.snapshot().title)
        reject(owner, DocumentCommand.Undo(CommandId("horizon"), doc, 5, first.id), DocumentRejection.HISTORY_EMPTY)
        repeat(2) { accept(owner, redo(owner)) }; assertEquals("third", owner.snapshot().title)
        assertEquals(1L, owner.status().evictedHistoryCount)
    }

    @Test fun byteHorizonIncludesRedoAndOversizedEditLeavesExistingBranchUntouched() {
        val probe = authority(); accept(probe, rename(probe, "first")); val singleCharge = probe.status().historyByteCharge
        val owner = authority(DocumentBudget(historyBytes = singleCharge + 16)); accept(owner, rename(owner, "first"))
        accept(owner, rename(owner, "second")); assertEquals(1, owner.status().undoCount); assertEquals(1L, owner.status().evictedHistoryCount)
        accept(owner, undo(owner)); val before = owner.status(); assertEquals(1, before.redoCount)
        val large = DocumentCommand.Rename(CommandId("large"), doc, owner.snapshot().revision, "x".repeat(120))
        reject(owner, large, DocumentRejection.HISTORY_CAPACITY)
        assertEquals(before, owner.status()); accept(owner, redo(owner)); assertEquals("second", owner.snapshot().title)
        val emptyBudget = authority(DocumentBudget(historyBytes = 0)); val owned = add(emptyBudget)
        reject(emptyBudget, owned, DocumentRejection.HISTORY_CAPACITY)
        assertEquals(2, (owned.command as CanvasCommand.AddStroke).samples.size)
    }

    @Test fun journalFullBlocksTraversalAtomicallyButOriginalUndoReceiptStillRetries() {
        val owner = authority(DocumentBudget(journalEntries = 2)); accept(owner, add(owner)); val command = undo(owner); val receipt = accept(owner, command)
        reject(owner, redo(owner), DocumentRejection.JOURNAL_CAPACITY)
        assertSame(receipt, accept(owner, command)); assertEquals(1, owner.status().redoCount)
    }

    @Test fun exhaustedObjectEpochBlocksDeletionWithoutPublishingMetadataOrHistory() {
        val populated = authority(); accept(populated, add(populated)); val max = populated.snapshot().scene.find(ink)!!.copy(objectRevision = Long.MAX_VALUE)
        val owner = MemoryDocumentAuthority(DocumentRoot.compose("title", SceneSnapshot.restored(doc, 1, listOf(max)), listOf(a.copy(contentRevision = 1))))
        reject(owner, DocumentCommand.DeletePage(CommandId("delete-max"), doc, 1, a.id), DocumentRejection.REVISION_EXHAUSTED)
        reject(owner, DocumentCommand.Canvas(a.id, CanvasCommand.DeleteStroke(CommandId("delete-stroke-max"), doc, 1, ink, Long.MAX_VALUE)), DocumentRejection.REVISION_EXHAUSTED)
    }

    @Test fun preparedPatchRetryAfterUndoReturnsOriginalReceiptAndLateNewOperationCannotResurrectInk() {
        val owner = authority(); val action = add(owner).command
        val original = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(owner.snapshot().scene, action)).patch
        val prepared = DocumentCommand.PreparedCanvas(a.id, original); val receipt = accept(owner, prepared)
        accept(owner, undo(owner)); assertSame(receipt, accept(owner, prepared)); assertEquals(0, owner.snapshot().scene.objectCount)
        val different = (action as CanvasCommand.AddStroke).copy(id = CommandId("late"))
        val late = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(DocumentRoot.empty(doc, "title", a).scene, different)).patch
        reject(owner, DocumentCommand.PreparedCanvas(a.id, late), DocumentRejection.STALE_ROOT)
        val retargeted = different.copy(id = CommandId("new-base"), baseRevision = 2)
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(owner.snapshot().scene, retargeted)).patch
        reject(owner, DocumentCommand.PreparedCanvas(a.id, patch), DocumentRejection.REUSED_IDENTITY)
    }
}
