package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

/** Unsupported runtime quota revocation exercises the defensive typed completion without a production hook. */
class PreviewPostAdmissionFaultTest {
    @Test fun revokedJournalAfterDownKeepsExactTypedPendingAndCanRetryAfterRestoration() {
        val n = PagedPreviewNotebook.create("fault", "title", "fault-session")
        assertTrue(n.rename("filled"))
        fun offer(phase: PointerPhase, vararg t: Long) = n.offerRealBatch(phase, t.last(), DoubleArray(t.size) { t[it].toDouble() },
            DoubleArray(t.size) { 2.0 }, FloatArray(t.size) { .5f }, t)
        assertTrue(offer(PointerPhase.DOWN, 1, 2).admitted)
        val before = n.snapshot()
        val memory = n.javaClass.getDeclaredField("memory").apply { isAccessible = true }.get(n)
        val budget = memory.javaClass.getDeclaredField("budget").apply { isAccessible = true }.get(memory)
        val quota = budget.javaClass.getDeclaredField("journalBytes").apply { isAccessible = true }
        val originalQuota = quota.getLong(budget)
        try {
            quota.setLong(budget, 0)
            val result = offer(PointerPhase.UP, 3, 4)
            assertEquals(PreviewInputDisposition.PENDING_COMMIT, result.disposition)
            assertTrue(result.admitted); assertEquals(2, result.acceptedSamples)
            assertEquals(DocumentRejection.JOURNAL_CAPACITY, result.failure?.documentReason)
            val pending = assertNotNull(n.pendingStroke()); val samples = (pending.command as CanvasCommand.AddStroke).samples
            assertEquals(4, samples.size); assertEquals(240L, n.inputReservedByteCharge)
            n.cancelStroke(); assertFalse(n.retire()); assertFalse(n.retryPendingStroke())
            assertSame(before, n.snapshot()); assertSame(pending, n.pendingStroke())
            assertFalse(offer(PointerPhase.UP, 3, 4).admitted)
        } finally { quota.setLong(budget, originalQuota) }
        assertTrue(n.retryPendingStroke()); assertFalse(n.hasPendingStroke); assertEquals(1, n.totalStrokeCount)
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), List(n.pointCount(0)) { n.pointX(0, it) })
    }
}
