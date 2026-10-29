package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PdfPageCatalogRulesTest {
    private val a = PageDescriptor(PageId("a"), LayerId("la"), 595.0, 842.0)
    private val b = PageDescriptor(PageId("b"), LayerId("lb"), 595.0, 842.0)
    private val root = DocumentRoot.compose("pdf", SceneSnapshot.empty(SceneId("doc")), listOf(a, b))
    private val source = PdfSourcePages("ab".repeat(32), 8)
    private val catalog = PdfPageCatalog("local", root.id, 5, true, listOf(source),
        listOf(PdfPageRef(a.id, source.sha256, 0), PdfPageRef(b.id, source.sha256, 7)))
    private fun project(root: DocumentRoot = this.root, s: PdfPageCatalog = catalog) = PdfPageCatalogRules.project(root, s, "local", 5)

    @Test fun originalReorderAndBlankInsertKeepPhysicalSourceNumbers() {
        val owner = MemoryDocumentAuthority(root)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.MovePage(CommandId("move"), root.id, 0, b.id, a.id)))
        val blank = PageDescriptor(PageId("blank"), LayerId("blank-layer"), 595.0, 842.0)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.InsertPage(CommandId("insert"), root.id, 1, blank, a.id)))
        val rows = project(owner.snapshot()).pages
        assertEquals(listOf(b.id, blank.id, a.id), rows.map { it.pageId })
        assertEquals(listOf(1, 2, 3), rows.map { it.displayNumber })
        assertEquals(listOf(8, null, 1), rows.map { it.sourceNumber })
        assertEquals(listOf(source), catalog.sources)
        assertEquals(0, catalog.references.first().sourceIndex)
    }

    @Test fun deletePlanPairsOriginalCommandWithExactReferenceAndLeavesSourceRegistry() {
        val deletion = assertNotNull(PdfPageCatalogRules.planDelete(root, catalog, "local", 5, a.id, CommandId("delete")).deletion)
        assertEquals(root.revision, deletion.expectedDocumentRevision); assertEquals(5, deletion.expectedMetadataRevision)
        assertEquals(catalog.references.first(), deletion.before)
        val owner = MemoryDocumentAuthority(root)
        val command = assertNotNull(deletion.command)
        assertIs<DocumentCommit.Accepted>(owner.commit(command))
        val after = catalog.copy(revision = 6, references = catalog.references.filterNot { it.pageId == deletion.before.pageId })
        val rows = PdfPageCatalogRules.project(owner.snapshot(), after, "local", 6).pages
        assertEquals(listOf(PdfPagePosition(b.id, 1, source.sha256, 8)), rows)
        assertEquals(catalog.sources, after.sources)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Undo(CommandId("undo"), root.id, owner.snapshot().revision, command.id)))
        // Reference restoration must be paired by future metadata history, never inferred from document undo alone.
        assertNull(PdfPageCatalogRules.project(owner.snapshot(), after, "local", 6).pages.first().sourceNumber)
        assertEquals(listOf(1, 8), project(owner.snapshot()).pages.map { it.sourceNumber })
    }

    @Test fun lastBlankPdfBackgroundRemovalHasNoFakeDocumentCommit() {
        val single = DocumentRoot.empty(root.id, root.title, a)
        val metadata = catalog.copy(references = listOf(catalog.references.first()))
        val deletion = assertNotNull(PdfPageCatalogRules.planDelete(single, metadata, "local", 5, a.id, CommandId("remove")).deletion)
        assertNull(deletion.command); assertEquals(a.id, deletion.before.pageId)
        assertEquals(CommandId("remove"), deletion.operationId)
        val cleared = metadata.copy(revision = 6, references = emptyList())
        assertEquals(listOf(PdfPagePosition(a.id, 1, null, null)), PdfPageCatalogRules.project(single, cleared, "local", 6).pages)
        assertEquals(1, single.pageCount); assertEquals(0, single.revision); assertEquals(listOf(source), cleared.sources)
        val patterned = DocumentRoot.empty(root.id, root.title, a.copy(paper = PaperKind.GRID))
        assertNotNull(PdfPageCatalogRules.planDelete(patterned, metadata, "local", 5, a.id, CommandId("pattern")).deletion!!.command)
    }

    @Test fun duplicatedSourcePageUnderDifferentStablePageIdsIsAllowed() {
        val copied = catalog.copy(references = catalog.references.map { it.copy(sourceIndex = 0) })
        assertEquals(listOf(1, 1), project(s = copied).pages.map { it.sourceNumber })
        assertEquals(listOf(a.id, b.id), project(s = copied).pages.map { it.pageId })
    }

    @Test fun invalidMetadataNeverProducesPartialProjectionOrDeletion() {
        for ((s, reason) in listOf(
            catalog.copy(spaceId = "other") to PdfPageCatalogFailure.WRONG_SPACE,
            catalog.copy(documentId = SceneId("other")) to PdfPageCatalogFailure.WRONG_DOCUMENT,
            catalog.copy(revision = 6) to PdfPageCatalogFailure.STALE_METADATA,
            catalog.copy(complete = false) to PdfPageCatalogFailure.INCOMPLETE,
            catalog.copy(sources = listOf(source, source)) to PdfPageCatalogFailure.INVALID_SOURCES,
            catalog.copy(sources = listOf(source.copy(sha256 = "AB".repeat(32)))) to PdfPageCatalogFailure.INVALID_SOURCES,
            catalog.copy(sources = listOf(source.copy(pageCount = 0))) to PdfPageCatalogFailure.INVALID_SOURCES,
            catalog.copy(references = listOf(catalog.references.first(), catalog.references.first())) to PdfPageCatalogFailure.INVALID_REFERENCES,
            catalog.copy(references = listOf(catalog.references.first().copy(sourceIndex = 8))) to PdfPageCatalogFailure.INVALID_REFERENCES,
            catalog.copy(references = listOf(catalog.references.first().copy(pageId = PageId("missing")))) to PdfPageCatalogFailure.INVALID_REFERENCES,
            catalog.copy(references = listOf(catalog.references.first().copy(sourceSha256 = "cd".repeat(32)))) to PdfPageCatalogFailure.INVALID_REFERENCES)) {
            val projected = project(s = s); assertEquals(reason, projected.failure); assertTrue(projected.pages.isEmpty())
            val plan = PdfPageCatalogRules.planDelete(root, s, "local", 5, a.id, CommandId("bad"))
            assertNull(plan.deletion); assertEquals(reason, plan.failure)
        }
    }

    @Test fun oldMetadataAndDocumentCommandAreRejectedAndBlankPagesAreNotSourcePages() {
        val deletion = assertNotNull(PdfPageCatalogRules.planDelete(root, catalog, "local", 5, b.id, CommandId("delete")).deletion)
        val owner = MemoryDocumentAuthority(root)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Rename(CommandId("rename"), root.id, 0, "new")))
        val before = owner.snapshot()
        assertIs<DocumentCommit.Rejected>(owner.commit(assertNotNull(deletion.command))); assertSame(before, owner.snapshot())
        assertEquals(PdfPageCatalogFailure.STALE_METADATA,
            PdfPageCatalogRules.planDelete(root, catalog.copy(revision = 6), "local", 5, b.id, CommandId("old")).failure)
        assertEquals(PdfPageCatalogFailure.NOT_SOURCE_PAGE,
            PdfPageCatalogRules.planDelete(root, catalog.copy(references = emptyList()), "local", 5, a.id, CommandId("blank")).failure)
        assertEquals(2, root.pageCount); assertEquals(listOf(source), catalog.sources)
    }
}
