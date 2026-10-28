package dev.notecore.document

import dev.notecore.canvas.preview.PreviewViewport
import dev.notecore.canvas.prototype.*
import kotlin.test.*

class ReadingPositionRulesTest {
    private val a = PageDescriptor(PageId("a"), LayerId("la"), 1000.0, 1400.0)
    private val b = PageDescriptor(PageId("b"), LayerId("lb"), 1000.0, 1400.0)
    private val root = DocumentRoot.compose("reading", SceneSnapshot.empty(SceneId("doc")), listOf(a, b))
    private fun note() = PagedPreviewNotebook(root, "position-session")
    private fun viewport() = PreviewViewport().also { it.resize(1000.0, 1400.0) }
    private val position = ReadingPosition("local", "doc", "b", 600.0, 800.0, 3.0)

    @Test fun capturedPositionRestoresInNewSessionAndWindowWithoutChangingContent() {
        val old = note(); assertTrue(old.selectPage(b.id))
        val view = viewport(); view.gesture(500.0, 700.0, 200.0, 400.0, 3.0)
        val saved = assertNotNull(ReadingPositionRules.capture("local", old, view).position)
        val encoded = PagedPreviewCodec.encode(old.snapshot()); assertTrue(old.retire())
        val fresh = PagedPreviewNotebook(PagedPreviewCodec.decode(encoded), "new-reading")
        val newView = PreviewViewport().also { it.resize(1400.0, 1000.0) }
        assertNull(ReadingPositionRules.restore("local", fresh, newView, saved))
        assertEquals(b.id, fresh.currentPageId); assertEquals(saved.zoom, newView.zoom)
        val center = newView.toScene(700.0, 500.0)
        assertEquals(saved.centerX, center.x, 1e-9); assertEquals(saved.centerY, center.y, 1e-9)
        assertEquals(encoded, PagedPreviewCodec.encode(fresh.snapshot()))
    }

    @Test fun reorderKeepsIdentityAndDeletedPageNeverFallsBackToSameIndex() {
        val reordered = DocumentRoot.compose(root.title, root.scene, listOf(b, a))
        val note = PagedPreviewNotebook(reordered, "reordered")
        val view = viewport()
        assertNull(ReadingPositionRules.restore("local", note, view, position)); assertEquals(0, note.currentPageIndex)
        assertTrue(note.deleteCurrentPage()); val before = view.toView(25.0, 30.0)
        assertEquals(ReadingPositionFailure.MISSING_PAGE, ReadingPositionRules.restore("local", note, view, position))
        assertEquals(a.id, note.currentPageId); assertEquals(before, view.toView(25.0, 30.0))
    }

    @Test fun invalidRecordsAndForeignSpaceNeverChangeSelectionOrViewport() {
        val note = note(); val view = viewport(); val before = view.toView(25.0, 30.0)
        for (record in listOf(position.copy(centerX = Double.NaN), position.copy(centerY = -1.0),
            position.copy(zoom = Double.POSITIVE_INFINITY), position.copy(zoom = 0.25), position.copy(pageId = "\uD800"))) {
            assertEquals(ReadingPositionFailure.INVALID_RECORD, ReadingPositionRules.restore("local", note, view, record))
            assertEquals(a.id, note.currentPageId); assertEquals(before, view.toView(25.0, 30.0))
        }
        for (record in listOf(position.copy(spaceId = "other"), position.copy(documentId = "other"))) {
            assertEquals(ReadingPositionFailure.WRONG_DOCUMENT, ReadingPositionRules.restore("local", note, view, record))
            assertEquals(a.id, note.currentPageId); assertEquals(before, view.toView(25.0, 30.0))
        }
    }

    @Test fun zeroLayoutOrMismatchedPageCannotOverwriteLastValidCapture() {
        val note = note(); val view = viewport()
        val saved = assertNotNull(ReadingPositionRules.capture("local", note, view).position)
        view.resize(0.0, 0.0)
        assertEquals(ReadingPositionFailure.INVALID_LAYOUT, ReadingPositionRules.capture("local", note, view).failure)
        assertEquals(ReadingPositionFailure.INVALID_LAYOUT, ReadingPositionRules.restore("local", note, view, saved))
        view.resize(1000.0, 1400.0); view.configurePage(595.0, 842.0)
        assertEquals(ReadingPositionFailure.VIEWPORT_MISMATCH, ReadingPositionRules.capture("local", note, view).failure)
        assertEquals("a", saved.pageId)
    }

    @Test fun activeAndPendingInputBlockRestoreWithoutCancellation() {
        val note = note(); val view = viewport(); val before = view.toView(25.0, 30.0)
        assertTrue(note.beginStroke(1.0, 2.0, .5f, 1)); val charge = note.inputReservedByteCharge
        assertEquals(ReadingPositionFailure.INPUT_OWNED, ReadingPositionRules.restore("local", note, view, position))
        assertEquals(a.id, note.currentPageId); assertEquals(before, view.toView(25.0, 30.0)); assertEquals(charge, note.inputReservedByteCharge)
        assertTrue(note.endStroke(3.0, 4.0, .6f, 2))
        val command = DocumentCommand.Canvas(a.id, CanvasCommand.AddStroke(CommandId("pending"), 0,
            StrokeTarget(root.id, a.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)), ObjectId("pending-ink"),
            StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))))
        val pending = assertNotNull(PagedPreviewNotebook.openRecovery(PagedPreviewSnapshot(root, command), "position-recovery").notebook)
        assertEquals(ReadingPositionFailure.INPUT_OWNED, ReadingPositionRules.restore("local", pending, view, position))
        assertSame(command, pending.pendingStroke()); assertEquals(before, view.toView(25.0, 30.0))
    }

    @Test fun closedSessionRefusesRestoreBeforeViewportChangesAndReadOnlyCanNavigate() {
        val note = note(); val view = viewport(); assertTrue(note.retire()); val before = view.toView(25.0, 30.0)
        assertEquals(ReadingPositionFailure.SESSION_REJECTED, ReadingPositionRules.restore("local", note, view, position))
        assertEquals(before, view.toView(25.0, 30.0))
        val reading = note(); assertTrue(reading.setReadOnly(true))
        assertNull(ReadingPositionRules.restore("local", reading, view, position))
        assertTrue(reading.isReadOnly); assertEquals(b.id, reading.currentPageId); assertSame(root, reading.snapshot())
    }

    @Test fun changedPaperBoundsConstrainOldCenterEvenInLargeValidWindow() {
        val changed = DocumentRoot.compose(root.title, root.scene, listOf(a, b.copy(width = 1.0, height = 14400.0)))
        val note = PagedPreviewNotebook(changed, "changed-paper")
        val view = PreviewViewport().also { it.resize(1e7, 1e7) }
        val saved = position.copy(centerX = 14400.0, centerY = 14400.0, zoom = 6.0)
        assertNull(ReadingPositionRules.restore("local", note, view, saved))
        val center = view.toScene(5e6, 5e6)
        assertEquals(.5, center.x, 1e-6); assertEquals(13200.0, center.y, 1e-6)
        assertEquals(6.0, view.zoom); assertSame(changed, note.snapshot())
    }
}
