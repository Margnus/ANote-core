package dev.notecore.canvas.prototype

import dev.notecore.canvas.preview.PreviewNotebook
import kotlin.test.*

class PreviewNotebookContractTest {
    @Test fun importedBackupGetsIndependentIdentityAndPreservesEverySampleAndBrush() {
        val source = PreviewNotebook("original", "备份\\中文")
        source.selectPen(-12345, 9.0); stroke(source); stroke(source, 20.0); source.undo()
        val backup = source.capture().encode()
        val copy = PreviewNotebook.importAsCopy(backup, "copy")
        assertEquals("copy", copy.documentId); assertEquals(source.title, copy.title)
        assertEquals(1, copy.strokeCount); assertFalse(copy.canRedo)
        assertEquals(backup.lines().drop(2), copy.capture().encode().lines().drop(2))
        assertEquals(0, copy.changeVersion)
        stroke(copy, 50.0); assertNotEquals(copy.strokeId(0), copy.strokeId(1))
        assertEquals(backup, source.capture().encode())
    }
    @Test fun importRejectsReuseInvalidIdentityAndCorruptionBeforeCreatingCopy() {
        val source = PreviewNotebook("original", "test"); stroke(source)
        val backup = source.capture().encode()
        assertFailsWith<IllegalArgumentException> { PreviewNotebook.importAsCopy(backup, "original") }
        assertFailsWith<IllegalArgumentException> { PreviewNotebook.importAsCopy(backup, "") }
        assertFailsWith<IllegalArgumentException> { PreviewNotebook.importAsCopy(backup.substringBefore("END"), "copy") }
    }
    private fun stroke(note: PreviewNotebook, offset: Double = 0.0) {
        assertTrue(note.beginStroke(10.0 + offset, 20.0, 0.4f, 0))
        assertTrue(note.appendStroke(20.0 + offset, 30.0, 0.7f, 1000))
        assertTrue(note.endStroke(30.0 + offset, 40.0, 0.5f, 2000))
    }
    @Test fun savedSnapshotRoundTripsExactContentAndIdentity() {
        val note = PreviewNotebook("test-page", "中文\\名称\t\n")
        note.selectPen(0xff3568be.toInt(), 5.0); stroke(note)
        val encoded = note.capture().encode(); val loaded = PreviewNotebook.restore(encoded)
        assertEquals(encoded, loaded.capture().encode())
        assertEquals(note.title, loaded.title); assertEquals(1, loaded.strokeCount)
        assertEquals(note.strokeId(0), loaded.strokeId(0)); assertEquals(5.0, loaded.strokeWidth(0))
        assertEquals(0xff3568be.toInt(), loaded.strokeColor(0)); assertEquals(0.7f, loaded.pointPressure(0, 1))
        stroke(loaded, 50.0); assertNotEquals(loaded.strokeId(0), loaded.strokeId(1))
    }
    @Test fun immutableCaptureRemainsStableWhileOwnerContinuesEditing() {
        val note = PreviewNotebook("test", "before"); stroke(note)
        val capture = note.capture(); val original = capture.encode()
        note.rename("after"); stroke(note, 10.0); note.undo()
        assertEquals(original, capture.encode()); assertEquals("before", PreviewNotebook.restore(original).title)
    }
    @Test fun cancellationAndUnfinishedStrokeNeverPolluteSavedScene() {
        val note = PreviewNotebook("test", "test")
        note.beginStroke(0.0, 0.0, 0.5f, 0); note.appendStroke(1.0, 1.0, 0.5f, 1)
        assertEquals(0, PreviewNotebook.restore(note.capture().encode()).strokeCount)
        note.cancelStroke(); assertFalse(note.hasActiveStroke); stroke(note)
        assertEquals(1, note.strokeCount)
    }
    @Test fun undoRedoAndNewBranchPersistOnlyCanonicalVisibleScene() {
        val note = PreviewNotebook("test", "test"); stroke(note); stroke(note, 10.0)
        val lastId = note.strokeId(1)
        assertTrue(note.undo()); assertEquals(1, note.strokeCount)
        assertTrue(note.redo()); assertEquals(lastId, note.strokeId(1))
        note.undo(); stroke(note, 30.0); assertFalse(note.canRedo)
        val loaded = PreviewNotebook.restore(note.capture().encode())
        assertEquals(2, loaded.strokeCount); assertEquals(40.0, loaded.pointX(1, 0))
    }
    @Test fun invalidAndRegressingNativeInputFailsExplicitlyWithoutPartialCommit() {
        val note = PreviewNotebook("test", "test")
        assertFalse(note.beginStroke(Double.NaN, 1.0, 0.5f, 0)); assertFalse(note.hasActiveStroke)
        note.beginStroke(1.0, 1.0, 0.5f, 10)
        assertFalse(note.appendStroke(2.0, 2.0, 0.5f, 9)); assertFalse(note.hasActiveStroke)
        assertEquals(0, note.strokeCount); assertTrue(note.lastError.isNotBlank())
    }
    @Test fun parserRejectsCorruptionUnknownVersionsDuplicateIdsAndTrailingData() {
        val note = PreviewNotebook("test", "test"); stroke(note); stroke(note, 10.0)
        val encoded = note.capture().encode()
        val invalid = listOf(encoded.replace("NCPREVIEW\t1", "NCPREVIEW\t9"), encoded.substringBefore("END"),
            encoded.replace("S\tink-2", "S\tink-1"), encoded + "unexpected", encoded.replace("\t0.4\t0", "\tNaN\t0"),
            encoded.replace("S\tink-1", "S\tink-01"))
        for (value in invalid) assertFailsWith<IllegalArgumentException> { PreviewNotebook.restore(value) }
    }
    @Test fun previewStrokeAndDocumentCapsAreExplicitAndDoNotOverwriteAcceptedContent() {
        val note = PreviewNotebook("test", "test")
        repeat(PreviewNotebook.MAX_STROKES) { stroke(note, it.toDouble()) }
        val before = note.capture().encode()
        assertFalse(note.beginStroke(1.0, 1.0, 0.5f, 0)); assertEquals(before, note.capture().encode())
        assertEquals(PreviewNotebook.MAX_STROKES, PreviewNotebook.restore(before).strokeCount)
    }
    @Test fun brushIsCapturedAtDownEvenIfControlsChangeMidStroke() {
        val note = PreviewNotebook("test", "test"); note.selectPen(-1, 3.0)
        note.beginStroke(1.0, 1.0, 0.5f, 0); note.selectPen(-2, 9.0); note.endStroke(2.0, 2.0, 0.5f, 1)
        assertEquals(-1, note.strokeColor(0)); assertEquals(3.0, note.strokeWidth(0))
    }
    @Test fun privateDedupEpochRotationPreservesSceneAndUndoAcrossCountAndByteLimits() {
        val note = PreviewNotebook("test", "test")
        // Each long stroke charges enough to force the byte bound well before the entry bound.
        repeat(12) { turn ->
            assertTrue(note.beginStroke(0.0, turn.toDouble(), 0.5f, 0))
            for (p in 1..16000) assertTrue(note.appendStroke(p.toDouble(), turn.toDouble(), 0.5f, p.toLong()))
            assertTrue(note.endStroke(16001.0, turn.toDouble(), 0.5f, 16001))
            assertTrue(note.undo()); assertTrue(note.redo()); assertTrue(note.undo())
        }
        repeat(150) { stroke(note); assertTrue(note.undo()) }
        assertEquals(0, note.strokeCount); assertTrue(note.redo()); assertEquals(1, note.strokeCount)
        assertEquals(note.capture().encode(), PreviewNotebook.restore(note.capture().encode()).capture().encode())
    }
}
