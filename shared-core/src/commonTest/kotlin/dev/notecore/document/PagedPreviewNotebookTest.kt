package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PagedPreviewNotebookTest {
    @Test fun reusedNonceSkipsInitialIdentitiesAcrossKindsWithoutDuplicatingIndex() {
        val root = DocumentRoot.empty(SceneId("collision"), "title",
            PageDescriptor(PageId("reuse-op-1"), LayerId("reuse-ink-3"), 595.0, 842.0))
        val note = assertNotNull(PagedPreviewNotebook.open(root, "reuse").notebook)
        draw(note)
        assertEquals("reuse-op-2", note.authorityStatus().nextUndoOperationId?.value)
        assertEquals("reuse-ink-4", note.stroke(0).id.value)
        val reopened = assertNotNull(PagedPreviewNotebook.open(note.capture().root, "reuse").notebook)
        draw(reopened)
        assertEquals(2, reopened.totalStrokeCount)
        assertNotEquals(reopened.stroke(0).id, reopened.stroke(1).id)
        assertEquals(root.page(0), reopened.snapshot().page(0).copy(contentRevision = 0))
    }
    @Test fun invalidMetadataDoesNotCancelAcceptedUnfinishedSamples() {
        val note = note()
        assertTrue(note.beginStroke(1.0, 2.0, 0.5f, 1))
        val before = note.snapshot(); val status = note.authorityStatus()
        assertFalse(note.setPaper(Double.NaN, 600.0, PaperKind.GRID))
        assertTrue(note.hasActiveStroke)
        assertFalse(note.rename("\uD800"))
        assertTrue(note.hasActiveStroke)
        assertSame(before, note.snapshot()); assertEquals(status, note.authorityStatus())
        assertTrue(note.endStroke(3.0, 4.0, 0.5f, 2)); assertEquals(2, note.pointCount(0))
    }
    @Test fun retiredOwnerCannotAcceptLateInputOrEditWhileImmutableCaptureRemainsReadable() {
        val note = note(); draw(note); val before = note.snapshot()
        assertTrue(note.beginStroke(4.0, 5.0, 0.5f, 4)); assertTrue(note.retire())
        assertFalse(note.hasActiveStroke); assertFalse(note.canUndo); assertFalse(note.canRedo)
        assertFalse(note.endStroke(5.0, 6.0, 0.5f, 5)); assertFalse(note.beginStroke(1.0, 2.0, 0.5f, 6))
        assertFalse(note.rename("late")); assertFalse(note.insertPage()); assertFalse(note.selectPage(note.currentPageId))
        assertFalse(note.selectPen(1, 2.0)); assertFalse(note.undo()); assertSame(before, note.capture().root)
    }
    private fun note() = PagedPreviewNotebook.create("document", "title", "session-a")
    private fun draw(note: PagedPreviewNotebook, offset: Double = 0.0) {
        assertTrue(note.beginStroke(1.0 + offset, 2.0, 0.3f, 1))
        assertTrue(note.appendStroke(2.0 + offset, 3.0, 0.5f, 2))
        assertTrue(note.endStroke(3.0 + offset, 4.0, 0.7f, 3))
    }
    @Test fun selectionAndMoveKeepStablePageIdentityAndOneDocumentRevision() {
        val note = note(); val first = note.currentPageId
        draw(note); assertTrue(note.insertPage()); val second = note.currentPageId
        assertEquals(0, note.strokeCount); draw(note, 10.0)
        val before = note.snapshot(); val version = note.changeVersion
        assertTrue(note.selectPage(first)); assertSame(before, note.snapshot()); assertEquals(version, note.changeVersion)
        assertEquals(1, note.strokeCount); assertEquals(2, note.totalStrokeCount)
        assertTrue(note.moveCurrentPage(null)); assertEquals(first, note.currentPageId)
        assertEquals(listOf(second, first), note.snapshot().pages().map { it.id })
        assertEquals(version + 1, note.changeVersion); assertEquals(note.changeVersion, note.snapshot().scene.revision)
        assertEquals(before.findPage(first), note.snapshot().findPage(first))
    }
    @Test fun copyingUsesFreshIdentitiesSharesSamplesAndInsertsAfterCurrentPage() {
        val note = note(); draw(note); val original = note.stroke(0); val first = note.currentPageId
        assertTrue(note.insertPage()); val last = note.currentPageId
        assertTrue(note.selectPage(first)); assertTrue(note.copyCurrentPage()); val copied = note.currentPageId
        assertEquals(listOf(first, copied, last), note.snapshot().pages().map { it.id })
        assertNotEquals(original.id, note.stroke(0).id); assertNotEquals(original.layerId, note.stroke(0).layerId)
        assertSame(original.samples, note.stroke(0).samples); assertEquals(2, note.totalStrokeCount)
        assertTrue(note.undo()); assertEquals(last, note.currentPageId); assertNull(note.snapshot().findPage(copied))
        assertTrue(note.redo()); assertEquals(last, note.currentPageId) // Undo/redo preserves a valid selection.
        assertTrue(note.selectPage(copied)); assertEquals(3L, note.stroke(0).objectRevision)
    }
    @Test fun deletingSelectedPageSelectsSuccessorThenPredecessorAndLastPageClearKeepsIdentity() {
        val note = note(); val first = note.currentPageId; assertTrue(note.insertPage()); val second = note.currentPageId
        assertTrue(note.insertPage()); val third = note.currentPageId
        assertTrue(note.selectPage(second)); assertTrue(note.deleteCurrentPage()); assertEquals(third, note.currentPageId)
        assertTrue(note.deleteCurrentPage()); assertEquals(first, note.currentPageId)
        draw(note); assertTrue(note.setPaper(612.0, 792.0, PaperKind.DOTS)); assertTrue(note.deleteCurrentPage())
        assertEquals(first, note.currentPageId); assertEquals(1, note.pageCount); assertEquals(PaperKind.BLANK, note.currentPage.paper)
        assertEquals(0, note.strokeCount); assertTrue(note.undo()); assertEquals(1, note.strokeCount)
        assertEquals(PaperKind.DOTS, note.currentPage.paper); assertEquals(612.0, note.currentPage.width)
    }
    @Test fun pageSwitchCancelsUnfinishedInkAndLateUpCannotWriteToNewPage() {
        val note = note(); val first = note.currentPageId; assertTrue(note.insertPage()); val second = note.currentPageId
        assertTrue(note.selectPage(first)); val before = note.snapshot()
        assertTrue(note.beginStroke(1.0, 2.0, 0.5f, 1)); assertTrue(note.selectPage(first)); assertTrue(note.hasActiveStroke)
        assertTrue(note.selectPage(second)); assertFalse(note.hasActiveStroke)
        assertFalse(note.endStroke(3.0, 4.0, 0.5f, 2)); assertSame(before, note.snapshot()); assertEquals(0, note.totalStrokeCount)
        assertTrue(note.beginStroke(1.0, 2.0, 0.5f, 3)); assertTrue(note.setPaper(400.0, 600.0, PaperKind.GRID)); assertFalse(note.hasActiveStroke)
        assertEquals(0, note.totalStrokeCount)
    }
    @Test fun capturedBrushSurvivesToolChangeAndOnlySelectedProjectionIsRebuilt() {
        val note = note(); assertTrue(note.selectPen(123, 4.0)); assertTrue(note.beginStroke(1.0, 2.0, 0.3f, 1))
        assertTrue(note.selectPen(456, 8.0)); assertTrue(note.endStroke(3.0, 4.0, 0.8f, 2))
        assertEquals(123, note.strokeColor(0)); assertEquals(4.0, note.strokeWidth(0))
        val original = note.stroke(0); repeat(10) { assertSame(original, note.stroke(0)) }
        assertTrue(note.insertPage()); draw(note); assertEquals(456, note.strokeColor(0))
        assertEquals(2, note.totalStrokeCount); assertEquals(1, note.strokeCount)
    }
    @Test fun completedRejectedInkRetainsExactCommandAndSamplesAcrossCancelAndAllPageActions() {
        val root = DocumentRoot.empty(SceneId("bounded"), "title", PageDescriptor(PageId("a"), LayerId("a"), 595.0, 842.0))
        val fault = PendingInputOwnerFault(root, "bounded-session"); val note = fault.note
        draw(note); val before = note.snapshot(); assertTrue(note.beginStroke(10.0, 20.0, 0.5f, 4))
        fault.arm(); assertFailsWith<IllegalStateException> { note.endStroke(30.0, 40.0, 0.7f, 5) }; val pending = note.pendingStroke()!!
        val command = pending.command as CanvasCommand.AddStroke
        assertEquals(PageId("a"), pending.pageId); assertEquals(2, command.samples.size); assertEquals(10.0, command.samples.x(0))
        note.cancelStroke(); assertTrue(note.hasPendingStroke)
        assertFalse(note.retire()); assertSame(pending, note.pendingStroke())
        assertFalse(note.insertPage()); assertFalse(note.copyCurrentPage()); assertFalse(note.deleteCurrentPage())
        assertFalse(note.moveCurrentPage(null)); assertFalse(note.setPaper(400.0, 600.0, PaperKind.GRID)); assertFalse(note.rename("changed"))
        assertFalse(note.undo()); assertFalse(note.redo()); fault.arm(); assertFailsWith<IllegalStateException> { note.retryPendingStroke() }
        assertFalse(note.beginStroke(1.0, 2.0, 0.5f, 6))
        assertSame(pending, note.pendingStroke()); assertSame(before, note.snapshot())
        assertEquals(1, note.capture().root.scene.objectCount) // Capture never claims the retained completion was committed.
    }
    @Test fun snapshotContainsAllPagesAndStaysImmutableDuringSubsequentChangesAndEncoding() {
        val note = note(); draw(note); val old = note.capture(); val encoded = old.encode()
        assertTrue(note.insertPage()); draw(note, 10.0); val all = note.capture()
        assertEquals(2, all.root.pageCount); assertEquals(2, all.root.scene.objectCount)
        assertEquals(all.retainedByteCharge + 128L + 2 * 8L, note.authorityStatus().liveByteCharge)
        assertEquals(encoded, old.encode()); assertEquals(1, old.root.pageCount)
        val reopened = PagedPreviewNotebook(PagedPreviewCodec.decode(all.encode()), "fresh-session")
        assertEquals(2, reopened.totalStrokeCount); assertEquals(2, reopened.pageCount); assertFalse(reopened.canUndo)
        draw(reopened, 20.0); assertEquals(3, reopened.totalStrokeCount)
        assertTrue(reopened.stroke(1).id.value.startsWith("fresh-session-"))
    }
    @Test fun transformedProjectionAndPageDimensionsComeFromCanonicalRoot() {
        val samples = StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(0.5f), longArrayOf(1))
        val obj = StrokeObject(ObjectId("old"), 7, LayerId("ink"), BrushSpec(BrushKind.MONOLINE, 1, 2.0, 1), samples, Affine2D(a = 2.0, d = 3.0, tx = 4.0, ty = 5.0))
        val root = DocumentRoot.compose("restored", SceneSnapshot.restored(SceneId("restored"), 8, listOf(obj)),
            listOf(PageDescriptor(PageId("a"), obj.layerId, 400.0, 600.0, PaperKind.GRID, 8)))
        val note = PagedPreviewNotebook(root, "new-session")
        assertEquals(6.0, note.pointX(0, 0)); assertEquals(11.0, note.pointY(0, 0)); assertEquals(400.0, note.currentPage.width)
        assertSame(obj.samples, note.snapshot().scene.find(obj.id)!!.samples)
    }
    @Test fun invalidTargetsTitlesAndSampleSequencesCannotPartiallyMutateSessionContent() {
        val note = note(); val before = note.snapshot()
        assertFalse(note.selectPage(PageId("missing"))); assertFalse(note.rename("\uD800"))
        assertFalse(note.setPaper(Double.NaN, 600.0, PaperKind.GRID)); assertFalse(note.selectPen(1, Double.NaN))
        assertFalse(note.beginStroke(Double.NaN, 2.0, 0.5f, 1)); assertFalse(note.hasActiveStroke)
        assertTrue(note.beginStroke(1.0, 2.0, 0.5f, 3)); assertFalse(note.appendStroke(2.0, 3.0, 0.5f, 2))
        assertFalse(note.hasActiveStroke); assertSame(before, note.snapshot())
        assertTrue(note.rename("x".repeat(119)+"😀")); assertEquals("x".repeat(119), note.title)
        assertEquals(note.title, PagedPreviewCodec.decode(note.capture().encode()).title)
    }
    @Test fun ownerGuardCoversPresentationToolsPendingAndSnapshotReads() {
        var allowed = true
        val root = DocumentRoot.empty(SceneId("guarded"), "title", PageDescriptor(PageId("a"), LayerId("a"), 595.0, 842.0))
        val note = PagedPreviewNotebook(root, "guarded-session", ownerCheck = { check(allowed) })
        allowed = false
        listOf<() -> Any?>({ note.currentPageId }, { note.lastError }, { note.hasActiveStroke }, { note.capture() }, { note.pendingStroke() },
            { note.selectPen(1, 2.0) }, { note.insertPage() }, { note.selectPage(PageId("a")) }, { note.cancelStroke() }).forEach {
            assertFailsWith<IllegalStateException> { it() }
        }
        allowed = true; draw(note); assertEquals(1, note.totalStrokeCount)
        assertFailsWith<IllegalArgumentException> { PagedPreviewNotebook(root, "invalid/non-uuid") }
    }
}
