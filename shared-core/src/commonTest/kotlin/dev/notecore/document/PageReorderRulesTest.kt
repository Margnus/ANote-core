package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PageReorderRulesTest {
    private val documentId = SceneId("thumbnail-reorder")
    private val pages = (0..3).map { index ->
        PageDescriptor(PageId("page-$index"), LayerId("ink-$index"), 595.0, 842.0, PaperKind.GRID)
    }
    private val root = DocumentRoot.compose("Thumbnails", SceneSnapshot.empty(documentId), pages)
    private val operation = CommandId("drop")

    @Test fun allRelativeDropsUseStableIdsAndDoNotChangeOtherPages() {
        for (source in pages) for (target in pages) for (side in PageDropSide.entries) {
            val result = PageReorderRules.plan(root, documentId, root.revision, operation, source.id, target.id, side)
            val expected = pages.toMutableList()
            if (source != target) {
                expected.remove(source)
                val offset = if (side == PageDropSide.AFTER) 1 else 0
                expected.add(expected.indexOf(target) + offset, source)
            }
            if (expected == pages) {
                assertEquals(PageReorderResult.Rejected(DocumentRejection.NO_CHANGE), result)
            } else {
                val command = assertIs<PageReorderResult.Planned>(result).command
                assertEquals(source.id, command.pageId)
                assertEquals(expected.getOrNull(expected.indexOf(source) + 1)?.id, command.before)
                val owner = MemoryDocumentAuthority(root)
                assertIs<DocumentCommit.Accepted>(owner.commit(command))
                assertEquals(expected, owner.snapshot().pages())
            }
            assertEquals(pages, root.pages())
        }
    }

    @Test fun rejectsWrongDocumentStaleRevisionAndInvalidOrMissingIdentities() {
        fun plan(document: SceneId = documentId, revision: Long = root.revision,
                 source: PageId = pages.first().id, target: PageId = pages.last().id, id: CommandId = operation) =
            PageReorderRules.plan(root, document, revision, id, source, target, PageDropSide.AFTER)

        assertEquals(PageReorderResult.Rejected(DocumentRejection.WRONG_DOCUMENT), plan(document = SceneId("other")))
        assertEquals(PageReorderResult.Rejected(DocumentRejection.STALE_ROOT), plan(revision = root.revision + 1))
        val missing = PageReorderResult.Rejected(DocumentRejection.MISSING_PAGE)
        assertEquals(missing, plan(source = PageId("missing")))
        assertEquals(missing, plan(target = PageId("missing")))
        val invalid = PageReorderResult.Rejected(DocumentRejection.INVALID_IDENTITY)
        assertEquals(invalid, plan(source = PageId("\uD800")))
        assertEquals(invalid, plan(target = PageId("\uD800")))
        assertEquals(invalid, plan(id = CommandId("\uD800")))
    }

    @Test fun authorityRejectsDropIfPageOrderChangesAfterPlanning() {
        val owner = MemoryDocumentAuthority(root)
        val command = assertIs<PageReorderResult.Planned>(PageReorderRules.plan(
            root, documentId, root.revision, operation, pages.first().id, pages.last().id, PageDropSide.AFTER,
        )).command
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.MovePage(
            CommandId("intervening-move"), documentId, root.revision, pages.last().id, pages.first().id,
        )))
        val before = owner.snapshot()
        val status = owner.status()
        assertEquals(DocumentRejection.STALE_ROOT, assertIs<DocumentCommit.Rejected>(owner.commit(command)).reason)
        assertSame(before, owner.snapshot())
        assertEquals(status, owner.status())
    }

    @Test fun dropUndoAndRedoPreserveActivePageIdentityAndInk() {
        val owner = MemoryDocumentAuthority(root)
        val active = pages.first()
        val inkId = ObjectId("active-page-ink")
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Canvas(active.id, CanvasCommand.AddStroke(
            CommandId("ink"), root.revision,
            StrokeTarget(documentId, active.layerId, BrushSpec(BrushKind.MONOLINE, 1, 2.0, 0xff000000.toInt())), inkId,
            StrokeSamples.copyOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0), floatArrayOf(0.5f, 0.7f), longArrayOf(1, 2)),
        ))))
        val before = owner.snapshot()
        val ink = assertNotNull(before.scene.find(inkId))
        val command = assertIs<PageReorderResult.Planned>(PageReorderRules.plan(
            before, documentId, before.revision, operation, active.id, pages.last().id, PageDropSide.AFTER,
        )).command
        assertIs<DocumentCommit.Accepted>(owner.commit(command))
        val moved = owner.snapshot()
        assertEquals(active.id, moved.page(moved.pageCount - 1).id)
        assertEquals(operation, owner.status().nextUndoOperationId)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Undo(CommandId("undo"), documentId, moved.revision, operation)))
        val undone = owner.snapshot()
        assertEquals(before.pages(), undone.pages())
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Redo(CommandId("redo"), documentId, undone.revision, operation)))
        val redone = owner.snapshot()
        assertEquals(moved.pages(), redone.pages())
        for (snapshot in listOf(moved, undone, redone)) {
            assertEquals(before.findPage(active.id), snapshot.findPage(active.id))
            assertSame(ink, snapshot.scene.find(inkId))
        }
    }
}
