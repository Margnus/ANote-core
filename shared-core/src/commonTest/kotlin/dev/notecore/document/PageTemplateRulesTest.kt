package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PageTemplateRulesTest {
    private val page = PageDescriptor(PageId("page"), LayerId("layer"), 595.0, 842.0)
    private val root = DocumentRoot.empty(SceneId("document"), "纸张", page)
    private fun command(root: DocumentRoot, paper: PaperKind, size: PageSizePreset, id: String = "paper") =
        assertNotNull(PageTemplateRules.plan(root, page.id, CommandId(id), paper, size).command)
    private fun accept(owner: MemoryDocumentAuthority, command: DocumentCommand) =
        assertIs<DocumentCommit.Accepted>(owner.commit(command))

    @Test fun allFourPapersUseExistingA4CoordinateConventionAndOrientation() {
        for (paper in PaperKind.entries) {
            assertEquals(PageTemplate(595.0, 842.0, paper), PageTemplateRules.resolve(paper, PageSizePreset.A4()).template)
            assertEquals(PageTemplate(842.0, 595.0, paper), PageTemplateRules.resolve(paper, PageSizePreset.A4(true)).template)
        }
    }

    @Test fun screenRatioUsesSnapshotOrientationAndIgnoresUniformDensityScale() {
        for ((w, h) in listOf(1080.0 to 1920.0, 1920.0 to 1080.0, 1000.0 to 1000.0)) {
            for (paper in PaperKind.entries) {
                val template = assertNotNull(PageTemplateRules.resolve(paper, PageSizePreset.Screen(w, h)).template)
                assertEquals(w / h, template.width / template.height, 1e-12)
                assertEquals(595.0, minOf(template.width, template.height))
                assertEquals(template, PageTemplateRules.resolve(paper, PageSizePreset.Screen(w * 2, h * 2)).template)
            }
        }
        assertEquals(PageTemplate(595.0, 595.0, PaperKind.BLANK),
            PageTemplateRules.resolve(PaperKind.BLANK, PageSizePreset.Screen(Double.MAX_VALUE, Double.MAX_VALUE)).template)
    }

    @Test fun invalidViewportAndExtremeAspectRatioNeverProduceCommands() {
        for ((w, h) in listOf(0.0 to 1.0, 1.0 to -1.0, Double.NaN to 1.0, 1.0 to Double.POSITIVE_INFINITY)) {
            val result = PageTemplateRules.plan(root, page.id, CommandId("bad"), PaperKind.GRID, PageSizePreset.Screen(w, h))
            assertNull(result.command); assertEquals(PageTemplateFailure.INVALID_VIEWPORT, result.failure)
        }
        for (size in listOf(PageSizePreset.Screen(1.0, 100.0), PageSizePreset.Screen(Double.MIN_VALUE, Double.MAX_VALUE))) {
            val result = PageTemplateRules.plan(root, page.id, CommandId("wide"), PaperKind.DOTS, size)
            assertNull(result.command); assertEquals(PageTemplateFailure.SIZE_LIMIT, result.failure)
        }
        assertEquals(PaperKind.BLANK, root.page(0).paper)
    }

    @Test fun templateChangeUsesOriginalAuthorityAndKeepsInkThroughUndoRedoAndCodec() {
        for (paper in PaperKind.entries) {
            val owner = MemoryDocumentAuthority(root)
            val samples = StrokeSamples.copyOf(doubleArrayOf(1.0, 2.5), doubleArrayOf(3.0, 4.5),
                floatArrayOf(.3f, .9f), longArrayOf(1, 7))
            val brush = BrushSpec(BrushKind.MONOLINE, 1, 2.0, 0xff112233.toInt())
            accept(owner, DocumentCommand.Canvas(page.id, CanvasCommand.AddStroke(CommandId("ink"), 0,
                StrokeTarget(root.id, page.layerId, brush), ObjectId("stroke"), samples)))
            val before = owner.snapshot()
            val changed = command(before, paper, PageSizePreset.Screen(1200.0, 800.0))
            accept(owner, changed)
            val after = owner.snapshot()
            assertEquals(892.5, after.page(0).width); assertEquals(595.0, after.page(0).height)
            assertEquals(paper, after.page(0).paper)
            assertEquals(before.pageObjects(page.id), after.pageObjects(page.id))
            assertEquals(before.id, after.id); assertEquals(page.id, after.page(0).id)
            val encoded = PagedPreviewCodec.encode(after)
            assertEquals(encoded, PagedPreviewCodec.encode(PagedPreviewCodec.decode(encoded)))
            accept(owner, DocumentCommand.Undo(CommandId("undo"), root.id, after.revision, changed.id))
            assertEquals(595.0, owner.snapshot().page(0).width); assertEquals(PaperKind.BLANK, owner.snapshot().page(0).paper)
            assertEquals(before.pageObjects(page.id), owner.snapshot().pageObjects(page.id))
            accept(owner, DocumentCommand.Redo(CommandId("redo"), root.id, owner.snapshot().revision, changed.id))
            assertEquals(after.page(0).width, owner.snapshot().page(0).width)
            assertEquals(paper, owner.snapshot().page(0).paper)
            assertEquals(before.pageObjects(page.id), owner.snapshot().pageObjects(page.id))
        }
    }

    @Test fun missingPageAndStaleTemplateCannotChangeDocument() {
        assertEquals(PageTemplateFailure.PAGE_NOT_FOUND,
            PageTemplateRules.plan(root, PageId("missing"), CommandId("missing"), PaperKind.GRID, PageSizePreset.A4()).failure)
        val owner = MemoryDocumentAuthority(root)
        val stale = command(root, PaperKind.GRID, PageSizePreset.A4(), "stale")
        accept(owner, command(root, PaperKind.DOTS, PageSizePreset.A4(), "first"))
        val before = owner.snapshot()
        assertIs<DocumentCommit.Rejected>(owner.commit(stale))
        assertSame(before, owner.snapshot())
    }
}
