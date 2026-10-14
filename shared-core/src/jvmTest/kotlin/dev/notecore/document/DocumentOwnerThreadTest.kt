package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class DocumentOwnerThreadTest {
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
