package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class CanonicalTitleTest {
    private val doc = SceneId("title-contract")
    private val page = PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)
    private fun root(title: String = "before") = DocumentRoot.empty(doc, title, page)

    @Test fun malformedDirectRenameRejectsWithoutPublishingOrConsumingIdentity() {
        for (title in listOf("\uD800", "\uDC00", "bad\uD800tail", "\uD800\uD800")) {
            val owner = MemoryDocumentAuthority(root()); val before = owner.snapshot(); val status = owner.status()
            val id = CommandId("retry")
            assertEquals(DocumentRejection.INVALID_TITLE,
                assertIs<DocumentCommit.Rejected>(owner.commit(DocumentCommand.Rename(id, doc, 0, title))).reason)
            assertSame(before, owner.snapshot()); assertEquals(status, owner.status())
            assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Rename(id, doc, 0, "合法😀")))
            assertEquals("合法😀", PagedPreviewCodec.decode(PagedPreviewCodec.encode(owner.snapshot())).title)
        }
    }
    @Test fun invalidRenameKeepsRedoBranchAndOriginalReceipt() {
        val owner = MemoryDocumentAuthority(root())
        val edit = DocumentCommand.Rename(CommandId("edit"), doc, 0, "after")
        val receipt = assertIs<DocumentCommit.Accepted>(owner.commit(edit)).receipt
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Undo(CommandId("undo"), doc, 1, edit.id)))
        val before = owner.snapshot(); val status = owner.status()
        assertIs<DocumentCommit.Rejected>(owner.commit(DocumentCommand.Rename(CommandId("invalid"), doc, 2, "\uD800")))
        assertSame(before, owner.snapshot()); assertEquals(status, owner.status())
        assertSame(receipt, assertIs<DocumentCommit.Accepted>(owner.commit(edit)).receipt)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Redo(CommandId("redo"), doc, 2, edit.id)))
        assertEquals("after", owner.snapshot().title)
    }
    @Test fun invalidInitialTitleCannotBecomeAnAuthority() {
        assertFailsWith<IllegalArgumentException> { MemoryDocumentAuthority(root("\uD800")) }
        // Raw immutable roots remain codec-negative fixtures; they are not a mutable authority.
        assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.encode(root("\uD800")) }
    }
    @Test fun validSupplementaryCharactersAtTitleLimitRoundTripUnchanged() {
        val title = "中".repeat(118) + "😀"
        assertEquals(120, title.length)
        val owner = MemoryDocumentAuthority(root())
        val action = DocumentCommand.Rename(CommandId("unicode"), doc, 0, title)
        assertIs<DocumentCommit.Accepted>(owner.commit(action))
        val text = PagedPreviewCodec.encode(owner.snapshot())
        assertEquals(title, PagedPreviewCodec.decode(text.encodeToByteArray().decodeToString(throwOnInvalidSequence = true)).title)
        val status = owner.status()
        assertEquals(DocumentRejection.INVALID_TITLE, assertIs<DocumentCommit.Rejected>(owner.commit(
            DocumentCommand.Rename(CommandId("too-long"), doc, 1, title + "x"))).reason)
        assertEquals(status, owner.status())
    }
}
