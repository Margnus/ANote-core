package dev.notecore.document

import dev.notecore.canvas.preview.*
import kotlin.test.*

class PreviewSaveDispatchTest {
    private fun note(id: String, points: Int = 0): PagedPreviewNotebook {
        val note = PagedPreviewNotebook.create(id, id, "$id-session")
        if (points > 0) {
            require(points >= 2)
            assertTrue(note.beginStroke(1.0, 2.0, .5f, 1))
            for (i in 2 until points) assertTrue(note.appendStroke(i.toDouble(), 2.0, .5f, i.toLong()))
            assertTrue(note.endStroke(points.toDouble(), 2.0, .5f, points.toLong()))
        }
        return note
    }

    @Test fun invalidAndTemporaryBudgetsRetainContentWithoutConsumingAnAttempt() {
        val snapshot = note("budget", 2).capture(); val ledger = PreviewSaveLedger()
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(snapshot))
        for ((available, maximum) in listOf(-1L to 1L, 0L to 0L, 2L to 1L, 1L to -1L)) {
            assertEquals(PreviewWriteDisposition.INVALID_BUDGET, ledger.admitNextWrite(available, maximum).disposition)
        }
        val needed = snapshot.encodingWorkByteCharge
        repeat(3) {
            val result = ledger.admitNextWrite(needed - 1, needed)
            assertEquals(PreviewWriteDisposition.BACKPRESSURE, result.disposition)
            assertNull(result.attempt); assertEquals(snapshot.documentId, result.documentId)
            assertFalse(ledger.states().single().inFlight)
            assertSame(snapshot, ledger.latest(snapshot.documentId))
        }
        val first = assertNotNull(ledger.admitNextWrite(needed, needed).attempt)
        assertEquals(1L, first.number)
        assertTrue(ledger.complete(first, false))
        assertEquals(PreviewWriteDisposition.IDLE, ledger.admitNextWrite(needed, needed).disposition)
        assertTrue(ledger.states().single().retryRequired)
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(snapshot))
        val retry = assertNotNull(ledger.admitNextWrite(needed, needed).attempt)
        assertEquals(2L, retry.number); assertSame(snapshot, retry.snapshot)
        assertFalse(ledger.complete(first, true)); assertTrue(ledger.complete(retry, true)); assertTrue(ledger.isDrained)
    }

    @Test fun oversizedDocumentDoesNotBlockAnotherDocumentAndRemainsRecoverable() {
        val large = note("large", 40).capture(); val small = note("small").capture()
        val ledger = PreviewSaveLedger(); ledger.offer(large); ledger.offer(small)
        val limit = small.encodingWorkByteCharge
        assertTrue(large.encodingWorkByteCharge > limit)
        val admitted = ledger.admitNextWrite(limit, limit)
        assertEquals(PreviewWriteDisposition.READY, admitted.disposition)
        val attempt = assertNotNull(admitted.attempt); assertSame(small, attempt.snapshot)
        assertEquals(1L, attempt.number); assertTrue(ledger.complete(attempt, true))
        val refused = ledger.admitNextWrite(limit, limit)
        assertEquals(PreviewWriteDisposition.WORK_CAPACITY, refused.disposition)
        assertEquals(large.documentId, refused.documentId); assertNull(refused.attempt)
        assertSame(large, ledger.latest(large.documentId))
        assertEquals(large.retainedByteCharge, ledger.chargedBytes)
        val recovery = assertNotNull(ledger.admitNextWrite(large.encodingWorkByteCharge, large.encodingWorkByteCharge).attempt)
        assertSame(large, recovery.snapshot); assertEquals(2L, recovery.number)
        assertTrue(ledger.complete(recovery, true)); assertTrue(ledger.isDrained)
    }

    @Test fun temporaryBackpressureKeepsEligibleOrderSoSmallerSavesCannotStarveTheFirstDocument() {
        val large = note("large", 40).capture(); val small = note("small").capture()
        val ledger = PreviewSaveLedger(); ledger.offer(large); ledger.offer(small)
        repeat(3) {
            val blocked = ledger.admitNextWrite(small.encodingWorkByteCharge, large.encodingWorkByteCharge)
            assertEquals(PreviewWriteDisposition.BACKPRESSURE, blocked.disposition)
            assertEquals(large.documentId, blocked.documentId); assertNull(blocked.attempt)
            assertTrue(ledger.states().none { it.inFlight })
        }
        val first = assertNotNull(ledger.admitNextWrite(large.encodingWorkByteCharge, large.encodingWorkByteCharge).attempt)
        assertSame(large, first.snapshot); assertEquals(1L, first.number); assertTrue(ledger.complete(first, true))
        val second = assertNotNull(ledger.admitNextWrite(small.encodingWorkByteCharge, large.encodingWorkByteCharge).attempt)
        assertSame(small, second.snapshot); assertEquals(2L, second.number)
    }

    @Test fun oldFlightAndNewContentKeepTheirChargesAndLateCompletionCannotClearTheReplacement() {
        val note = note("versions", 2); val old = note.capture(); val ledger = PreviewSaveLedger()
        ledger.offer(old); val flight = assertNotNull(ledger.admitNextWrite(Long.MAX_VALUE, Long.MAX_VALUE).attempt)
        assertTrue(note.rename("latest")); val latest = note.capture(); ledger.offer(latest)
        assertEquals(old.retainedByteCharge + latest.retainedByteCharge, ledger.chargedBytes)
        assertEquals(PreviewWriteDisposition.IDLE, ledger.admitNextWrite(0, Long.MAX_VALUE).disposition)
        assertTrue(ledger.states().single().inFlight)
        assertFalse(PreviewSaveLedger().complete(flight, true))
        assertTrue(ledger.complete(flight, false)); assertSame(latest, ledger.latest(note.documentId))
        assertEquals(PreviewWriteDisposition.BACKPRESSURE, ledger.admitNextWrite(0, Long.MAX_VALUE).disposition)
        val replacement = assertNotNull(ledger.admitNextWrite(latest.encodingWorkByteCharge, latest.encodingWorkByteCharge).attempt)
        assertSame(latest, replacement.snapshot); assertFalse(ledger.complete(flight, true))
        assertTrue(ledger.complete(replacement, true)); assertTrue(ledger.isDrained)
    }

    @Test fun ownerFailuresPropagateBeforeAllReadsAndMutationsAndLeaveTheOriginalFlightIntact() {
        var forbidden = false
        val ledger = PreviewSaveLedger(ownerCheck = { check(!forbidden) { "foreign owner" } })
        val snapshot = note("owner").capture(); ledger.offer(snapshot); val flight = assertNotNull(ledger.nextWrite())
        forbidden = true
        val operations: List<() -> Any?> = listOf({ ledger.chargedBytes }, { ledger.documentCount }, { ledger.isDrained },
            { ledger.latest(snapshot.documentId) }, { ledger.states() }, { ledger.offer(snapshot) },
            { ledger.admitNextWrite(0, 0) }, { ledger.nextWrite() }, { ledger.returnUnstarted(flight) }, { ledger.complete(flight, true) })
        operations.forEach { assertFailsWith<IllegalStateException> { it() } }
        forbidden = false
        assertSame(snapshot, ledger.latest(snapshot.documentId)); assertTrue(ledger.states().single().inFlight)
        assertTrue(ledger.complete(flight, true)); assertTrue(ledger.isDrained)
    }

    @Test fun stoppedBackendIsNotTemporaryBytePressureAndDoesNotCancelAnExistingFlight() {
        val snapshot = note("stopped").capture(); val ledger = PreviewSaveLedger(); ledger.offer(snapshot)
        val unavailable = ledger.admitNextWrite(0, snapshot.encodingWorkByteCharge, false)
        assertEquals(PreviewWriteDisposition.BACKEND_UNAVAILABLE, unavailable.disposition)
        assertEquals(snapshot.documentId, unavailable.documentId); assertNull(unavailable.attempt)
        assertSame(snapshot, ledger.latest(snapshot.documentId)); assertFalse(ledger.states().single().inFlight)
        val flight = assertNotNull(ledger.admitNextWrite(snapshot.encodingWorkByteCharge, snapshot.encodingWorkByteCharge, true).attempt)
        assertEquals(1L, flight.number)
        assertEquals(PreviewWriteDisposition.IDLE, ledger.admitNextWrite(0, snapshot.encodingWorkByteCharge, false).disposition)
        assertTrue(ledger.complete(flight, true)); assertTrue(ledger.isDrained)
    }
}
