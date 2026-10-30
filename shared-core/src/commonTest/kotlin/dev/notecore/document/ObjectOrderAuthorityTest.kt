package dev.notecore.document

import dev.notecore.canvas.prototype.*
import dev.notecore.canvas.preview.*
import kotlin.test.*

class ObjectOrderAuthorityTest {
    private val a = PageDescriptor(PageId("a"), LayerId("la"), 595.0, 842.0)
    private val b = PageDescriptor(PageId("b"), LayerId("lb"), 595.0, 842.0)
    private val samples = StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))
    private fun ink(id: String, layer: LayerId) = StrokeObject(ObjectId(id), 1, layer,
        BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1), samples)
    private val root = DocumentRoot.compose("order", SceneSnapshot.restored(SceneId("doc"), 3,
        listOf(ink("a1", a.layerId), ink("b1", b.layerId), ink("a2", a.layerId), ink("b2", b.layerId), ink("a3", a.layerId))), listOf(a, b))
    private fun ids(vararg values: String) = values.map(::ObjectId)
    private fun order(owner: MemoryDocumentAuthority) = owner.snapshot().scene.objects().map { it.id }
    private fun command(owner: MemoryDocumentAuthority, selected: List<ObjectId> = ids("a1"),
        direction: ObjectOrderDirection = ObjectOrderDirection.FRONT, id: String = "order") =
        OrderObjects(CommandId(id), root.id, owner.snapshot().revision, a.id, selected, direction)
    private fun accept(owner: MemoryDocumentAuthority, command: DocumentCommand) = assertIs<DocumentCommit.Accepted>(owner.commit(command)).receipt
    private fun reject(owner: MemoryDocumentAuthority, command: DocumentCommand, reason: DocumentRejection) {
        val before = owner.snapshot(); val status = owner.status()
        assertEquals(reason, assertIs<DocumentCommit.Rejected>(owner.commit(command)).reason)
        assertSame(before, owner.snapshot()); assertEquals(status, owner.status())
    }
    private fun undo(owner: MemoryDocumentAuthority) = accept(owner, DocumentCommand.Undo(CommandId("u-${owner.snapshot().revision}"), root.id,
        owner.snapshot().revision, owner.status().nextUndoOperationId!!))
    private fun redo(owner: MemoryDocumentAuthority) = accept(owner, DocumentCommand.Redo(CommandId("r-${owner.snapshot().revision}"), root.id,
        owner.snapshot().revision, owner.status().nextRedoOperationId!!))

    @Test fun atomicOrderVersionsAndRetryKeepObjectEpochsAndPayloads() {
        val owner = MemoryDocumentAuthority(root)
        val selected = ids("a3", "a1").toMutableList(); val cmd = command(owner, selected)
        selected.clear()
        val receipt = accept(owner, cmd)
        assertEquals(ids("a2", "b1", "a1", "b2", "a3"), order(owner))
        assertEquals(4L, owner.snapshot().revision); assertEquals(4L, owner.snapshot().findPage(a.id)!!.contentRevision)
        assertEquals(b, owner.snapshot().findPage(b.id)); assertEquals(setOf(a.id), receipt.affectedPages())
        assertEquals(Durability.MEMORY_ONLY, receipt.durability)
        root.scene.forEachObject { assertSame(it, owner.snapshot().scene.find(it.id)) }
        assertSame(receipt, accept(owner, OrderObjects(cmd.id, root.id, 3, a.id, ids("a3", "a1"), ObjectOrderDirection.FRONT)))
        reject(owner, OrderObjects(cmd.id, root.id, 3, a.id, ids("a1"), ObjectOrderDirection.FRONT), DocumentRejection.REUSED_OPERATION_ID)
        val decoded = PagedPreviewCodec.decode(PagedPreviewCodec.encode(owner.snapshot()))
        assertEquals(order(owner), decoded.scene.objects().map { it.id }); assertEquals(4L, decoded.revision)
        val ledger = PreviewSaveLedger()
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(PagedPreviewSnapshot(root)))
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(PagedPreviewSnapshot(owner.snapshot())))
    }

    @Test fun mixedMembershipAndTransformHistoryRestoresOrderWithoutReorderingOtherPageSlots() {
        val owner = MemoryDocumentAuthority(root)
        accept(owner, command(owner)); val sorted = order(owner)
        accept(owner, DocumentCommand.Canvas(a.id, CanvasCommand.AddStroke(CommandId("add"), 4,
            StrokeTarget(root.id, a.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)), ObjectId("a4"), samples)))
        accept(owner, DocumentCommand.Canvas(a.id, CanvasCommand.DeleteStroke(CommandId("delete"), root.id, 5, ObjectId("a2"), 1)))
        accept(owner, DocumentCommand.Canvas(a.id, CanvasCommand.TransformStroke(CommandId("transform"), root.id, 6, ObjectId("a1"), 1, Affine2D(tx = 8.0))))
        val final = order(owner)
        repeat(3) {
            repeat(3) { undo(owner) }; assertEquals(sorted, order(owner))
            val epoch = owner.snapshot().scene.find(ObjectId("a1"))!!.objectRevision
            undo(owner); assertEquals(root.scene.objects().map { it.id }, order(owner))
            assertEquals(epoch, owner.snapshot().scene.find(ObjectId("a1"))!!.objectRevision)
            redo(owner); assertEquals(sorted, order(owner))
            repeat(3) { redo(owner) }; assertEquals(final, order(owner))
            assertEquals(Affine2D(tx = 8.0), owner.snapshot().scene.find(ObjectId("a1"))!!.transform)
        }
    }

    @Test fun invalidAndNoopCommandsLeaveAllAuthorityStateUntouched() {
        val owner = MemoryDocumentAuthority(root)
        for (selection in listOf(emptyList(), ids("a1", "a1"), ids("missing"), ids("\uD800")))
            reject(owner, command(owner, selection), DocumentRejection.INVALID_IDENTITY)
        reject(owner, command(owner, ids("b1")), DocumentRejection.WRONG_LAYER)
        reject(owner, command(owner, ids("a3")), DocumentRejection.NO_CHANGE)
        reject(owner, OrderObjects(CommandId("bad-page"), root.id, 3, PageId("missing"), ids("a1"), ObjectOrderDirection.FRONT), DocumentRejection.MISSING_PAGE)
        reject(owner, OrderObjects(CommandId("stale"), root.id, 2, a.id, ids("a1"), ObjectOrderDirection.FRONT), DocumentRejection.STALE_ROOT)
        reject(owner, OrderObjects(CommandId("other"), SceneId("other"), 3, a.id, ids("a1"), ObjectOrderDirection.FRONT), DocumentRejection.WRONG_DOCUMENT)
        accept(owner, command(owner)); owner.retireEditingState()
        reject(owner, command(owner, id = "closed"), DocumentRejection.CLOSED)
    }

    @Test fun exactHistoryJournalAndScratchAdmissionRejectOneByteShortBeforePublishing() {
        val probe = MemoryDocumentAuthority(root); val cmd = command(probe); accept(probe, cmd)
        val history = probe.status().historyByteCharge
        val journal = probe.status().journalByteCharge
        val scratch = 512L + root.scene.objectCount * 256L + cmd.selectionCharge * 4L
        val exact = DocumentBudget(historyBytes = history, journalBytes = journal, inputBytes = scratch)
        accept(MemoryDocumentAuthority(root, exact), cmd)
        reject(MemoryDocumentAuthority(root, exact.copy(historyBytes = history - 1)), cmd, DocumentRejection.HISTORY_CAPACITY)
        reject(MemoryDocumentAuthority(root, exact.copy(journalBytes = journal - 1)), cmd, DocumentRejection.JOURNAL_CAPACITY)
        reject(MemoryDocumentAuthority(root, exact.copy(inputBytes = scratch - 1)), cmd, DocumentRejection.LIVE_BYTE_CAPACITY)
        assertEquals(MemoryDocumentAuthority.COMMAND_HEADER_BYTES + cmd.selectionCharge, journal)
        assertEquals(PageObjectOrder.projectedCharge(root, a), PageObjectOrder(a.id, ids("a1", "a2", "a3"), ids("a2", "a3", "a1")).charge)
    }

    @Test fun historyEvictionAndSuccessfulNewEditClearRedoUnderOriginalPolicy() {
        val owner = MemoryDocumentAuthority(root, DocumentBudget(historyEntries = 1))
        accept(owner, command(owner)); accept(owner, command(owner, ids("a1"), ObjectOrderDirection.BACK, "back"))
        assertEquals(1, owner.status().undoCount); assertEquals(1L, owner.status().evictedHistoryCount)
        undo(owner); assertEquals(1, owner.status().redoCount)
        reject(owner, command(owner, ids("a1"), id = "noop"), DocumentRejection.NO_CHANGE)
        accept(owner, DocumentCommand.Rename(CommandId("name"), root.id, owner.snapshot().revision, "changed"))
        assertEquals(0, owner.status().redoCount)
    }

    @Test fun sparseHistoryMatchesExactPageMembershipAndExpectedOrder() {
        val delta = PageObjectOrder(a.id, ids("a1", "a2", "a3"), ids("a2", "a3", "a1"))
        assertTrue(delta.matches(root, false)); assertFalse(delta.matches(root, true))
        val owner = MemoryDocumentAuthority(root); accept(owner, command(owner))
        assertTrue(delta.matches(owner.snapshot(), true)); assertFalse(delta.matches(owner.snapshot(), false))
        val missing = DocumentRoot.compose(root.title, SceneSnapshot.restored(root.id, 3, root.scene.objects().filter { it.id != ObjectId("a2") }), root.pages())
        assertFalse(delta.matches(missing, false))
    }
}
