package dev.notecore.canvas.prototype

import dev.notecore.canvas.preview.*
import dev.notecore.document.*
import kotlin.test.*

class PreviewSaveLedgerTest {
    private fun PagedPreviewNotebook.saveCapture() = capture()
    private fun note(id: String = "note") = PagedPreviewNotebook.create(id, "first", "ledger-test")
    @Test fun completeLatestSnapshotSupersedesUnsentContentAndCountsOnlyRetainedVersion() {
        val ledger = PreviewSaveLedger(); val note = note(); val first = note.saveCapture()
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(first))
        note.rename("second"); val second = note.saveCapture(); ledger.offer(second)
        assertSame(second, ledger.latest("note")); assertEquals(second.retainedByteCharge, ledger.chargedBytes)
        val attempt = assertNotNull(ledger.nextWrite()); assertSame(second, attempt.snapshot)
        assertNull(ledger.nextWrite()); assertTrue(ledger.complete(attempt, true)); assertTrue(ledger.isDrained)
    }
    @Test fun runningOldWriteKeepsBudgetAndCannotEraseLatestAcceptedVersion() {
        val ledger = PreviewSaveLedger(); val note = note(); val first = note.saveCapture(); ledger.offer(first)
        val old = assertNotNull(ledger.nextWrite()); note.rename("latest"); val latest = note.saveCapture(); ledger.offer(latest)
        assertEquals(first.retainedByteCharge + latest.retainedByteCharge, ledger.chargedBytes)
        assertNull(ledger.nextWrite()); assertTrue(ledger.complete(old, true)); assertSame(latest, ledger.latest("note"))
        assertEquals(latest.retainedByteCharge, ledger.chargedBytes)
        val next = assertNotNull(ledger.nextWrite()); assertSame(latest, next.snapshot)
        assertTrue(ledger.complete(next, true)); assertEquals(0, ledger.documentCount)
    }
    @Test fun failedWriteRetainsOriginalUntilExplicitOfferRetriesAndDuplicateCompletionCannotFreeIt() {
        val ledger = PreviewSaveLedger(); val snapshot = note().saveCapture(); ledger.offer(snapshot)
        val attempt = assertNotNull(ledger.nextWrite()); assertTrue(ledger.complete(attempt, false))
        assertTrue(ledger.states().single().retryRequired); assertNull(ledger.nextWrite())
        assertFalse(ledger.complete(attempt, true)); assertSame(snapshot, ledger.latest("note"))
        assertEquals(snapshot.retainedByteCharge, ledger.chargedBytes)
        ledger.offer(snapshot); val retry = assertNotNull(ledger.nextWrite()); assertNotSame(attempt, retry)
        assertTrue(ledger.complete(retry, true)); assertTrue(ledger.isDrained)
    }
    @Test fun unknownOldWriteDoesNotBlockNewerCompleteReplacement() {
        val ledger = PreviewSaveLedger(); val note = note(); ledger.offer(note.saveCapture()); val attempt = assertNotNull(ledger.nextWrite())
        note.rename("newer"); val newer = note.saveCapture(); ledger.offer(newer)
        ledger.complete(attempt, false); assertFalse(ledger.states().single().retryRequired)
        assertSame(newer, assertNotNull(ledger.nextWrite()).snapshot)
    }
    @Test fun byteRejectionIsAtomicAndCallerStillOwnsRejectedSnapshot() {
        val note = note(); val first = note.saveCapture(); val ledger = PreviewSaveLedger(byteBudget = first.retainedByteCharge)
        ledger.offer(first); note.rename("a much longer title than first"); val bigger = note.saveCapture()
        assertEquals(PreviewSaveAdmission.BYTE_CAPACITY, ledger.offer(bigger))
        assertSame(first, ledger.latest("note")); assertEquals(first.retainedByteCharge, ledger.chargedBytes)
        assertEquals("a much longer title than first", bigger.title)
    }
    @Test fun newVersionMayFitWhenUnsentOldVersionIsSupersededButNotWhileBothAreRetained() {
        val note = note(); val first = note.saveCapture(); note.rename("second"); val second = note.saveCapture()
        val ledger = PreviewSaveLedger(byteBudget = second.retainedByteCharge)
        ledger.offer(first); assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(second))
        val attempt = assertNotNull(ledger.nextWrite()); note.rename("third"); val third = note.saveCapture()
        assertEquals(PreviewSaveAdmission.BYTE_CAPACITY, ledger.offer(third)); assertSame(second, ledger.latest("note"))
        assertTrue(ledger.complete(attempt, true)); assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(third))
    }
    @Test fun perDocumentSingleFlightAndOtherDocumentsRemainEligible() {
        val ledger = PreviewSaveLedger(maxDocuments = 2)
        val first = note("a").saveCapture(); val second = note("b").saveCapture(); val third = note("c").saveCapture()
        ledger.offer(first); ledger.offer(second)
        assertEquals(PreviewSaveAdmission.DOCUMENT_CAPACITY, ledger.offer(third)); assertEquals(2, ledger.documentCount)
        val a = assertNotNull(ledger.nextWrite()); val b = assertNotNull(ledger.nextWrite())
        assertEquals("a", a.snapshot.documentId); assertEquals("b", b.snapshot.documentId); assertNull(ledger.nextWrite())
        ledger.complete(a, true); assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(third))
    }
    @Test fun foreignAndLateAttemptsCannotClearReopenedDocumentResponsibility() {
        val first = PreviewSaveLedger(); val second = PreviewSaveLedger(); val snapshot = note().saveCapture()
        first.offer(snapshot); second.offer(snapshot); val a = assertNotNull(first.nextWrite()); val b = assertNotNull(second.nextWrite())
        assertFalse(first.complete(b, true)); assertFalse(second.returnUnstarted(a))
        assertTrue(first.complete(a, true)); first.offer(snapshot); val reopened = assertNotNull(first.nextWrite())
        assertFalse(first.complete(a, true)); assertSame(snapshot, first.latest("note")); assertTrue(first.complete(reopened, true))
    }
    @Test fun rejectedBackendAdmissionReturnsUnstartedWithoutLosingSnapshotOrBudget() {
        val ledger = PreviewSaveLedger(); val snapshot = note().saveCapture(); ledger.offer(snapshot)
        val attempt = assertNotNull(ledger.nextWrite()); assertTrue(ledger.returnUnstarted(attempt))
        assertFalse(ledger.returnUnstarted(attempt)); assertEquals(snapshot.retainedByteCharge, ledger.chargedBytes)
        assertSame(snapshot, assertNotNull(ledger.nextWrite()).snapshot)
    }
    @Test fun staleVersionCannotOverwritePendingNewerAndSameVersionRetryKeepsOriginal() {
        val ledger = PreviewSaveLedger(); val note = note(); val old = note.saveCapture(); note.rename("newer"); val newer = note.saveCapture()
        ledger.offer(newer); assertEquals(PreviewSaveAdmission.STALE_VERSION, ledger.offer(old)); assertSame(newer, ledger.latest("note"))
        val equivalent = note.saveCapture(); assertNotSame(equivalent, newer)
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(equivalent)); assertSame(newer, ledger.latest("note"))
    }
    @Test fun boundedCatalogHeaderKeepsEscapedUnicodeTitleWithoutDecodingStrokeBody() {
        val notebook = PagedPreviewNotebook.create("header", "标题\t\n\\", "header-test")
        notebook.beginStroke(1.0, 2.0, 0.5f, 1); notebook.endStroke(3.0, 4.0, 0.5f, 2)
        val encoded = notebook.saveCapture().encode()
        val header = PagedPreviewCodec.header(encoded.lineSequence().take(2).joinToString("\n") + "\n")
        assertEquals(notebook.title, header.title); assertEquals(1, header.objectCount)
        assertEquals(notebook.changeVersion, header.revision)
        assertEquals("header", header.documentId)
    }
    @Test fun catalogHeaderRejectsUnsupportedTruncatedInvalidAndOversizedData() {
        listOf("NCPREVIEW\t9\nD\tx\ttitle\t1\t0\t0\n", "NCPREVIEW\t1\n",
            "NCPREVIEW\t1\nD\tx\ttitle\t0\t0\t0\n", "NCPREVIEW\t1\nD\tx\ttitle\t1\t501\t0\n",
            "NCPREVIEW\t1\nD\tx\ttitle\t1\t0\t-1\n", "x".repeat(4097)).forEach {
            assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.header(it) }
        }
    }

}
