package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class CanonicalIdentityTest {
    private val doc = SceneId("document")
    private val page = PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)
    private fun root() = DocumentRoot.empty(doc, "title", page)
    private val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, -1)
    private val samples = StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(.5f), longArrayOf(1))

    @Test fun malformedDirectInsertCannotPublishAnUnencodableRoot() {
        val owner = MemoryDocumentAuthority(root())
        val before = owner.snapshot(); val status = owner.status()
        val command = DocumentCommand.InsertPage(CommandId("retry"), doc, 0,
            PageDescriptor(PageId("\uD800"), LayerId("new-layer"), 595.0, 842.0))
        assertEquals(DocumentRejection.INVALID_IDENTITY, assertIs<DocumentCommit.Rejected>(owner.commit(command)).reason)
        assertSame(before, owner.snapshot()); assertEquals(status, owner.status())
        assertIs<DocumentCommit.Accepted>(owner.commit(command.copy(page = command.page.copy(id = PageId("valid")))))
        assertEquals(2, PagedPreviewCodec.decode(PagedPreviewCodec.encode(owner.snapshot())).pageCount)
    }

    @Test fun everyExternalIdentitySlotRejectsMalformedScalarsBeforeOtherMutation() {
        for (bad in listOf("\uD800", "\uDC00", "bad\uD800tail")) {
            val id = CommandId("retry")
            val add = CanvasCommand.AddStroke(id, 0, StrokeTarget(doc, page.layerId, brush), ObjectId("object"), samples)
            val malformed = StrokeObject(ObjectId(bad), 1, page.layerId, brush, samples)
            val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(root().scene, id,
                listOf(ObjectChange(null, malformed)))).patch
            val badLayer = malformed.copy(id = ObjectId("object"), layerId = LayerId(bad))
            val layerPatch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(root().scene, id,
                listOf(ObjectChange(null, badLayer)))).patch
            fun removal(obj: StrokeObject) = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(
                SceneSnapshot.restored(doc, 1, listOf(obj)), id, listOf(ObjectChange(obj, null)))).patch
            val commands = listOf(
                DocumentCommand.Rename(CommandId(bad), doc, 0, "after"),
                DocumentCommand.Rename(id, SceneId(bad), 0, "after"),
                DocumentCommand.InsertPage(id, doc, 0, page.copy(id = PageId(bad))),
                DocumentCommand.InsertPage(id, doc, 0, page.copy(layerId = LayerId(bad))),
                DocumentCommand.InsertPage(id, doc, 0, page.copy(id = PageId("new")), PageId(bad)),
                DocumentCommand.MovePage(id, doc, 0, PageId(bad)),
                DocumentCommand.MovePage(id, doc, 0, page.id, PageId(bad)),
                DocumentCommand.DeletePage(id, doc, 0, PageId(bad)),
                DocumentCommand.SetPaper(id, doc, 0, PageId(bad), 10.0, 20.0, PaperKind.BLANK),
                DocumentCommand.Undo(id, doc, 0, CommandId(bad)),
                DocumentCommand.Redo(id, doc, 0, CommandId(bad)),
                DocumentCommand.CopyPage(id, doc, 0, PageId(bad), 0, PageId("new"), LayerId("new-layer"), emptyMap()),
                DocumentCommand.CopyPage(id, doc, 0, page.id, 0, PageId(bad), LayerId("new-layer"), emptyMap()),
                DocumentCommand.CopyPage(id, doc, 0, page.id, 0, PageId("new"), LayerId(bad), emptyMap()),
                DocumentCommand.CopyPage(id, doc, 0, page.id, 0, PageId("new"), LayerId("new-layer"), emptyMap(), PageId(bad)),
                DocumentCommand.CopyPage(id, doc, 0, page.id, 0, PageId("new"), LayerId("new-layer"), mapOf(ObjectId(bad) to ObjectId("new-object"))),
                DocumentCommand.CopyPage(id, doc, 0, page.id, 0, PageId("new"), LayerId("new-layer"), mapOf(ObjectId("object") to ObjectId(bad))),
                DocumentCommand.Canvas(PageId(bad), add),
                DocumentCommand.Canvas(page.id, add.copy(objectId = ObjectId(bad))),
                DocumentCommand.Canvas(page.id, add.copy(target = add.target.copy(layerId = LayerId(bad)))),
                DocumentCommand.Canvas(page.id, CanvasCommand.DeleteStroke(id, doc, 0, ObjectId(bad), 1)),
                DocumentCommand.Canvas(page.id, CanvasCommand.TransformStroke(id, doc, 0, ObjectId(bad), 1, Affine2D())),
                DocumentCommand.PreparedCanvas(page.id, patch),
                DocumentCommand.PreparedCanvas(PageId(bad), patch),
                DocumentCommand.PreparedCanvas(page.id, layerPatch),
                DocumentCommand.PreparedCanvas(page.id, removal(malformed)),
                DocumentCommand.PreparedCanvas(page.id, removal(badLayer))
            )
            for (command in commands) {
                val owner = MemoryDocumentAuthority(root()); val before = owner.snapshot(); val status = owner.status()
                assertEquals(DocumentRejection.INVALID_IDENTITY, assertIs<DocumentCommit.Rejected>(owner.commit(command)).reason)
                assertSame(before, owner.snapshot()); assertEquals(status, owner.status())
            }
        }
    }

    @Test fun initialMalformedIdentityCannotBecomeMutableAuthority() {
        val malformed = "\uD800"
        val badObject = StrokeObject(ObjectId(malformed), 1, page.layerId, brush, samples)
        val roots = listOf(DocumentRoot.empty(SceneId(malformed), "title", page),
            DocumentRoot.empty(doc, "title", page.copy(id = PageId(malformed))),
            DocumentRoot.empty(doc, "title", page.copy(layerId = LayerId(malformed))),
            DocumentRoot.compose("title", SceneSnapshot.restored(doc, 1, listOf(badObject)), listOf(page)))
        for (root in roots) {
            assertFailsWith<IllegalArgumentException> { MemoryDocumentAuthority(root) }
            assertEquals(PreviewOpenFailure.INVALID_CONTENT, PagedPreviewNotebook.open(root, "session").failure)
            assertFailsWith<IllegalArgumentException> { PagedPreviewCodec.encode(root) }
        }
    }

    @Test fun rejectedIdentityKeepsRedoAndValidSupplementaryIdsRoundTripExactly() {
        val owner = MemoryDocumentAuthority(root())
        val edit = DocumentCommand.Rename(CommandId("edit"), doc, 0, "after")
        val receipt = assertIs<DocumentCommit.Accepted>(owner.commit(edit)).receipt
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Undo(CommandId("undo"), doc, 1, edit.id)))
        val before = owner.snapshot(); val status = owner.status()
        assertIs<DocumentCommit.Rejected>(owner.commit(DocumentCommand.InsertPage(CommandId("retry"), doc, 2,
            page.copy(id = PageId("\uD800"), layerId = LayerId("new-layer")))))
        assertSame(before, owner.snapshot()); assertEquals(status, owner.status())
        assertSame(receipt, assertIs<DocumentCommit.Accepted>(owner.commit(edit)).receipt)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Redo(CommandId("redo"), doc, 2, edit.id)))
        val id = "中e\u0301😀"
        val validPage = page.copy(id = PageId(id), layerId = LayerId(id))
        val unicodeRoot = DocumentRoot.empty(SceneId(id), "title", validPage)
        val unicode = MemoryDocumentAuthority(unicodeRoot)
        assertIs<DocumentCommit.Accepted>(unicode.commit(DocumentCommand.Canvas(validPage.id,
            CanvasCommand.AddStroke(CommandId(id), 0, StrokeTarget(unicodeRoot.id, validPage.layerId, brush), ObjectId(id), samples))))
        val decoded = PagedPreviewCodec.decode(PagedPreviewCodec.encode(unicode.snapshot()))
        assertEquals(id, decoded.id.value); assertEquals(id, decoded.page(0).id.value)
        assertEquals(id, decoded.page(0).layerId.value); assertEquals(id, decoded.scene.objects().single().id.value)
    }
}
