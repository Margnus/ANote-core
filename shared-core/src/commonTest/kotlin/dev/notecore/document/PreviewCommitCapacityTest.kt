package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PreviewCommitCapacityTest {
    private val page = PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)
    private fun root() = DocumentRoot.empty(SceneId("admit"), "title", page)
    private fun note(budget: DocumentBudget = DocumentBudget()) = PagedPreviewNotebook(root(), "admit", budget)
    private fun offer(n: PagedPreviewNotebook, phase: PointerPhase, vararg t: Long, event: Long = t.lastOrNull() ?: 0) =
        n.offerRealBatch(phase, event, DoubleArray(t.size) { t[it].toDouble() }, DoubleArray(t.size) { 2.0 }, FloatArray(t.size) { .5f }, t)
    private fun rejected(n: PagedPreviewNotebook, reason: DocumentRejection, vararg t: Long) {
        val before = n.snapshot(); val status = n.authorityStatus()
        val result = offer(n, PointerPhase.DOWN, *t)
        assertEquals(PreviewInputDisposition.REJECTED, result.disposition); assertEquals(0, result.acceptedSamples)
        assertEquals(0, result.activeSamples); assertEquals(reason, result.failure?.documentReason)
        assertSame(before, n.snapshot()); assertEquals(status, n.authorityStatus())
        assertFalse(n.hasActiveStroke); assertFalse(n.hasPendingStroke); assertEquals(0L, n.inputReservedByteCharge)
    }
    @Test fun fullJournalRefusesDownAndCommittedDocumentCanBeReopenedWithoutPending() {
        val n = note(DocumentBudget(journalEntries = 1)); assertTrue(n.rename("filled"))
        repeat(3) { rejected(n, DocumentRejection.JOURNAL_CAPACITY, 1) }
        val encoded = n.capture().encode(); assertTrue(n.retire())
        val reopened = PagedPreviewNotebook(PagedPreviewCodec.decode(encoded), "reopened")
        assertTrue(offer(reopened, PointerPhase.DOWN, 1).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(reopened, PointerPhase.UP, 2).disposition)
    }
    @Test fun journalByteBoundaryAdmitsOnlyWholeAffordableStrokeWithoutConsumingRejectedIds() {
        val n = note(DocumentBudget(journalBytes = 4096 + 2 * 28))
        rejected(n, DocumentRejection.JOURNAL_CAPACITY, 1, 2, 3)
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted); assertEquals(752L, n.inputReservedByteCharge)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 2).disposition)
        assertEquals(CommandId("admit-op-1"), n.authorityStatus().nextUndoOperationId)
        assertEquals("admit-ink-2", n.strokeId(0)); rejected(n, DocumentRejection.JOURNAL_CAPACITY, 3)
    }
    @Test fun historyByteBoundaryAllowsEvictableOldHistoryAndEmptyTerminal() {
        val probe = note(); assertTrue(offer(probe, PointerPhase.DOWN, 1).admitted); assertTrue(offer(probe, PointerPhase.UP, 2).admitted)
        val bytes = probe.authorityStatus().historyByteCharge
        val n = note(DocumentBudget(historyBytes = bytes))
        for (time in listOf(1L, 3L)) {
            assertTrue(offer(n, PointerPhase.DOWN, time).admitted)
            assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, time + 1).disposition)
        }
        assertEquals(2, n.totalStrokeCount); assertEquals(1, n.authorityStatus().undoCount); assertEquals(1L, n.authorityStatus().evictedHistoryCount)
        val short = note(DocumentBudget(historyBytes = bytes - 1))
        rejected(short, DocumentRejection.HISTORY_CAPACITY, 1, 2)
        assertTrue(offer(short, PointerPhase.DOWN, 1).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(short, PointerPhase.UP, event = 1).disposition)
        assertEquals(1, short.pointCount(0))
    }
    @Test fun identityBoundaryRefusesBeforeAllocationAndMetadataRetryKeepsOriginalId() {
        val base = note().authorityStatus().identityByteCharge
        val bytes = base + 80 + "admit-ink-2".length * 2
        val n = note(DocumentBudget(identityBytes = bytes - 1))
        rejected(n, DocumentRejection.IDENTITY_CAPACITY, 1)
        assertTrue(n.rename("ok")); assertEquals(CommandId("admit-op-1"), n.authorityStatus().nextUndoOperationId)
        val exact = note(DocumentBudget(identityBytes = bytes))
        assertTrue(offer(exact, PointerPhase.DOWN, 1).admitted); assertTrue(offer(exact, PointerPhase.UP, 2).admitted)
        assertEquals(bytes, exact.authorityStatus().identityByteCharge)
        rejected(exact, DocumentRejection.IDENTITY_CAPACITY, 3)
    }
    @Test fun exhaustedRootRevisionRefusesWithoutOwningInputAndRemainsEncodable() {
        val root = DocumentRoot.compose("title", SceneSnapshot.restored(SceneId("admit"), Long.MAX_VALUE, emptyList()), listOf(page))
        val n = PagedPreviewNotebook(root, "admit")
        rejected(n, DocumentRejection.REVISION_EXHAUSTED, 1)
        assertEquals(Long.MAX_VALUE, PagedPreviewCodec.decode(n.capture().encode()).revision); assertTrue(n.retire())
    }
    @Test fun projectedStrokePlanMatchesActualDeltaWithoutConstructingPlaceholderSamples() {
        for (count in listOf(1, 257, 20_000)) {
            val owner = MemoryDocumentAuthority(root())
            val samples = StrokeSamples.copyOf(DoubleArray(count), DoubleArray(count), FloatArray(count), LongArray(count) { it.toLong() })
            val id = ObjectId("longer-identity-$count")
            val expected = DocumentDelta.projectedStrokeCharge(root(), page, id, count)
            val action = CanvasCommand.AddStroke(CommandId("op"), 0, StrokeTarget(SceneId("admit"), page.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)), id, samples)
            assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Canvas(page.id, action)))
            assertEquals(expected, owner.status().historyByteCharge)
            assertEquals(28L * count, expected - DocumentDelta.projectedStrokeCharge(root(), page, id, 0))
        }
    }
    @Test fun arenaAndJournalLimitsIntersectAndNoopToolChangeCannotInvalidateCommitPlan() {
        val n = note(DocumentBudget(journalBytes = 4096 + 3 * 28, inputBytes = InputReplaySession.reservationBytes(2)))
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted); assertEquals(752L, n.inputReservedByteCharge)
        assertTrue(n.rename("title")); assertTrue(n.selectPen(123, 3.0)); assertTrue(n.hasActiveStroke)
        assertFalse(offer(n, PointerPhase.MOVE, 2, 3).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 2).disposition)
        assertEquals(2.5, n.strokeWidth(0)); assertFalse(n.hasPendingStroke)
    }
}
