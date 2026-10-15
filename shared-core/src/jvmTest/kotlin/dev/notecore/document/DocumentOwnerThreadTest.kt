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
}
