package dev.notecore.document

import dev.notecore.canvas.preview.*
import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PreviewRecoveryTest {
    private fun recovery(): PagedPreviewSnapshot {
        val fault = PendingInputOwnerFault(nonce = "reused")
        val note = fault.note
        assertTrue(note.beginStroke(1.25, -2.5, .125f, 7))
        assertTrue(note.appendStroke(3.0, 4.0, .625f, 9))
        fault.arm(); assertFailsWith<IllegalStateException> { note.endStroke(8.0, 6.0, 1f, 10) }
        return note.captureRecovery()
    }
    @Test fun completionCannotCrossRetainedCodecBytesOrObjectCapacity() {
        val layer = LayerId("l".repeat(128)); val id = SceneId("retained-completion")
        val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)
        val small = StrokeSamples.copyOf(DoubleArray(45) { 1.0 }, DoubleArray(45) { 2.0 }, FloatArray(45) { .5f }, LongArray(45) { 1 })
        val objects = (0 until 4000).map { StrokeObject(ObjectId("$it".padEnd(128,'x')), 1, layer, brush, small) }
        val page = PageDescriptor(PageId("page"), layer, 595.0, 842.0, contentRevision = 1)
        val root = DocumentRoot.compose("title", SceneSnapshot.restored(id, 1, objects), listOf(page))
        assertSame(root, PreviewDocumentReader.accept(root).root)
        val large = StrokeSamples.copyOf(DoubleArray(20000) { 1.0 },DoubleArray(20000) { 2.0 },FloatArray(20000) { .5f },LongArray(20000) { 1 })
        val pending = DocumentCommand.Canvas(page.id,CanvasCommand.AddStroke(CommandId("completion"),1,
            StrokeTarget(id,layer,brush),ObjectId("new"),large))
        assertEquals(180000,root.scene.objects().sumOf { it.samples.size })
        assertFailsWith<IllegalArgumentException> { PagedPreviewSnapshot(root,pending) }
        val countRoot=DocumentRoot.compose("title",SceneSnapshot.restored(id,1,(0 until 5000).map {
            StrokeObject(ObjectId("count-$it"),1,layer,brush,small) }),listOf(page))
        // Keep this fixture below the point limit so this is specifically the object-count guard.
        val one=StrokeSamples.copyOf(doubleArrayOf(1.0),doubleArrayOf(2.0),floatArrayOf(.5f),longArrayOf(1))
        val bounded=DocumentRoot.compose("title",SceneSnapshot.restored(id,1,countRoot.scene.objects().map { it.copy(samples=one) }),listOf(page))
        assertSame(bounded,PreviewDocumentReader.accept(bounded).root)
        assertFailsWith<IllegalArgumentException> { PagedPreviewSnapshot(bounded,pending.copy(command=(pending.command as CanvasCommand.AddStroke).copy(samples=one))) }
        assertEquals(4000,root.scene.objectCount);assertSame(objects.last(),root.scene.find(objects.last().id))
    }

    @Test fun longNumericRecoveryFitsAndImpossibleCompletionIsRefusedBeforeAdoption() {
        val id=SceneId("long-recovery"); val layer=LayerId("ink")
        val samples=StrokeSamples.copyOf(DoubleArray(20000) { -1.2345678901234567e-100 },
            DoubleArray(20000) { -1.2345678901234567e-100 },FloatArray(20000) { 1.2345679e-20f },LongArray(20000) { Long.MAX_VALUE })
        val brush=BrushSpec(BrushKind.MONOLINE,1,2.0,-1)
        val objects=(0 until 9).map { StrokeObject(ObjectId("old-$it"),1,layer,brush,samples) }
        val page=PageDescriptor(PageId("page"),layer,595.0,842.0,contentRevision=1)
        val root=DocumentRoot.compose("title",SceneSnapshot.restored(id,1,objects),listOf(page))
        val pending=DocumentCommand.Canvas(page.id,CanvasCommand.AddStroke(CommandId("pending-op"),1,StrokeTarget(id,layer,brush),ObjectId("pending"),samples))
        val checkpoint=PagedPreviewSnapshot(root,pending);val text=checkpoint.encode()
        assertTrue(text.length>16_000_000);assertTrue(text.length<=PagedPreviewCodec.MAX_CHARACTERS)
        assertTrue(text.encodeToByteArray().size<=PagedPreviewCodec.MAX_UTF8_BYTES)
        assertTrue(checkpoint.encodingWorkByteCharge<=256L*1024*1024)
        val decoded=PreviewRecoveryCodec.decode(text)
        val note=assertNotNull(PagedPreviewNotebook.openRecovery(decoded,"resume").notebook)
        assertTrue(note.retryPendingStroke());val completed=note.snapshot()
        assertTrue(note.retryPendingStroke());assertSame(completed,note.snapshot())
        assertEquals(200_000,completed.scene.objects().sumOf { it.samples.size })
        assertTrue(note.capture().encode().length>16_000_000)
        val excess=DocumentCommand.Canvas(page.id,CanvasCommand.AddStroke(CommandId("extra-op"),completed.revision,
            StrokeTarget(id,layer,brush),ObjectId("extra"),samples))
        assertFailsWith<IllegalArgumentException> { PagedPreviewSnapshot(completed,excess) }
        assertSame(root,checkpoint.root);assertSame(pending,checkpoint.pending)
    }

    @Test fun denseCheckpointKeepsOrderChargesAndDuplicateIdentityRefusalThroughOneRetry() {
        val id = SceneId("dense-recovery")
        val page = PageDescriptor(PageId("page"), LayerId("ink"), 595.0, 842.0, contentRevision = 1)
        val samples = StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))
        val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)
        val objects = (1..4999).map { StrokeObject(ObjectId("old-$it"), 1, page.layerId, brush, samples) }
        val root = DocumentRoot.compose("dense", SceneSnapshot.restored(id, 1, objects), listOf(page))
        val command = CanvasCommand.AddStroke(CommandId("completion"), 1, StrokeTarget(id, page.layerId, brush), ObjectId("pending"), samples)
        val pending = DocumentCommand.Canvas(page.id, command)
        val checkpoint = PagedPreviewSnapshot(root, pending); val encoded = checkpoint.encode()
        assertEquals(PagedPreviewCodec.retainedByteCharge(root) + PreviewRecoveryCodec.pendingCharge(pending), checkpoint.retainedByteCharge)
        assertEquals(PagedPreviewCodec.encodingWorkByteCharge(root) + PreviewRecoveryCodec.pendingWorkCharge(pending), checkpoint.encodingWorkByteCharge)
        for (existing in listOf(objects.first(), objects.last())) assertFailsWith<IllegalArgumentException> {
            PagedPreviewSnapshot(root, pending.copy(command = command.copy(objectId = existing.id)))
        }
        val decoded = PreviewRecoveryCodec.decode(encoded)
        assertEquals(encoded, decoded.encode()); assertTrue(checkpoint.sameContent(decoded))
        assertEquals(objects.map { it.id }, decoded.root.scene.objects().map { it.id })
        val note = assertNotNull(PagedPreviewNotebook.openRecovery(decoded, "resume").notebook)
        assertTrue(note.retryPendingStroke()); val committed = note.snapshot()
        assertTrue(note.retryPendingStroke()); assertSame(committed, note.snapshot())
        assertEquals(5000, committed.scene.objectCount)
        assertEquals(objects.map { it.id } + command.objectId, committed.scene.objects().map { it.id })
        assertEquals(samples, committed.scene.find(command.objectId)!!.samples)
        assertSame(root, checkpoint.root); assertSame(pending, checkpoint.pending)
        assertEquals(encoded, checkpoint.encode())
    }

    @Test fun exactPendingRoundtripAndOriginalOwnerExplicitRetryOnce() {
        val source = recovery(); val text = source.encode()
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.decode(text) }
        assertNull(PreviewDocumentReader.read(text).root)
        val decoded = PreviewRecoveryCodec.decode(text)
        assertTrue(source.sameContent(decoded)); assertEquals(source.version, decoded.root.revision)
        val note = assertNotNull(PagedPreviewNotebook.openRecovery(decoded, "reused").notebook)
        assertTrue(note.hasPendingStroke); assertEquals(0, note.totalStrokeCount)
        note.cancelStroke(); assertFalse(note.retire()); assertSame(decoded.pending, note.pendingStroke())
        assertTrue(note.retryPendingStroke()); assertTrue(note.retryPendingStroke())
        val pending = source.pending!!.command as CanvasCommand.AddStroke
        assertEquals(1, note.totalStrokeCount); assertEquals(1L, note.changeVersion)
        assertEquals(pending.objectId, note.stroke(0).id); assertEquals(pending.samples, note.stroke(0).samples)
        assertTrue(note.beginStroke(10.0, 10.0, .5f, 12)); assertTrue(note.endStroke(11.0, 11.0, .5f, 13))
        assertNotEquals(pending.objectId, note.stroke(1).id)
        assertNotEquals(source.pending!!.id, note.authorityStatus().nextUndoOperationId)
        assertTrue(note.captureRecovery().encode().startsWith("NCPREVIEW\t2\n"))
        assertEquals(2, PreviewRecoveryCodec.decode(note.captureRecovery().encode()).root.scene.objectCount)
    }
    @Test fun oldFlightCannotConfirmExtensionAndFailureRetainsExactRecovery() {
        val recovery = recovery(); val base = PagedPreviewSnapshot.fromRoot(recovery.root)
        val ledger = PreviewSaveLedger(byteBudget = base.retainedByteCharge + recovery.retainedByteCharge)
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(base)); val old = ledger.nextWrite()!!
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(recovery))
        assertEquals(base.retainedByteCharge + recovery.retainedByteCharge, ledger.chargedBytes)
        assertEquals(PreviewSaveAdmission.VERSION_CONFLICT, ledger.offer(base))
        assertTrue(ledger.complete(old, true)); assertSame(recovery, ledger.latest(base.documentId))
        assertEquals(recovery.retainedByteCharge, ledger.chargedBytes)
        val flight = ledger.nextWrite()!!; assertTrue(ledger.complete(flight, false))
        assertTrue(ledger.states().single().retryRequired); assertNull(ledger.nextWrite())
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(recovery)); val retry = ledger.nextWrite()!!
        assertFalse(ledger.complete(old, true)); assertTrue(ledger.complete(retry, true)); assertTrue(ledger.isDrained)
    }
    @Test fun capacityRejectionAndSameRevisionForkDoNotDropBaseOrCompletion() {
        val recovery = recovery(); val base = PagedPreviewSnapshot.fromRoot(recovery.root)
        val ledger = PreviewSaveLedger(byteBudget = base.retainedByteCharge + recovery.retainedByteCharge - 1)
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(base)); val old = ledger.nextWrite()!!
        assertEquals(PreviewSaveAdmission.BYTE_CAPACITY, ledger.offer(recovery)); assertSame(base, ledger.latest(base.documentId))
        ledger.complete(old, true)
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(recovery))
        val equivalentFork = PreviewRecoveryCodec.decode(recovery.encode())
        assertTrue(equivalentFork.sameContent(recovery))
        assertEquals(PreviewSaveAdmission.ACCEPTED, ledger.offer(equivalentFork))
        val otherRoot = PreviewRecoveryCodec.decode(base.encode()).root
        val second = PagedPreviewSnapshot(otherRoot, recovery.pending)
        val baseLedger = PreviewSaveLedger(); baseLedger.offer(base)
        assertEquals(PreviewSaveAdmission.VERSION_CONFLICT, baseLedger.offer(second)) // identical text, different root authority
    }
    @Test fun corruptUnknownTruncatedAndTrailingInputNeverReturnsOnlyBase() {
        val recovery = recovery(); val text = recovery.encode()
        for (bad in listOf(text.dropLast(1), text + "x", text.replace("NCRECOVERY\t1", "NCRECOVERY\t2"),
            text.replace("RECOVERY-END", "BAD-END"), text.replace("MONOLINE", "PRESSURE"),
            text.replace("\n${PagedPreviewCodec.encode(recovery.root).length}\t", "\n2147483647\t"))) {
            assertFailsWith<IllegalArgumentException> { PreviewRecoveryCodec.decode(bad) }
        }
        assertEquals(recovery.documentId, PreviewRecoveryCodec.header(text.lineSequence().take(2).joinToString("\n") + "\n").documentId)
    }
    @Test fun signedOrNonAsciiFramingCannotChangeRecoveredOperationIdentity() {
        val text = recovery().encode()
        val lines = text.split('\n', limit = 4)
        val frame = lines[2].split('\t')
        fun framed(base: String = frame[0], ink: String = frame[1], op: String = frame[2]) =
            lines[0] + "\n" + lines[1] + "\n" + "$base\t$ink\t$op\n" + lines[3]
        // The old radix parser accepted -001 and recovered a different operation beginning U+FFFF.
        for (op in listOf("-001", "+001", "ＦＦＦＦ", "٠٠٧٢")) {
            assertFailsWith<IllegalArgumentException>(op) { PreviewRecoveryCodec.decode(framed(op = op + frame[2].drop(4))) }
        }
        for (index in 0..1) for (length in listOf("+" + frame[index], "０" + frame[index])) {
            assertFailsWith<IllegalArgumentException> {
                PreviewRecoveryCodec.decode(if (index == 0) framed(base = length) else framed(ink = length))
            }
        }
        val unicode = "笔\uD83D\uDE80"
        val unicodeHex = unicode.map { it.code.toString(16).padStart(4, '0') }.joinToString("")
        val decodedUnicode = PreviewRecoveryCodec.decode(framed(op = unicodeHex))
        assertEquals(unicode, decodedUnicode.pending!!.id.value)
        assertTrue(decodedUnicode.sameContent(PreviewRecoveryCodec.decode(decodedUnicode.encode())))
        assertTrue(PreviewRecoveryCodec.decode(text).sameContent(PreviewRecoveryCodec.decode(framed(op = frame[2].uppercase()))))
    }
    @Test fun recoveryRetirementRequiresExactRootAndCommandAndCannotResubmit() {
        val recovery = recovery(); val note = assertNotNull(PagedPreviewNotebook.openRecovery(recovery, "reused").notebook)
        assertFalse(note.retireAfterRecovery(PagedPreviewSnapshot.fromRoot(recovery.root)))
        assertFalse(note.retireAfterRecovery(PreviewRecoveryCodec.decode(recovery.encode())))
        assertTrue(note.hasPendingStroke); assertTrue(note.retireAfterRecovery(note.captureRecovery()))
        assertFalse(note.retryPendingStroke()); assertEquals(0, note.totalStrokeCount)
        assertSame(recovery.pending, note.pendingStroke()); assertFalse(note.beginStroke(1.0, 1.0, .5f, 20))
    }
    @Test fun restoredCapacityRefusalKeepsPendingAndOwnerErrorsEscape() {
        val recovery = recovery()
        val note = assertNotNull(PagedPreviewNotebook.openRecovery(recovery, "consumer", DocumentBudget(historyBytes = 0)).notebook)
        assertFalse(note.retryPendingStroke()); assertSame(recovery.pending, note.pendingStroke())
        assertTrue(note.captureRecovery().sameContent(recovery))
        assertFailsWith<IllegalStateException> { PagedPreviewNotebook.openRecovery(recovery, "consumer", ownerCheck = { error("wrong owner") }) }
    }
    @Test fun pendingMetadataBudgetRefusalPreservesCheckpointAndExactBudgetRestoresOnce() {
        val source = recovery(); val encoded = source.encode()
        val pending = source.pending!!
        val stroke = pending.command as CanvasCommand.AddStroke
        val charge = 2048L + (pending.id.value.length + pending.pageId.value.length + stroke.objectId.value.length +
            stroke.target.layerId.value.length) * 2L + 3 * 28L
        assertEquals(charge, PreviewRecoveryCodec.pendingCharge(pending))
        for (bytes in listOf(0L, stroke.samples.logicalByteCharge, charge - 1)) {
            val refused = PagedPreviewNotebook.openRecovery(source, "restore", DocumentBudget(inputBytes = bytes))
            assertNull(refused.notebook); assertEquals(PreviewOpenFailure.INPUT_CAPACITY, refused.failure)
            assertEquals(encoded, source.encode()); assertSame(pending, source.pending)
        }
        val note = assertNotNull(PagedPreviewNotebook.openRecovery(source, "restore", DocumentBudget(inputBytes = charge)).notebook)
        assertEquals(charge, note.inputReservedByteCharge); note.cancelStroke()
        assertEquals(charge, note.inputReservedByteCharge); assertTrue(note.retryPendingStroke())
        assertTrue(note.retryPendingStroke()); assertEquals(1, note.totalStrokeCount)
        assertEquals(stroke.samples, note.stroke(0).samples); assertEquals(0L, note.inputReservedByteCharge)
        assertEquals(encoded, source.encode())
        // An ordinary checkpoint needs no pending reservation, even with input disabled.
        assertNotNull(PagedPreviewNotebook.openRecovery(PagedPreviewSnapshot.fromRoot(source.root), "restore",
            DocumentBudget(inputBytes = 0)).notebook)
    }
}
