package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class RetiredEditingStateTest {
    private val page = PageDescriptor(PageId("page"), LayerId("ink"), 595.0, 842.0)
    private fun empty() = DocumentRoot.empty(SceneId("retire"), "title", page)
    private fun cleared(status: DocumentAuthorityStatus) {
        assertEquals(0L, status.journalByteCharge); assertEquals(0L, status.historyByteCharge); assertEquals(0L, status.identityByteCharge)
        assertEquals(0, status.operationCount); assertEquals(0, status.undoCount); assertEquals(0, status.redoCount)
        assertNull(status.nextUndoOperationId); assertNull(status.nextRedoOperationId)
    }
    private fun draw(note: PagedPreviewNotebook) {
        assertTrue(note.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(note.endStroke(3.0, 4.0, .75f, 2))
    }
    @Test fun originalAuthorityRetiresEditingReferencesAndRejectsOldAndNewCommands() {
        var owned = true
        val authority = MemoryDocumentAuthority(empty(), DocumentBudget(historyEntries = 2), ownerCheck = { check(owned) })
        fun accept(command: DocumentCommand) = assertIs<DocumentCommit.Accepted>(authority.commit(command))
        fun add(key: String, count: Int): DocumentCommand.Canvas {
            val root = authority.snapshot()
            return DocumentCommand.Canvas(page.id, CanvasCommand.AddStroke(CommandId("add-$key"), root.revision,
                StrokeTarget(root.id, page.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.5, -1)), ObjectId(key),
                StrokeSamples.copyOf(DoubleArray(count), DoubleArray(count), FloatArray(count) { .5f }, LongArray(count))))
        }
        val old = add("deleted", 257); accept(old)
        accept(DocumentCommand.Canvas(page.id, CanvasCommand.DeleteStroke(CommandId("delete"), authority.snapshot().id,
            authority.snapshot().revision, (old.command as CanvasCommand.AddStroke).objectId, 1)))
        accept(add("kept", 2))
        accept(DocumentCommand.Rename(CommandId("rename"), authority.snapshot().id, authority.snapshot().revision, "changed"))
        accept(DocumentCommand.Undo(CommandId("undo"), authority.snapshot().id, authority.snapshot().revision, CommandId("rename")))
        val root = authority.snapshot(); val before = authority.status(); val encoded = PagedPreviewCodec.encode(root)
        assertTrue(before.undoCount > 0 && before.redoCount > 0 && before.evictedHistoryCount > 0)
        assertTrue(before.journalByteCharge > 0 && before.identityByteCharge > 0 && before.historyByteCharge > 0)
        assertTrue(authority.identityWasUsed("deleted")); assertNull(root.scene.find(ObjectId("deleted")))
        val next = add("late", 1)
        owned = false; assertFailsWith<IllegalStateException> { authority.retireEditingState() }
        owned = true; assertEquals(before, authority.status())
        authority.retireEditingState(); val after = authority.status(); cleared(after)
        assertSame(root, authority.snapshot()); assertEquals(encoded, PagedPreviewCodec.encode(authority.snapshot()))
        assertEquals(PagedPreviewCodec.retainedByteCharge(root), after.liveByteCharge)
        assertEquals(before.evictedHistoryCount, after.evictedHistoryCount)
        assertEquals(DocumentRejection.CLOSED, assertIs<DocumentCommit.Rejected>(authority.commit(old)).reason)
        assertEquals(DocumentRejection.CLOSED, assertIs<DocumentCommit.Rejected>(authority.commit(next)).reason)
        assertEquals(0, authority.additionalStrokeSampleCapacity(ObjectId("late"), page.layerId))
        assertEquals(DocumentRejection.CLOSED, authority.strokeInputCapacity(ObjectId("late"), page.id).limitingReason)
        assertFailsWith<IllegalStateException> { authority.identityWasUsed("late") }
        authority.retireEditingState(); assertEquals(after, authority.status()); assertSame(root, authority.snapshot())
    }
    @Test fun stronglyRetainedOldNotebooksKeepOnlyCanonicalReadResponsibilityWhileNewOwnerEdits() {
        var note = PagedPreviewNotebook(empty(), "session0")
        val old = mutableListOf<Pair<PagedPreviewNotebook, PagedPreviewSnapshot>>()
        repeat(3) { index ->
            draw(note); val snapshot = note.capture()
            assertTrue(note.authorityStatus().journalByteCharge > 0 && note.authorityStatus().historyByteCharge > 0)
            assertTrue(note.retire()); assertTrue(note.retire()); old.add(note to snapshot)
            note = assertNotNull(PagedPreviewNotebook.open(snapshot.root, "session${index + 1}").notebook)
            for ((retired, captured) in old) {
                cleared(retired.authorityStatus()); assertSame(captured.root, retired.snapshot())
                assertEquals(captured.retainedByteCharge, retired.authorityStatus().liveByteCharge)
                assertEquals(captured.encode(), retired.capture().encode())
                assertFalse(retired.beginStroke(5.0, 6.0, .5f, 3)); assertFalse(retired.retryPendingStroke())
            }
        }
        draw(note); assertEquals(4, note.totalStrokeCount)
        assertTrue(note.authorityStatus().historyByteCharge > 0); old.forEach { cleared(it.first.authorityStatus()) }
    }
    @Test fun failedRecoveryRetirementKeepsEditingAndExactHandoffRetainsPendingForSingleNewOwnerCommit() {
        val fault = PendingInputOwnerFault(); val note = fault.note
        draw(note); assertTrue(note.beginStroke(5.0, 6.0, .5f, 3))
        fault.arm(); assertFailsWith<IllegalStateException> { note.endStroke(7.0, 8.0, .75f, 4) }
        val checkpoint = note.captureRecovery(); val encoded = checkpoint.encode(); val before = note.authorityStatus()
        assertFalse(note.retire()); assertEquals(before, note.authorityStatus())
        assertFalse(note.retireAfterRecovery(note.capture())); assertEquals(before, note.authorityStatus())
        assertFalse(note.retireAfterRecovery(PreviewRecoveryCodec.decode(encoded))); assertEquals(before, note.authorityStatus())
        fault.arm(); assertFailsWith<IllegalStateException> { note.retireAfterRecovery(checkpoint) }
        assertEquals(before, note.authorityStatus()); assertSame(checkpoint.pending, note.pendingStroke())
        assertTrue(note.retireAfterRecovery(checkpoint)); cleared(note.authorityStatus())
        assertSame(checkpoint.root, note.snapshot()); assertSame(checkpoint.pending, note.pendingStroke())
        assertTrue(note.inputReservedByteCharge > 0); assertEquals(encoded, note.captureRecovery().encode())
        assertFalse(note.retryPendingStroke())
        val next = assertNotNull(PagedPreviewNotebook.openRecovery(checkpoint, "newowner").notebook)
        assertTrue(next.retryPendingStroke()); val root = next.snapshot(); assertTrue(next.retryPendingStroke())
        assertSame(root, next.snapshot()); assertEquals(2, next.totalStrokeCount)
        val stroke = checkpoint.pending!!.command as CanvasCommand.AddStroke
        assertEquals(stroke.samples, root.scene.find(stroke.objectId)!!.samples)
        assertEquals(encoded, note.captureRecovery().encode()); cleared(note.authorityStatus())
    }
    @Test fun wrongOwnerCannotRetireNotebookOrCancelItsAcceptedPrefix() {
        var owned = true
        val note = PagedPreviewNotebook(empty(), "owner", ownerCheck = { check(owned) })
        draw(note); assertTrue(note.beginStroke(5.0, 6.0, .5f, 3))
        val status = note.authorityStatus(); val root = note.snapshot(); val charge = note.inputReservedByteCharge
        owned = false; assertFailsWith<IllegalStateException> { note.retire() }
        owned = true; assertEquals(status, note.authorityStatus()); assertSame(root, note.snapshot())
        assertTrue(note.hasActiveStroke); assertEquals(charge, note.inputReservedByteCharge)
        assertTrue(note.retire()); assertFalse(note.hasActiveStroke); cleared(note.authorityStatus())
        assertSame(root, note.snapshot()); assertEquals(0L, note.inputReservedByteCharge)
    }
}
