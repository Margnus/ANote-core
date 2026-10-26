package dev.notecore.document

import kotlin.test.*

class PreviewReadTraversalTest {
    private fun note(guard: () -> Unit = {}): PagedPreviewNotebook = assertNotNull(
        PagedPreviewNotebook.createValidated("read", "title", "reader", ownerCheck = guard).notebook).also {
        assertTrue(it.beginStroke(10.0, 20.0, .25f, 1)); assertTrue(it.endStroke(30.0, 40.0, .75f, 2))
    }
    @Test fun retainedImmutableStrokeSurvivesSelectionUndoAndRetirement() {
        val n = note(); val page = n.currentPageId; val stroke = n.stroke(0)
        val checkpoint = n.capture(); val encoded = checkpoint.encode()
        assertTrue(n.insertPage()); assertEquals(0, n.strokeCount)
        assertTrue(n.selectPage(page)); assertSame(stroke, n.stroke(0))
        assertTrue(n.undo()); assertEquals(1, n.strokeCount) // Undo added page.
        assertTrue(n.undo()); assertEquals(0, n.strokeCount) // Undo committed stroke.
        assertTrue(n.retire()); assertEquals(0, n.strokeCount)
        assertEquals(encoded, checkpoint.encode()); assertSame(stroke, checkpoint.root.scene.objects().single())
        assertEquals(2, stroke.samples.size)
        assertEquals(10.0, stroke.samples.x(0)); assertEquals(40.0, stroke.samples.y(1))
        assertEquals(.25f, stroke.samples.pressure(0)); assertEquals(2L, stroke.samples.timeMicros(1))
    }
    @Test fun notebookReadsRequireOwnerButCapturedValueDoesNotTransferMutablePermission() {
        var owned = true; val n = note { check(owned) }; val stroke = n.stroke(0)
        val root = n.snapshot(); val checkpoint = n.capture(); val version = n.changeVersion
        owned = false
        for (read in listOf<() -> Any?>({ n.snapshot() }, { n.page(0) }, { n.strokeCount }, { n.stroke(0) },
            { n.pointX(0, 0) }, { n.pointPressure(0, 0) }, { n.captureRecovery() }))
            assertFailsWith<IllegalStateException> { read() }
        // Immutable value getters have no owner callback. They do not read or mutate the Notebook.
        assertEquals(30.0, stroke.samples.x(1)); assertSame(root, checkpoint.root)
        owned = true; assertSame(root, n.snapshot()); assertEquals(version, n.changeVersion)
        assertEquals("", n.lastError); assertSame(stroke, n.stroke(0))
    }
    @Test fun rawIndexPreconditionFailuresDoNotEditOrCancelAcceptedInput() {
        val n = note(); val stroke = n.stroke(0); val root = n.snapshot()
        assertTrue(n.beginStroke(1.0, 2.0, .5f, 3)); val operation = n.activeInputOperationId
        for (index in listOf(Int.MIN_VALUE, -1, 1, Int.MAX_VALUE)) {
            assertFailsWith<IndexOutOfBoundsException> { n.stroke(index) }
            assertFailsWith<IndexOutOfBoundsException> { n.page(index) }
            assertFailsWith<IndexOutOfBoundsException> { n.pointCount(index) }
        }
        for (index in listOf(Int.MIN_VALUE, -1, 2, Int.MAX_VALUE)) {
            assertFailsWith<IndexOutOfBoundsException> { n.pointX(0, index) }
            assertFailsWith<IndexOutOfBoundsException> { n.pointY(0, index) }
            assertFailsWith<IndexOutOfBoundsException> { n.pointPressure(0, index) }
            assertFailsWith<IndexOutOfBoundsException> { stroke.samples.timeMicros(index) }
        }
        assertSame(root, n.snapshot()); assertEquals(operation, n.activeInputOperationId); assertTrue(n.hasActiveStroke)
        assertEquals("", n.lastError); assertTrue(n.endStroke(3.0, 4.0, .5f, 4)); assertEquals(2, n.strokeCount)
    }
}
