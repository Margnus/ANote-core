package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class DocumentAuthorityTest {
    private val doc = SceneId("document")
    private val a = PageDescriptor(PageId("page-a"), LayerId("ink-a"), 595.0, 842.0, PaperKind.RULED)
    private val b = PageDescriptor(PageId("page-b"), LayerId("ink-b"), 595.0, 842.0)
    private fun root() = DocumentRoot.empty(doc, "title", a)
    private fun accept(authority: MemoryDocumentAuthority, command: DocumentCommand): DocumentReceipt = assertIs<DocumentCommit.Accepted>(authority.commit(command)).receipt
    private fun reject(authority: MemoryDocumentAuthority, command: DocumentCommand, reason: DocumentRejection) {
        val before = authority.snapshot(); val stats = authority.status()
        assertEquals(reason, assertIs<DocumentCommit.Rejected>(authority.commit(command)).reason)
        assertSame(before, authority.snapshot()); assertEquals(stats, authority.status())
    }
    private fun insert(authority: MemoryDocumentAuthority, page: PageDescriptor = b, before: PageId? = null): DocumentCommand.InsertPage =
        DocumentCommand.InsertPage(CommandId("insert-${page.id.value}"), doc, authority.snapshot().revision, page, before)
    private fun add(authority: MemoryDocumentAuthority, page: PageDescriptor = a, key: String = "stroke-a"): DocumentCommand.Canvas {
        val samples = StrokeSamples.copyOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0), floatArrayOf(0.5f, 0.7f), longArrayOf(1, 2))
        return DocumentCommand.Canvas(page.id, CanvasCommand.AddStroke(CommandId("add-$key"), authority.snapshot().revision,
            StrokeTarget(doc, page.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, 0xff000000.toInt())), ObjectId(key), samples))
    }
    @Test fun denseInitialAdmissionPreservesIdentityBudgetAndObjectEpochs() {
        val samples = StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))
        val objects = (1..5000).map { StrokeObject(ObjectId("dense-$it"), (it % 3 + 1).toLong(), a.layerId,
            BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1), samples) }
        val initial = DocumentRoot.compose("dense", SceneSnapshot.restored(doc, 7, objects), listOf(a.copy(contentRevision = 7)))
        val baseline = MemoryDocumentAuthority(initial).status()
        val exact = DocumentBudget(maxPages = 1, maxObjects = 5000, liveBytes = baseline.liveByteCharge,
            identityBytes = baseline.identityByteCharge)
        for ((budget, reason) in listOf(exact.copy(maxObjects = 4999) to DocumentRejection.OBJECT_CAPACITY,
            exact.copy(liveBytes = exact.liveBytes - 1) to DocumentRejection.LIVE_BYTE_CAPACITY,
            exact.copy(identityBytes = exact.identityBytes - 1) to DocumentRejection.IDENTITY_CAPACITY)) {
            assertEquals(reason, MemoryDocumentAuthority.initialCapacityFailure(initial, budget))
            assertFailsWith<IllegalArgumentException> { MemoryDocumentAuthority(initial, budget) }
        }
        val owner = MemoryDocumentAuthority(initial, exact)
        assertSame(initial, owner.snapshot()); assertEquals(baseline, owner.status())
        for (obj in objects) assertTrue(owner.identityWasUsed(obj.id.value))
        assertTrue(owner.identityWasUsed(a.id.value)); assertTrue(owner.identityWasUsed(a.layerId.value))
        val last = objects.last()
        reject(owner, DocumentCommand.Canvas(a.id, CanvasCommand.TransformStroke(CommandId("stale-dense"), doc, 7,
            last.id, last.objectRevision + 1, Affine2D())), DocumentRejection.SCENE_REJECTED)
        assertSame(last, initial.scene.find(last.id)); assertEquals(baseline, owner.status())
    }

    @Test fun allocationProbeUsesOriginalTombstonesAndJournalWithoutMutation() {
        var owned = true
        val authority = MemoryDocumentAuthority(root(), ownerCheck = { check(owned) })
        accept(authority, insert(authority)); accept(authority, add(authority))
        accept(authority, DocumentCommand.DeletePage(CommandId("remove-b"), doc, authority.snapshot().revision, b.id))
        assertNull(authority.snapshot().findPage(b.id))
        assertTrue(authority.identityWasUsed(b.id.value)); assertTrue(authority.identityWasUsed(b.layerId.value))
        accept(authority, DocumentCommand.DeletePage(CommandId("clear-a"), doc, authority.snapshot().revision, a.id))
        assertEquals(0, authority.snapshot().scene.objectCount)
        assertTrue(authority.identityWasUsed("stroke-a"))
        accept(authority, DocumentCommand.Undo(CommandId("undo-remove"), doc, authority.snapshot().revision, CommandId("clear-a")))
        val before = authority.snapshot(); val status = authority.status()
        for (id in listOf(a.id.value, a.layerId.value, b.id.value, b.layerId.value, "stroke-a", "add-stroke-a", "remove-b", "undo-remove"))
            assertTrue(authority.identityWasUsed(id), id)
        assertFalse(authority.identityWasUsed("fresh"))
        assertSame(before, authority.snapshot()); assertEquals(status, authority.status())
        owned = false
        assertFailsWith<IllegalStateException> { authority.identityWasUsed("fresh") }
    }
    @Test fun directoryIsDefensivelyOwnedAndRejectsOrphanObjectsOrDuplicateMembership() {
        val pages = mutableListOf(a); val initial = DocumentRoot.compose("title", SceneSnapshot.empty(doc), pages); pages.clear()
        assertEquals(1, initial.pageCount)
        val exposed = initial.pages().toMutableList(); exposed.clear(); assertEquals(1, initial.pageCount)
        assertFailsWith<IllegalArgumentException> { DocumentRoot.compose("x", SceneSnapshot.empty(doc), listOf(a, a)) }
        val authority = MemoryDocumentAuthority(initial); accept(authority, add(authority))
        assertFailsWith<IllegalArgumentException> { DocumentRoot.compose("x", authority.snapshot().scene, listOf(b)) }
    }
    @Test fun metadataAndCanvasShareExactlyOneRootRevisionAndImmutablePriorSnapshots() {
        val initial = root(); val authority = MemoryDocumentAuthority(initial)
        val inserted = accept(authority, insert(authority))
        assertEquals(1L, inserted.rootRevision); assertEquals(Durability.MEMORY_ONLY, inserted.durability)
        assertEquals(1L, authority.snapshot().revision); assertEquals(1L, authority.snapshot().scene.revision)
        assertEquals(0L, initial.revision); assertEquals(1, initial.pageCount)
        accept(authority, add(authority))
        assertEquals(2L, authority.snapshot().revision); assertEquals(2L, authority.snapshot().findPage(a.id)?.contentRevision)
        assertEquals(1L, authority.snapshot().findPage(b.id)?.contentRevision)
    }
    @Test fun movePreservesPageIdentityContentRevisionAndCanonicalObjectIdentity() {
        val authority = MemoryDocumentAuthority(root()); accept(authority, add(authority)); accept(authority, insert(authority))
        val prior = authority.snapshot(); val original = prior.pageObjects(a.id).single()
        accept(authority, DocumentCommand.MovePage(CommandId("move"), doc, prior.revision, b.id, a.id))
        assertEquals(listOf(b.id, a.id), authority.snapshot().pages().map { it.id })
        assertEquals(prior.findPage(a.id), authority.snapshot().findPage(a.id))
        assertSame(original, authority.snapshot().pageObjects(a.id).single())
        assertEquals(prior.revision + 1, authority.snapshot().revision)
    }
    @Test fun capturedTargetCannotBeRetargetedByPageOrderAndLateOldBaseIsRejected() {
        val authority = MemoryDocumentAuthority(root()); val old = add(authority)
        accept(authority, insert(authority, before = a.id))
        reject(authority, old, DocumentRejection.STALE_ROOT)
        val captured = old.command as CanvasCommand.AddStroke
        val resolved = DocumentCommand.Canvas(a.id, captured.copy(baseRevision = authority.snapshot().revision))
        accept(authority, resolved)
        assertEquals(1, authority.snapshot().pageObjects(a.id).size); assertTrue(authority.snapshot().pageObjects(b.id).isEmpty())
        reject(authority, DocumentCommand.Canvas(b.id, captured.copy(id = CommandId("wrong-layer"), baseRevision = authority.snapshot().revision)), DocumentRejection.WRONG_LAYER)
    }
    @Test fun copyAssignsFreshPageLayerAndObjectIdsWhileSharingImmutableSamples() {
        val authority = MemoryDocumentAuthority(root()); accept(authority, add(authority)); accept(authority, add(authority, key = "stroke-b"))
        val prior = authority.snapshot(); val mapping = mutableMapOf(ObjectId("stroke-a") to ObjectId("copy-a"), ObjectId("stroke-b") to ObjectId("copy-b"))
        val copy = DocumentCommand.CopyPage(CommandId("copy"), doc, prior.revision, a.id, prior.findPage(a.id)!!.contentRevision, b.id, b.layerId, mapping)
        mapping.clear(); val receipt = accept(authority, copy)
        assertEquals(prior.revision + 1, receipt.rootRevision); assertEquals(4, authority.snapshot().scene.objectCount)
        assertEquals(2, prior.scene.objectCount); assertEquals(2, authority.snapshot().pageObjects(b.id).size)
        for (source in prior.pageObjects(a.id)) {
            val target = authority.snapshot().scene.find(ObjectId(source.id.value.replace("stroke", "copy")))!!
            assertSame(source.samples, target.samples); assertEquals(b.layerId, target.layerId); assertEquals(1L, target.objectRevision)
        }
        assertSame(receipt, accept(authority, copy)); assertEquals(receipt.rootRevision, authority.snapshot().revision)
    }
    @Test fun invalidCopyMappingMissingDuplicateAndReusedIdsAreAtomicRejections() {
        val authority = MemoryDocumentAuthority(root()); accept(authority, add(authority)); accept(authority, add(authority, key = "stroke-b"))
        fun copy(mapping: Map<ObjectId, ObjectId>) = DocumentCommand.CopyPage(CommandId("bad-copy"), doc, authority.snapshot().revision,
            a.id, authority.snapshot().findPage(a.id)!!.contentRevision, b.id, b.layerId, mapping)
        reject(authority, copy(emptyMap()), DocumentRejection.COPY_MAPPING)
        reject(authority, copy(mapOf(ObjectId("stroke-a") to ObjectId("same"), ObjectId("stroke-b") to ObjectId("same"))), DocumentRejection.COPY_MAPPING)
        reject(authority, copy(mapOf(ObjectId("stroke-a") to ObjectId("stroke-a"), ObjectId("stroke-b") to ObjectId("new"))), DocumentRejection.REUSED_IDENTITY)
        val stale = DocumentCommand.CopyPage(CommandId("stale-copy"), doc, authority.snapshot().revision, a.id, 0, b.id, b.layerId, emptyMap())
        reject(authority, stale, DocumentRejection.STALE_PAGE)
    }
    @Test fun deleteCascadesDirectoryAndObjectsInOneCommitWithoutOrphans() {
        val authority = MemoryDocumentAuthority(root()); accept(authority, add(authority)); accept(authority, insert(authority)); accept(authority, add(authority, b, "stroke-b"))
        val prior = authority.snapshot(); val retained = prior.pageObjects(b.id).single()
        val receipt = accept(authority, DocumentCommand.DeletePage(CommandId("delete"), doc, prior.revision, a.id))
        assertEquals(prior.revision + 1, receipt.rootRevision); assertEquals(setOf(a.id), receipt.affectedPages())
        assertNull(authority.snapshot().findPage(a.id)); assertEquals(1, authority.snapshot().scene.objectCount)
        assertSame(retained, authority.snapshot().pageObjects(b.id).single()); assertEquals(2, prior.scene.objectCount)
        reject(authority, insert(authority, a), DocumentRejection.REUSED_IDENTITY)
        val late = DocumentCommand.Canvas(a.id, (add(authority).command as CanvasCommand.AddStroke).copy(id = CommandId("late")))
        reject(authority, late, DocumentRejection.MISSING_PAGE)
    }
    @Test fun deletingLastPageKeepsStableIdAndLayerWithBlankBackgroundAndEmptyContent() {
        val authority = MemoryDocumentAuthority(root()); accept(authority, add(authority))
        val removal = DocumentCommand.DeletePage(CommandId("delete-last"), doc, authority.snapshot().revision, a.id)
        val receipt = accept(authority, removal)
        val remaining = authority.snapshot().page(0)
        assertEquals(a.id, remaining.id); assertEquals(a.layerId, remaining.layerId); assertEquals(PaperKind.BLANK, remaining.paper)
        assertEquals(0, authority.snapshot().scene.objectCount); assertEquals(1, authority.snapshot().pageCount)
        assertSame(receipt, accept(authority, removal))
        reject(authority, DocumentCommand.DeletePage(CommandId("empty-last"), doc, authority.snapshot().revision, a.id), DocumentRejection.NO_CHANGE)
        val reused = add(authority)
        reject(authority, DocumentCommand.Canvas(a.id, (reused.command as CanvasCommand.AddStroke).copy(id = CommandId("fresh-reuse"))), DocumentRejection.REUSED_IDENTITY)
    }
    @Test fun operationIdChecksFullPayloadBeforeRevisionAndEvenWhenJournalIsFull() {
        val authority = MemoryDocumentAuthority(root(), DocumentBudget(journalEntries = 1))
        val command = insert(authority); val receipt = accept(authority, command)
        assertSame(receipt, accept(authority, command))
        reject(authority, command.copy(page = b.copy(width = 600.0)), DocumentRejection.REUSED_OPERATION_ID)
        reject(authority, DocumentCommand.Rename(CommandId("rename"), doc, authority.snapshot().revision, "new"), DocumentRejection.JOURNAL_CAPACITY)
    }
    @Test fun capacitiesRejectBeforePublishingAndKeepCallerInputOwned() {
        val baseline = MemoryDocumentAuthority(root()).status()
        val pageFull = MemoryDocumentAuthority(root(), DocumentBudget(maxPages = 1))
        reject(pageFull, insert(pageFull), DocumentRejection.PAGE_CAPACITY)
        val bytesFull = MemoryDocumentAuthority(root(), DocumentBudget(liveBytes = baseline.liveByteCharge))
        reject(bytesFull, add(bytesFull), DocumentRejection.LIVE_BYTE_CAPACITY)
        val idsFull = MemoryDocumentAuthority(root(), DocumentBudget(identityBytes = baseline.identityByteCharge))
        reject(idsFull, insert(idsFull), DocumentRejection.IDENTITY_CAPACITY)
        val objectsFull = MemoryDocumentAuthority(root(), DocumentBudget(maxObjects = 1)); accept(objectsFull, add(objectsFull))
        val owned = add(objectsFull, key = "caller-retained")
        reject(objectsFull, owned, DocumentRejection.OBJECT_CAPACITY)
        assertEquals(2, (owned.command as CanvasCommand.AddStroke).samples.size)
        val journalFull = MemoryDocumentAuthority(root(), DocumentBudget(journalBytes = 1023))
        reject(journalFull, insert(journalFull), DocumentRejection.JOURNAL_CAPACITY)
    }
    @Test fun paperAndTitleUpdateOneSequenceWithoutMutatingSamplesOrOtherPageContent() {
        val authority = MemoryDocumentAuthority(root()); accept(authority, add(authority)); accept(authority, insert(authority))
        val previous = authority.snapshot(); val obj = previous.pageObjects(a.id).single()
        accept(authority, DocumentCommand.SetPaper(CommandId("paper"), doc, previous.revision, a.id, 612.0, 792.0, PaperKind.DOTS))
        val paper = authority.snapshot(); assertEquals(paper.revision, paper.findPage(a.id)?.contentRevision)
        assertEquals(previous.findPage(b.id), paper.findPage(b.id)); assertSame(obj, paper.pageObjects(a.id).single())
        accept(authority, DocumentCommand.Rename(CommandId("title"), doc, paper.revision, " renamed "))
        assertEquals("renamed", authority.snapshot().title); assertEquals(paper.revision + 1, authority.snapshot().revision)
        assertEquals(paper.pages(), authority.snapshot().pages())
        reject(authority, DocumentCommand.SetPaper(CommandId("invalid-size"), doc, authority.snapshot().revision, a.id, Double.NaN, 10.0, PaperKind.BLANK), DocumentRejection.INVALID_PAGE)
    }
    @Test fun foreignOwnerAndDocumentCannotReadOrMutateAuthority() {
        var allowed = true
        val authority = MemoryDocumentAuthority(root(), ownerCheck = { check(allowed) })
        val command = insert(authority)
        allowed = false
        assertFailsWith<IllegalStateException> { authority.snapshot() }; assertFailsWith<IllegalStateException> { authority.status() }
        assertFailsWith<IllegalStateException> { authority.commit(command) }
        allowed = true
        reject(authority, command.copy(documentId = SceneId("foreign")), DocumentRejection.WRONG_DOCUMENT)
        assertEquals(0L, authority.snapshot().revision)
    }
    @Test fun rootRevisionExhaustionCannotWrapAndRestoredSnapshotsValidateCanonicalObjects() {
        val exhausted = DocumentRoot.compose("max", SceneSnapshot.restored(doc, Long.MAX_VALUE, emptyList()), listOf(a))
        val authority = MemoryDocumentAuthority(exhausted)
        reject(authority, insert(authority), DocumentRejection.REVISION_EXHAUSTED)
        assertFailsWith<IllegalArgumentException> { SceneSnapshot.restored(doc, -1, emptyList()) }
        val populated = MemoryDocumentAuthority(root()); accept(populated, add(populated)); val obj = populated.snapshot().scene.objects().single()
        assertFailsWith<IllegalArgumentException> { SceneSnapshot.restored(doc, 1, listOf(obj, obj)) }
    }
    @Test fun missingAnchorNoOpAndReusedLayerDoNotConsumeRevisionOrJournalBudget() {
        val authority = MemoryDocumentAuthority(root())
        reject(authority, insert(authority, before = PageId("missing")), DocumentRejection.MISSING_PAGE)
        reject(authority, DocumentCommand.MovePage(CommandId("noop"), doc, 0, a.id, a.id), DocumentRejection.NO_CHANGE)
        reject(authority, insert(authority, b.copy(layerId = a.layerId)), DocumentRejection.REUSED_IDENTITY)
        reject(authority, DocumentCommand.InsertPage(CommandId("invalid-initial-version"), doc, 0, b.copy(contentRevision = 1)), DocumentRejection.INVALID_PAGE)
        assertEquals(0, authority.status().operationCount)
    }
    @Test fun missingAndStaleCanvasObjectsReportSceneFailureWithoutMutatingRoot() {
        val authority = MemoryDocumentAuthority(root()); accept(authority, add(authority))
        val original = authority.snapshot()
        val missing = DocumentCommand.Canvas(a.id, CanvasCommand.DeleteStroke(CommandId("missing-object"), doc, original.revision, ObjectId("absent"), 1))
        val absent = assertIs<DocumentCommit.Rejected>(authority.commit(missing))
        assertEquals(DocumentRejection.SCENE_REJECTED, absent.reason); assertEquals(Rejection.MISSING_OBJECT, absent.sceneReason)
        val stale = DocumentCommand.Canvas(a.id, CanvasCommand.TransformStroke(CommandId("stale-object"), doc, original.revision,
            ObjectId("stroke-a"), 2, Affine2D(tx = 1.0)))
        val failed = assertIs<DocumentCommit.Rejected>(authority.commit(stale))
        assertEquals(Rejection.STALE_OBJECT, failed.sceneReason); assertSame(original, authority.snapshot())
    }

    @Test fun oversizedTitleCannotHideUnboundedRawInputInTheReceiptJournal() {
        val authority = MemoryDocumentAuthority(root())
        val input = DocumentCommand.Rename(CommandId("oversized-title"), doc, 0, "x".repeat(100_000))
        reject(authority, input, DocumentRejection.INVALID_TITLE)
        assertEquals(100_000, input.title.length); assertEquals(0L, authority.status().journalByteCharge)
        val max = DocumentCommand.Rename(CommandId("bounded-title"), doc, 0, "x".repeat(120))
        accept(authority, max); assertEquals(120, authority.snapshot().title.length)
        assertTrue(authority.status().journalByteCharge >= max.title.length * 2)
    }

    @Test fun preparedSessionPatchCanRetryAfterLostAckOrMetadataAdvanceWithoutAnotherAuthority() {
        val authority = MemoryDocumentAuthority(root())
        val command = add(authority).command
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(authority.snapshot().scene, command)).patch
        val receipt = accept(authority, DocumentCommand.PreparedCanvas(a.id, patch))
        accept(authority, insert(authority))
        // Recreated transport payload still compares by full content, not object identity.
        val initial = root().scene
        val reconstructed = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(initial, command)).patch
        assertNotSame(patch, reconstructed)
        val differentId = (command as CanvasCommand.AddStroke).copy(id = CommandId("other-operation"))
        val sameEffect = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(initial, differentId)).patch
        assertNotEquals(DocumentCommand.PreparedCanvas(a.id, patch), DocumentCommand.PreparedCanvas(a.id, sameEffect))
        assertSame(receipt, accept(authority, DocumentCommand.PreparedCanvas(a.id, reconstructed)))
        assertEquals(2L, authority.snapshot().revision); assertEquals(1, authority.snapshot().scene.objectCount)
        val otherPage = DocumentCommand.PreparedCanvas(b.id, reconstructed)
        reject(authority, otherPage, DocumentRejection.REUSED_OPERATION_ID)
    }
    @Test fun staleAndCrossPagePreparedPatchesCannotLeaveObjectsOrPartiallyUpdatePageMetadata() {
        val authority = MemoryDocumentAuthority(root())
        val captured = add(authority).command
        val stale = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(authority.snapshot().scene, captured)).patch
        accept(authority, insert(authority))
        reject(authority, DocumentCommand.PreparedCanvas(a.id, stale), DocumentRejection.STALE_ROOT)
        val latest = (captured as CanvasCommand.AddStroke).copy(id = CommandId("wrong-target"), baseRevision = authority.snapshot().revision)
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepare(authority.snapshot().scene, latest)).patch
        reject(authority, DocumentCommand.PreparedCanvas(b.id, patch), DocumentRejection.WRONG_LAYER)
        assertTrue(authority.snapshot().scene.objects().isEmpty())
    }

}
