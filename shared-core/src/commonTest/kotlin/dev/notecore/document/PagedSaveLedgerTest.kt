package dev.notecore.document

import dev.notecore.canvas.preview.*
import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PagedSaveLedgerTest {
    @Test fun equalVisibleLegacyInkWithDifferentNextIdentityIsStillAContentConflict() {
        fun draw(note: PreviewNotebook) {
            assertTrue(note.beginStroke(1.0, 2.0, 0.3f, 1)); assertTrue(note.endStroke(3.0, 4.0, 0.7f, 2))
        }
        val a = PreviewNotebook("legacy", "title"); draw(a); draw(a); assertTrue(a.undo())
        val b = PreviewNotebook("legacy", "title"); draw(b); b.rename("temporary"); b.rename("title")
        val first = a.capture(); val second = b.capture()
        assertEquals(first.version, second.version); assertEquals(first.canonicalScene().objects(), second.canonicalScene().objects())
        assertNotEquals(first.nextObjectSequence, second.nextObjectSequence)
        val ledger = PreviewSaveLedger(); accept(ledger, LegacyPreviewSaveSnapshot(first))
        assertEquals(PreviewSaveAdmission.VERSION_CONFLICT, ledger.offer(LegacyPreviewSaveSnapshot(second)))
    }
    private fun note() = PagedPreviewNotebook.create("paged", "title", "fresh-session")
    private fun draw(note: PagedPreviewNotebook, offset: Double = 0.0) {
        assertTrue(note.beginStroke(1.0 + offset, 2.0, 0.3f, 1)); assertTrue(note.endStroke(3.0 + offset, 4.0, 0.7f, 2))
    }
    private fun accept(ledger: PreviewSaveLedger, snapshot: PreviewSaveSnapshot) = assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(snapshot))
    @Test fun oldPageWriteCannotClearNewerOtherPageContentAndBothVersionsRemainCharged() {
        val note = note(); draw(note); val old = note.capture(); val ledger = PreviewSaveLedger(); accept(ledger, old)
        val flight = assertNotNull(ledger.nextWrite()); assertTrue(note.insertPage()); draw(note, 10.0); val latest = note.capture()
        accept(ledger, latest); assertEquals(old.retainedByteCharge + latest.retainedByteCharge, ledger.chargedBytes)
        assertTrue(ledger.complete(flight, true)); assertSame(latest, ledger.latest(note.documentId))
        assertEquals(latest.retainedByteCharge, ledger.chargedBytes)
        val next = assertNotNull(ledger.nextWrite()); val root = PagedPreviewCodec.decode(next.snapshot.encode())
        assertEquals(2, root.pageCount); assertEquals(2, root.scene.objectCount); assertFalse(ledger.complete(flight, true))
        assertTrue(ledger.complete(next, true)); assertTrue(ledger.isDrained)
    }
    @Test fun failedRootCanRetryFromEquivalentReopenedContentWithoutReplacingRetainedSnapshot() {
        val note = note(); draw(note); assertTrue(note.insertPage()); draw(note, 10.0)
        val latest = note.capture(); val ledger = PreviewSaveLedger(); accept(ledger, latest)
        val failed = assertNotNull(ledger.nextWrite()); assertTrue(ledger.complete(failed, false))
        val reopened = PagedPreviewNotebook(PagedPreviewCodec.decode(latest.encode()), "reopened-session").capture()
        assertNotSame(latest.root, reopened.root); assertTrue(latest.sameContent(reopened)); accept(ledger, reopened)
        assertSame(latest, ledger.latest(note.documentId)); val retry = assertNotNull(ledger.nextWrite())
        assertSame(latest, retry.snapshot); assertFalse(ledger.complete(failed, true)); assertTrue(ledger.complete(retry, true))
    }
    @Test fun sameVersionForksInTitlePaperOrderBrushTransformAndObjectRevisionRemainRejected() {
        val note = note(); draw(note); draw(note, 10.0); val original = note.capture(); val root = original.root
        val objects = root.scene.objects(); val ledger = PreviewSaveLedger(); accept(ledger, original)
        assertTrue(ledger.complete(assertNotNull(ledger.nextWrite()), false))
        val variants = listOf(
            DocumentRoot.compose("fork", root.scene, root.pages()),
            DocumentRoot.compose(root.title, root.scene, root.pages().map { it.copy(paper = PaperKind.GRID) }),
            DocumentRoot.compose(root.title, SceneSnapshot.restored(root.id, root.revision, objects.reversed()), root.pages()),
            DocumentRoot.compose(root.title, SceneSnapshot.restored(root.id, root.revision, objects.map { it.copy(brush = it.brush.copy(argb = 123)) }), root.pages()),
            DocumentRoot.compose(root.title, SceneSnapshot.restored(root.id, root.revision, objects.map { it.copy(transform = Affine2D(tx = 10.0)) }), root.pages()),
            DocumentRoot.compose(root.title, SceneSnapshot.restored(root.id, root.revision, objects.map { it.copy(objectRevision = it.objectRevision + 1) }), root.pages()))
        for (fork in variants) {
            assertEquals(PreviewSaveAdmission.VERSION_CONFLICT, ledger.offer(PagedPreviewSnapshot(fork)))
            assertSame(original, ledger.latest(note.documentId)); assertTrue(ledger.states().single().retryRequired)
            assertEquals(original.retainedByteCharge, ledger.chargedBytes); assertNull(ledger.nextWrite())
        }
        accept(ledger, note.capture()); assertNotNull(ledger.nextWrite())
    }
    @Test fun capacityRejectionKeepsNewPageRootWithCallerAndDoesNotChangeOldFlight() {
        val note = note(); draw(note); val old = note.capture(); assertTrue(note.insertPage()); draw(note, 10.0); val latest = note.capture()
        val ledger = PreviewSaveLedger(byteBudget = old.retainedByteCharge + latest.retainedByteCharge - 1)
        accept(ledger, old); val flight = assertNotNull(ledger.nextWrite())
        assertEquals(PreviewSaveAdmission.BYTE_CAPACITY, ledger.offer(latest)); assertSame(old, ledger.latest(note.documentId))
        assertEquals(2, latest.root.pageCount); assertEquals(2, note.totalStrokeCount)
        assertTrue(ledger.complete(flight, true)); accept(ledger, latest); assertSame(latest, assertNotNull(ledger.nextWrite()).snapshot)
    }
    @Test fun legacyAndPagedInputsUseTheSameSingleFlightLedgerAndKeepIndependentResponsibilities() {
        val ledger = PreviewSaveLedger(); val legacy = LegacyPreviewSaveSnapshot(PreviewNotebook("legacy", "old").capture())
        val note = note(); draw(note); val paged = note.capture()
        accept(ledger, legacy); accept(ledger, paged)
        val a = assertNotNull(ledger.nextWrite()); val b = assertNotNull(ledger.nextWrite()); assertNull(ledger.nextWrite())
        assertTrue(a.snapshot.encode().startsWith("NCPREVIEW\t1")); assertTrue(b.snapshot.encode().startsWith("NCPREVIEW\t2"))
        assertTrue(ledger.complete(a, true)); assertTrue(ledger.returnUnstarted(b)); assertSame(paged, ledger.latest(note.documentId))
        assertFalse(ledger.complete(b, true)); assertNotNull(ledger.nextWrite())
    }
    @Test fun legacyRecoveryComparesContentRatherThanPrivateCoordinatorRevisionOrWrapperIdentity() {
        val original = PreviewNotebook("legacy", "old")
        assertTrue(original.beginStroke(1.0, 2.0, 0.3f, 1)); assertTrue(original.endStroke(3.0, 4.0, 0.7f, 2))
        original.rename("renamed")
        val old = LegacyPreviewSaveSnapshot(original.capture()); val ledger = PreviewSaveLedger(); accept(ledger, old)
        assertTrue(ledger.complete(assertNotNull(ledger.nextWrite()), false))
        val reopened = LegacyPreviewSaveSnapshot(PreviewNotebook.restore(old.encode()).capture())
        assertNotSame(old, reopened); assertTrue(old.sameContent(reopened)); accept(ledger, reopened)
        assertSame(old, assertNotNull(ledger.nextWrite()).snapshot)
    }
    @Test fun legacySameVersionDifferentInkIsNotAcknowledgedAsTheOldSavedContent() {
        fun snapshot(offset: Double): LegacyPreviewSaveSnapshot {
            val note = PreviewNotebook("legacy", "title")
            assertTrue(note.beginStroke(1.0 + offset, 2.0, 0.3f, 1)); assertTrue(note.endStroke(3.0 + offset, 4.0, 0.7f, 2))
            return LegacyPreviewSaveSnapshot(note.capture())
        }
        val original = snapshot(0.0); val fork = snapshot(10.0); val ledger = PreviewSaveLedger(); accept(ledger, original)
        val flight = assertNotNull(ledger.nextWrite())
        assertEquals(PreviewSaveAdmission.VERSION_CONFLICT, ledger.offer(fork)); assertSame(original, ledger.latest("legacy"))
        assertTrue(ledger.complete(flight, true)); assertTrue(ledger.isDrained)
    }
    @Test fun oldAttemptCannotCompleteNewSaveEpochEvenWhenIdentityAndVersionAreEqual() {
        val original = note().capture(); val ledger = PreviewSaveLedger(); accept(ledger, original)
        val old = assertNotNull(ledger.nextWrite()); assertTrue(ledger.complete(old, true))
        val reopened = PagedPreviewNotebook(PagedPreviewCodec.decode(original.encode()), "new-session").capture()
        accept(ledger, reopened); val current = assertNotNull(ledger.nextWrite())
        assertFalse(ledger.complete(old, true)); assertFalse(ledger.returnUnstarted(old))
        assertSame(reopened, ledger.latest(original.documentId)); assertEquals(reopened.retainedByteCharge, ledger.chargedBytes)
        assertTrue(ledger.complete(current, true)); assertTrue(ledger.isDrained)
    }
}
