package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PageProjectionBudgetTest {
    private val a = PageDescriptor(PageId("a"), LayerId("ink-a"), 595.0, 842.0)
    private val b = PageDescriptor(PageId("b"), LayerId("ink-b"), 595.0, 842.0)
    private fun stroke(id: String, page: PageDescriptor, points: Int = 2) = StrokeObject(ObjectId(id), 1, page.layerId,
        BrushSpec(BrushKind.MONOLINE, 1, 2.5, -1), StrokeSamples.copyOf(DoubleArray(points), DoubleArray(points),
            FloatArray(points) { .5f }, LongArray(points) { it + 1L }))
    private fun mixed(): DocumentRoot = DocumentRoot.compose("title", SceneSnapshot.restored(SceneId("mixed"), 0,
        listOf(stroke("a1", a), stroke("b1", b), stroke("a2", a))), listOf(a, b))
    private fun expectedLive(root: DocumentRoot): Long = PagedPreviewCodec.retainedByteCharge(root) +
        if (root.scene.objectCount == 0) 0L else 128L + root.scene.objectCount * 8L

    @Test fun pageProjectionKeepsCanonicalOrderAndOnlyOwnsReferences() {
        val root = mixed(); val projected = root.pageObjects(a.id)
        assertEquals(listOf("a1", "a2"), projected.map { it.id.value })
        for (obj in projected) assertSame(root.scene.find(obj.id), obj)
        assertEquals(listOf("b1"), root.pageObjects(b.id).map { it.id.value })
        assertTrue(root.pageObjects(PageId("missing")).isEmpty())
        (projected as MutableList).clear()
        assertEquals(listOf("a1", "b1", "a2"), root.scene.objects().map { it.id.value })
        assertEquals(listOf("a1", "a2"), root.pageObjects(a.id).map { it.id.value })
    }
    @Test fun projectionPrepaymentSurvivesReadsButReferencesClearBeforeAcceptedAndRejectedCommit() {
        val root = mixed(); val live = PagedPreviewCodec.retainedByteCharge(root) + 152L
        val refused = PagedPreviewNotebook.open(root, "projection", DocumentBudget(liveBytes = live - 1))
        assertNull(refused.notebook); assertEquals(DocumentRejection.LIVE_BYTE_CAPACITY, refused.documentReason)
        val note = PagedPreviewNotebook(root, "projection", DocumentBudget(liveBytes = live + 1024, journalEntries = 1))
        assertEquals(0L, note.retainedProjectionByteCharge)
        val beforeRead = note.authorityStatus()
        assertEquals(2, note.strokeCount); assertEquals(144L, note.retainedProjectionByteCharge)
        assertEquals(beforeRead, note.authorityStatus()); assertEquals(live, beforeRead.liveByteCharge)
        assertTrue(note.selectPage(b.id)); assertEquals(0L, note.retainedProjectionByteCharge)
        assertEquals(1, note.strokeCount); assertEquals(136L, note.retainedProjectionByteCharge)
        assertTrue(note.rename("changed")); assertEquals(0L, note.retainedProjectionByteCharge)
        assertEquals(1, note.strokeCount); val before = note.snapshot(); val status = note.authorityStatus()
        assertFalse(note.rename("refused")); assertSame(before, note.snapshot()); assertEquals(status, note.authorityStatus())
        assertEquals(DocumentRejection.JOURNAL_CAPACITY, note.lastFailure?.documentReason)
        assertEquals(0L, note.retainedProjectionByteCharge); assertEquals(1, note.strokeCount)
        assertEquals(136L, note.retainedProjectionByteCharge); assertTrue(note.retire())
        assertEquals(0L, note.retainedProjectionByteCharge); assertEquals(1, note.strokeCount)
        assertEquals(0L, note.retainedProjectionByteCharge) // Retired reads never reacquire a retained cache.
        assertNotNull(PagedPreviewNotebook.open(root, "exact", DocumentBudget(liveBytes = live)).notebook)
    }
    @Test fun projectedReferencesParticipateInDirectAndDownLiveAdmissionWithoutConsumingRefusedIds() {
        val empty = DocumentRoot.empty(SceneId("budget"), "title", a)
        val obj = stroke("p-ink-2", a)
        val target = DocumentRoot.compose(empty.title, SceneSnapshot.restored(empty.id, 0, listOf(obj)), listOf(a))
        val live = expectedLive(target)
        val command = DocumentCommand.Canvas(a.id, CanvasCommand.AddStroke(CommandId("p-op-1"), 0,
            StrokeTarget(empty.id, a.layerId, obj.brush), obj.id, obj.samples))
        val exact = MemoryDocumentAuthority(empty, DocumentBudget(liveBytes = live))
        assertIs<DocumentCommit.Accepted>(exact.commit(command)); assertEquals(live, exact.status().liveByteCharge)
        val short = MemoryDocumentAuthority(empty, DocumentBudget(liveBytes = live - 1))
        val before = short.status()
        assertEquals(DocumentRejection.LIVE_BYTE_CAPACITY, assertIs<DocumentCommit.Rejected>(short.commit(command)).reason)
        assertSame(empty, short.snapshot()); assertEquals(before, short.status()); assertFalse(short.identityWasUsed("p-op-1"))
        val note = PagedPreviewNotebook(empty, "p", DocumentBudget(liveBytes = live - 1))
        val rejected = note.offerRealBatch(PointerPhase.DOWN, 2, doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0, 2.0),
            floatArrayOf(.5f, .5f), longArrayOf(1, 2))
        assertFalse(rejected.admitted); assertEquals(0, rejected.acceptedSamples); assertSame(empty, note.snapshot())
        assertTrue(note.beginStroke(1.0, 1.0, .5f, 1))
        assertEquals(PreviewInputDisposition.COMMITTED, note.offerRealBatch(PointerPhase.UP, 1,
            doubleArrayOf(), doubleArrayOf(), floatArrayOf(), longArrayOf()).disposition)
        assertEquals("p-ink-2", note.strokeId(0)); assertEquals(1, note.pointCount(0))
        assertTrue(note.authorityStatus().liveByteCharge <= live - 1)
    }
    @Test fun copyDeleteUndoAndRedoUseTheSameProjectionEnvelope() {
        val note = PagedPreviewNotebook(DocumentRoot.empty(SceneId("flow"), "title", a), "flow")
        fun checkEnvelope() = assertEquals(expectedLive(note.snapshot()), note.authorityStatus().liveByteCharge)
        fun draw() { assertTrue(note.beginStroke(1.0, 2.0, .5f, 1)); assertTrue(note.endStroke(3.0, 4.0, .5f, 2)); checkEnvelope() }
        checkEnvelope(); draw(); assertTrue(note.insertPage()); draw()
        assertTrue(note.copyCurrentPage()); assertEquals(3, note.totalStrokeCount); checkEnvelope()
        assertTrue(note.deleteCurrentPage()); assertEquals(2, note.totalStrokeCount); checkEnvelope()
        assertTrue(note.undo()); assertEquals(3, note.totalStrokeCount); checkEnvelope()
        assertTrue(note.redo()); assertEquals(2, note.totalStrokeCount); checkEnvelope()
        assertTrue(note.deleteCurrentPage()); checkEnvelope()
        assertTrue(note.deleteCurrentPage()); assertEquals(0, note.totalStrokeCount); checkEnvelope()
        assertTrue(note.undo()); assertEquals(1, note.totalStrokeCount); checkEnvelope()
        assertTrue(note.redo()); assertEquals(0, note.totalStrokeCount); checkEnvelope()
    }
}
