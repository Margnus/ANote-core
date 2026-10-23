package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PreviewInputTest {
    private fun note(budget: DocumentBudget = DocumentBudget(), guard: () -> Unit = {}) = PagedPreviewNotebook(
        DocumentRoot.empty(SceneId("batch"), "batch", PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)), "batch-session", budget, guard)
    private fun offer(n: PagedPreviewNotebook, phase: PointerPhase, vararg times: Long, event: Long = times.lastOrNull() ?: 0): PreviewInputResult =
        n.offerRealBatch(phase, event, DoubleArray(times.size) { times[it].toDouble() }, DoubleArray(times.size) { it + 2.0 },
            FloatArray(times.size) { .5f }, times)
    @Test fun multipleRealBatchesCaptureOriginalBrushAndNeverRetainCallerBuffers() {
        val n = note(); assertTrue(n.selectPen(123, 4.0))
        val xs = doubleArrayOf(1.0, 2.0, 3.0); val ys = doubleArrayOf(4.0, 5.0, 6.0)
        val pressures = floatArrayOf(.2f, .3f, .4f); val times = longArrayOf(1, 1, 2)
        val down = n.offerRealBatch(PointerPhase.DOWN, 2, xs, ys, pressures, times)
        assertEquals(PreviewInputDisposition.ACCEPTED, down.disposition); assertEquals(3, down.acceptedSamples); assertEquals(3, down.activeSamples)
        xs.fill(99.0); ys.fill(99.0); pressures.fill(1f); times.fill(99)
        assertTrue(n.selectPen(456, 8.0))
        assertEquals(PreviewInputDisposition.ACCEPTED, offer(n, PointerPhase.MOVE, 3, 4).disposition)
        val up = offer(n, PointerPhase.UP, 5, 6)
        assertEquals(PreviewInputDisposition.COMMITTED, up.disposition); assertEquals(2, up.acceptedSamples); assertEquals(0, up.activeSamples)
        assertEquals(7, n.pointCount(0)); assertEquals(1.0, n.pointX(0, 0)); assertEquals(4.0, n.pointY(0, 0))
        assertEquals(.2f, n.pointPressure(0, 0)); assertEquals(1L, n.stroke(0).samples.timeMicros(1))
        assertEquals(123, n.strokeColor(0)); assertEquals(4.0, n.strokeWidth(0))
    }
    @Test fun invalidSuffixAndMalformedLengthsRejectWholeBatchAndAllowSameTimeRetry() {
        val n = note(); assertTrue(offer(n, PointerPhase.DOWN, 1, 2).admitted); val before = n.snapshot()
        val rejected = n.offerRealBatch(PointerPhase.MOVE, 4, doubleArrayOf(3.0, Double.NaN), doubleArrayOf(3.0, 4.0), floatArrayOf(.5f, .5f), longArrayOf(3, 4))
        assertEquals(PreviewInputDisposition.REJECTED, rejected.disposition); assertEquals(0, rejected.acceptedSamples); assertEquals(2, rejected.activeSamples)
        assertEquals(InputRejection.INVALID_BATCH, rejected.inputReason); assertSame(before, n.snapshot())
        assertFalse(n.offerRealBatch(PointerPhase.MOVE, 4, doubleArrayOf(3.0), doubleArrayOf(), floatArrayOf(.5f), longArrayOf(3)).admitted)
        assertTrue(offer(n, PointerPhase.MOVE, 3, 4).admitted)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 5).disposition)
        assertEquals(5, n.pointCount(0)); assertEquals(3.0, n.pointX(0, 2))
    }
    @Test fun batchLimitAndInvalidDownDoNotConsumeIdentityOrAcceptedPrefix() {
        val n = note(); val big = n.inputBatchLimit + 1
        val rejected = n.offerRealBatch(PointerPhase.DOWN, 0, DoubleArray(big), DoubleArray(big), FloatArray(big), LongArray(big))
        assertEquals(PreviewFailureKind.INPUT_BATCH_CAPACITY, rejected.failure?.kind); assertEquals(0, rejected.activeSamples)
        assertFalse(offer(n, PointerPhase.DOWN).admitted)
        assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        assertEquals(PreviewFailureKind.INPUT_BATCH_CAPACITY, n.offerRealBatch(PointerPhase.MOVE, 2, DoubleArray(big), DoubleArray(big), FloatArray(big), LongArray(big)).failure?.kind)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 2).disposition)
        assertEquals(CommandId("batch-session-op-1"), n.authorityStatus().nextUndoOperationId); assertEquals(2, n.pointCount(0))
    }
    @Test fun strokeCapacityRejectsWholeProducerBatchAndEmptyUpFinishesOnlyAcceptedPoints() {
        val n = note(); var count = 0
        while (count < PagedPreviewCodec.MAX_STROKE_POINTS) {
            val size = minOf(n.inputBatchLimit, PagedPreviewCodec.MAX_STROKE_POINTS - count)
            val times = LongArray(size) { count + it + 1L }
            assertTrue(offer(n, if (count == 0) PointerPhase.DOWN else PointerPhase.MOVE, *times).admitted)
            count += size
        }
        val rejected = offer(n, PointerPhase.MOVE, count + 1L, count + 2L)
        assertEquals(0, rejected.acceptedSamples); assertEquals(count, rejected.activeSamples); assertEquals(InputRejection.CAPACITY, rejected.inputReason)
        val up = offer(n, PointerPhase.UP, event = count.toLong())
        assertEquals(PreviewInputDisposition.COMMITTED, up.disposition); assertEquals(0, up.acceptedSamples)
        assertEquals(count, n.pointCount(0)); assertEquals(count.toDouble(), n.pointX(0, count - 1))
    }
    @Test fun terminalAcceptedButPendingIsNotARejectedBatchOrAReasonToResend() {
        val fault = PendingInputOwnerFault(); val n = fault.note; assertTrue(n.rename("filled"))
        assertTrue(offer(n, PointerPhase.DOWN, 1, 2).admitted)
        fault.arm()
        assertFailsWith<IllegalStateException> { offer(n, PointerPhase.UP, 3, 4) }
        assertEquals(0, n.totalStrokeCount)
        val pending = n.pendingStroke(); val samples = (pending!!.command as CanvasCommand.AddStroke).samples
        assertEquals(4, samples.size)
        assertEquals(samples.logicalByteCharge, n.inputReservedByteCharge)
        n.cancelStroke(); assertEquals(samples.logicalByteCharge, n.inputReservedByteCharge)
        val resent = offer(n, PointerPhase.UP, 3, 4)
        assertFalse(resent.admitted); assertEquals(PreviewFailureKind.PENDING_STROKE, resent.failure?.kind)
        assertSame(pending, n.pendingStroke()); n.cancelStroke(); assertFalse(n.retire()); assertSame(pending, n.pendingStroke())
    }
    @Test fun eventClockRejectionDoesNotAdvanceSequenceAndEqualTimestampDistinctPointsStayReal() {
        val n = note(); assertTrue(offer(n, PointerPhase.DOWN, 10, 10).admitted)
        val rejected = offer(n, PointerPhase.UP, event = 9)
        assertEquals(InputRejection.EVENT_ORDER, rejected.inputReason); assertEquals(2, rejected.activeSamples)
        assertEquals(PreviewInputDisposition.COMMITTED, offer(n, PointerPhase.UP, 10, event = 10).disposition)
        assertEquals(3, n.pointCount(0)); assertEquals(2.0, n.pointY(0, 0)); assertEquals(3.0, n.pointY(0, 1))
    }
    @Test fun pageChangeCancellationClosedAndLegacyPolicyRemainExplicit() {
        val n = note(); assertTrue(n.insertPage()); assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
        assertTrue(n.selectPage(PageId("page"))); val before = n.snapshot()
        assertFalse(offer(n, PointerPhase.UP, 2).admitted); assertSame(before, n.snapshot())
        assertTrue(offer(n, PointerPhase.DOWN, 3).admitted)
        assertEquals(PreviewInputDisposition.CANCELLED, offer(n, PointerPhase.CANCEL, event = 3).disposition)
        assertEquals(0, n.totalStrokeCount); assertTrue(n.beginStroke(1.0, 2.0, .5f, 4))
        assertFalse(n.appendStroke(Double.NaN, 2.0, .5f, 5)); assertFalse(n.hasActiveStroke) // compatibility path explicitly cancels
        assertTrue(n.retire()); assertEquals(PreviewFailureKind.CLOSED, offer(n, PointerPhase.DOWN, 6).failure?.kind)
    }
    @Test fun ownerFailureIsNotConvertedIntoCapacityOrInvalidData() {
        var allowed = true; val n = note(guard = { check(allowed) }); val before = n.snapshot()
        allowed = false; assertFailsWith<IllegalStateException> { offer(n, PointerPhase.DOWN, 1) }
        allowed = true; assertSame(before, n.snapshot()); assertTrue(offer(n, PointerPhase.DOWN, 1).admitted)
    }
}


/** No production hook: a trusted owner checker fails only after completion has installed pending. */
internal class PendingInputOwnerFault(
    root: DocumentRoot = DocumentRoot.empty(SceneId("fault"), "fault", PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)),
    nonce: String = "fault-session", budget: DocumentBudget = DocumentBudget(),
) {
    private var armed = false
    private var probing = false
    val note: PagedPreviewNotebook
    init {
        var current: PagedPreviewNotebook? = null
        note = PagedPreviewNotebook(root, nonce, budget) {
            if (armed && !probing) {
                probing = true
                val pending = try { current?.hasPendingStroke == true } finally { probing = false }
                if (pending) { armed = false; error("Synthetic unavailable commit owner") }
            }
        }
        current = note
    }
    fun arm() { armed = true }
}
