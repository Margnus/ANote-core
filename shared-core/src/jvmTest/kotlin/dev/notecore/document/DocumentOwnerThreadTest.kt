package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class DocumentOwnerThreadTest {
    @Test fun pagedSessionRejectsActualWorkerMutationAndSnapshotCalls() {
        val executor = BoundedOwnerExecutor()
        val root = DocumentRoot.empty(SceneId("session-owner"), "title", PageDescriptor(PageId("a"), LayerId("ink-a"), 595.0, 842.0))
        val note = PagedPreviewNotebook(root, "owner-session", ownerCheck = executor::checkOwnerThread)
        try {
            executor.submit(1024, { note.insertPage() }) { outcome ->
                assertIs<WorkResult.OutcomeUnknown>(outcome); assertSame(root, note.snapshot()); ResultDisposition.ACKNOWLEDGED
            }
            executor.submit(1024, { note.capture() }) { outcome ->
                assertIs<WorkResult.OutcomeUnknown>(outcome); assertEquals(1, note.pageCount); ResultDisposition.ACKNOWLEDGED
            }
            executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)); assertEquals(2, executor.drainResults().acknowledged)
            assertTrue(note.insertPage()); assertEquals(2, note.pageCount)
        } finally { executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)) }
    }
    @Test fun historyTraversalRemainsOnActualOwnerThreadAndForeignAttemptKeepsStacks() {
        val executor = BoundedOwnerExecutor()
        val id = SceneId("history-owner")
        val authority = MemoryDocumentAuthority(DocumentRoot.empty(id, "title",
            PageDescriptor(PageId("a"), LayerId("ink-a"), 595.0, 842.0)), ownerCheck = executor::checkOwnerThread)
        val edit = DocumentCommand.Rename(CommandId("edit"), id, 0, "new")
        assertIs<DocumentCommit.Accepted>(authority.commit(edit))
        val undo = DocumentCommand.Undo(CommandId("undo"), id, 1, edit.id)
        val before = authority.status()
        try {
            executor.submit(undo, 1024, { input -> authority.commit(input) }) { _, result ->
                assertIs<WorkResult.OutcomeUnknown>(result); assertEquals(before, authority.status())
                ResultDisposition.ACKNOWLEDGED
            }
            executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000))
            assertEquals(1, executor.drainResults().acknowledged)
            assertIs<DocumentCommit.Accepted>(authority.commit(undo))
            assertEquals("title", authority.snapshot().title); assertEquals(1, authority.status().redoCount)
        } finally { executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)) }
    }
    @Test fun realWorkerCannotCommitOrInspectDocumentAndOwnerCanContinueAfterFailedForeignCall() {
        val executor = BoundedOwnerExecutor()
        val id = SceneId("thread-document")
        val authority = MemoryDocumentAuthority(DocumentRoot.empty(id, "title",
            PageDescriptor(PageId("a"), LayerId("ink-a"), 595.0, 842.0)), ownerCheck = executor::checkOwnerThread)
        val command = DocumentCommand.Rename(CommandId("rename"), id, 0, "new")
        try {
            executor.submit(command, 1024, { input -> authority.commit(input) }) { input, result ->
                assertSame(command, input); assertIs<WorkResult.OutcomeUnknown>(result)
                assertEquals(0L, authority.snapshot().revision); ResultDisposition.ACKNOWLEDGED
            }
            executor.submit(1024, { authority.status() }) { result ->
                assertIs<WorkResult.OutcomeUnknown>(result); ResultDisposition.ACKNOWLEDGED
            }
            executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000))
            assertEquals(2, executor.drainResults().acknowledged); assertTrue(executor.status().isDrained)
            assertIs<DocumentCommit.Accepted>(authority.commit(command)); assertEquals(1L, authority.snapshot().revision)
        } finally { executor.stopAccepting(); assertTrue(executor.awaitWorkerStop(3000)) }
    }
    private fun <T> onNewOwner(action: () -> T): T {
        val task = java.util.concurrent.FutureTask(action)
        val thread = Thread(task, "explicit-preview-owner")
        thread.start()
        try { return task.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        finally { thread.join(3000); assertFalse(thread.isAlive, "Owner must terminate before next owner starts") }
    }
    private fun threadGuard(): () -> Unit {
        val owner = Thread.currentThread()
        return { check(Thread.currentThread() === owner) { "Foreign preview owner" } }
    }
    @Test fun retiredOwnerThreadTransfersOnlyImmutableRootAndNewOwnerContinuesCanonicalContent() {
        val handed = onNewOwner {
            val note = assertNotNull(PagedPreviewNotebook.createValidated("thread-handoff", "title", "first", ownerCheck=threadGuard()).notebook)
            assertTrue(note.beginStroke(1.0,2.0,.5f,1)); assertTrue(note.endStroke(3.0,4.0,.75f,2))
            val checkpoint = note.capture()
            assertTrue(note.retire())
            assertEquals(PreviewFailureKind.CLOSED, note.edit(PreviewEdit.Rename("late")).failure?.kind)
            assertEquals(0L,note.authorityStatus().journalByteCharge)
            note to checkpoint
        }
        val old = handed.first; val snapshot = handed.second; val encoded = snapshot.encode()
        assertFailsWith<IllegalStateException> { old.snapshot() }
        assertFailsWith<IllegalStateException> { old.retryPendingStroke() }
        val next = onNewOwner {
            val note = assertNotNull(PagedPreviewNotebook.openRecovery(snapshot,"second",ownerCheck=threadGuard()).notebook)
            assertSame(snapshot.root,note.snapshot())
            assertEquals(encoded,note.capture().encode())
            assertTrue(note.beginStroke(5.0,6.0,.5f,3)); assertTrue(note.endStroke(7.0,8.0,.5f,4))
            assertEquals(2,note.totalStrokeCount)
            assertEquals(1,note.authorityStatus().undoCount) // New session history, not a second live owner of old history.
            val result=note.capture(); assertTrue(note.retire()); result
        }
        assertEquals(encoded,snapshot.encode())
        assertEquals(1,snapshot.root.scene.objectCount)
        assertEquals(2,assertNotNull(PreviewRecoveryCodec.read(next.encode()).snapshot).root.scene.objectCount)
    }
    @Test fun pendingOwnerThreadHandsOffOnlyAfterExactHostFileWriteAndNewOwnerCommitsOnce() {
        val path=java.nio.file.Files.createTempFile("notecore-owner-checkpoint-", ".ncp")
        try {
            val handed=onNewOwner {
                val guard=threadGuard(); var armed=false; var probing=false
                var current: PagedPreviewNotebook?=null
                val note=assertNotNull(PagedPreviewNotebook.createValidated("pending-thread", "title", "first", ownerCheck={
                    guard()
                    if(armed && !probing) {
                        probing=true
                        val pending=try { current?.hasPendingStroke==true } finally { probing=false }
                        if(pending) { armed=false; error("Synthetic unavailable commit owner") }
                    }
                }).notebook)
                current=note
                assertTrue(note.beginStroke(1.0,2.0,.25f,1)); armed=true
                assertFailsWith<IllegalStateException> { note.endStroke(3.0,4.0,.75f,2) }
                val checkpoint=note.captureRecovery(); val encoded=checkpoint.encode()
                assertFalse(note.retire()); assertSame(checkpoint.pending,note.pendingStroke())
                assertFalse(note.retireAfterRecovery(PagedPreviewSnapshot.fromRoot(checkpoint.root)))
                val bytes=encoded.toByteArray(Charsets.UTF_8)
                java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer=java.nio.ByteBuffer.wrap(bytes)
                    while(buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                assertContentEquals(bytes,java.nio.file.Files.readAllBytes(path))
                assertTrue(note.retireAfterRecovery(checkpoint))
                assertFalse(note.retryPendingStroke()); assertSame(checkpoint.pending,note.pendingStroke())
                note to checkpoint
            }
            assertFailsWith<IllegalStateException> { handed.first.captureRecovery() }
            val before=java.nio.file.Files.readAllBytes(path)
            val result=onNewOwner {
                val checkpoint=assertNotNull(PreviewRecoveryCodec.read(String(java.nio.file.Files.readAllBytes(path),Charsets.UTF_8)).snapshot)
                assertTrue(checkpoint.sameContent(handed.second))
                val note=assertNotNull(PagedPreviewNotebook.openRecovery(checkpoint,"second",ownerCheck=threadGuard()).notebook)
                assertEquals(0,note.totalStrokeCount); assertTrue(note.hasPendingStroke)
                assertTrue(note.retryPendingStroke()); val root=note.snapshot()
                assertTrue(note.retryPendingStroke()); assertSame(root,note.snapshot())
                assertEquals(1,note.totalStrokeCount); assertEquals(checkpoint.root.revision+1,root.revision)
                assertEquals((checkpoint.pending!!.command as CanvasCommand.AddStroke).samples,note.stroke(0).samples)
                val result=note.capture(); assertTrue(note.retire()); result
            }
            assertContentEquals(before,java.nio.file.Files.readAllBytes(path))
            assertEquals(0,handed.second.root.scene.objectCount)
            assertEquals(1,result.root.scene.objectCount)
        } finally { java.nio.file.Files.deleteIfExists(path) }
    }

}
