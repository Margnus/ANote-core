package dev.notecore.document

import kotlin.test.*

class PreviewRecoveryReaderTest {
    private fun checkpoint(withBaseStroke: Boolean = false): PagedPreviewSnapshot {
        val fault = PendingInputOwnerFault(nonce = "reader-pending")
        if (withBaseStroke) {
            assertTrue(fault.note.beginStroke(10.0, 20.0, .5f, 0)); assertTrue(fault.note.endStroke(11.0, 21.0, .75f, 0))
        }
        assertTrue(fault.note.beginStroke(1.25, 2.5, .25f, 1))
        fault.arm(); assertFailsWith<IllegalStateException> { fault.note.endStroke(3.5, 4.75, .75f, 2) }
        return fault.note.captureRecovery()
    }
    private fun refused(text: String, failure: PreviewReadFailure) {
        val result = PreviewRecoveryCodec.read(text)
        assertNull(result.snapshot); assertEquals(failure, result.failure)
    }
    @Test fun fullCheckpointRetainsPendingUntilOriginalOwnerExplicitlyCommitsOnce() {
        val original = checkpoint(); val result = PreviewRecoveryCodec.read(original.encode())
        assertNull(result.failure); val snapshot = assertNotNull(result.snapshot)
        assertTrue(original.sameContent(snapshot)); assertEquals(0, snapshot.root.scene.objectCount)
        val note = assertNotNull(PagedPreviewNotebook.openRecovery(snapshot, "reopened").notebook)
        assertTrue(note.hasPendingStroke); assertEquals(0, note.totalStrokeCount)
        assertTrue(note.retryPendingStroke()); assertTrue(note.retryPendingStroke()); assertEquals(1, note.totalStrokeCount)
        assertTrue(PreviewRecoveryCodec.read(note.capture().encode()).snapshot!!.pending == null)
    }
    @Test fun ordinaryV1V2RemainReadableWithoutManufacturingPending() {
        val v2 = PagedPreviewNotebook.create("ordinary", "title", "session").capture().encode()
        val v1 = "NCPREVIEW\t1\nD\tlegacy\ttitle\t2\t1\t1\nS\tink-1\t-1\t2.5\t1\nP\t1.0\t2.0\t0.5\t1\nEND\n"
        for (text in listOf(v1, v2)) {
            val result = PreviewRecoveryCodec.read(text)
            assertNull(result.failure); assertNull(assertNotNull(result.snapshot).pending)
            assertEquals(PagedPreviewCodec.encode(PreviewDocumentReader.read(text).root!!), result.snapshot!!.encode())
        }
    }
    @Test fun malformedOrOverCapacityInputNeverReturnsPartialBaseAndLaterReadStillWorks() {
        val original = checkpoint(); val text = original.encode()
        val rows = text.split('\n', limit = 4); val frame = rows[2].split('\t')
        val signed = rows[0] + "\n" + rows[1] + "\n" + frame[0] + "\t" + frame[1] + "\t-001" + frame[2].drop(4) + "\n" + rows[3]
        for (bad in listOf("", text.dropLast(1), text+"extra", text.replace("NCRECOVERY\t1", "NCRECOVERY\t9"), signed))
            refused(bad, PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT)
        refused("x".repeat(PagedPreviewCodec.MAX_CHARACTERS+1), PreviewReadFailure.CHARACTER_CAPACITY)
        assertTrue(PreviewRecoveryCodec.read(text).snapshot!!.sameContent(original))
    }
    @Test fun validButUnsupportedOrdinaryAndEnvelopeContentKeepCapabilityRefusal() {
        val fault = PendingInputOwnerFault(nonce = "unsupported")
        assertTrue(fault.note.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(fault.note.endStroke(3.0, 4.0, .5f, 2))
        val text = fault.note.capture().encode()
        for (brush in listOf("PRESSURE", "HIGHLIGHTER")) {
            val unsupported = text.replace("MONOLINE", brush)
            assertNotNull(PagedPreviewCodec.decode(unsupported))
            refused(unsupported, PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT)
        }
        for (withBase in listOf(false, true)) {
            val checkpoint = checkpoint(withBase).encode()
            // PRESSURE has the same UTF-16 length as MONOLINE: framing remains valid for both base and pending payload.
            refused(checkpoint.replace("MONOLINE", "PRESSURE"), PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT)
        }
    }
}
