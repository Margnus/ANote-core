package dev.notecore.document

import dev.notecore.canvas.prototype.*
import kotlin.test.*

class PdfDocumentModelRulesTest {
    private val source = PdfSourcePages("ab".repeat(32), 3)
    private fun descriptor(id: String, layer: String = "l-$id", contentRevision: Long = 0) =
        PageDescriptor(PageId(id), LayerId(layer), 595.0, 842.0, contentRevision = contentRevision)
    private fun sourcePages() = (0..2).map { PdfSourcePage(descriptor("p$it"), it) }
    private fun create(source: PdfSourcePages = this.source, pages: List<PdfSourcePage> = sourcePages()) =
        assertNotNull(PdfPageCatalogRules.createDocument("local", SceneId("doc"), "PDF 笔记", source, pages).model)
    private fun insert(model: PdfDocumentModel, page: PageDescriptor = descriptor("new"), before: PageId? = null,
                       source: PdfSourcePages? = this.source, sourceIndex: Int? = 0, operation: CommandId = CommandId("insert"),
                       catalog: PdfPageCatalog = model.catalog, root: DocumentRoot = model.root, space: String = "local",
                       revision: Long = catalog.revision) = PdfPageCatalogRules.planInsert(root, catalog, space, revision,
        page, before, source, sourceIndex, operation)

    @Test fun completeSourceCatalogCreatesStableMappingAndDoesNotRetainInputList() {
        val input = sourcePages().toMutableList()
        val model = assertNotNull(PdfPageCatalogRules.createDocument("local", SceneId("doc"), "PDF 笔记", source, input).model)
        input.clear()
        assertEquals(listOf("p0", "p1", "p2"), model.root.pages().map { it.id.value })
        assertEquals(listOf(0, 1, 2), model.catalog.references.map { it.sourceIndex })
        assertTrue(model.catalog.references.all { it.sourceSha256 == source.sha256 })
        assertEquals(listOf(source), model.catalog.sources)
        assertEquals(0, model.catalog.revision); assertTrue(model.catalog.complete)
        val projection = PdfPageCatalogRules.project(model.root, model.catalog, "local", 0)
        assertNull(projection.failure)
        assertEquals(listOf(1, 2, 3), projection.pages.map { it.sourceNumber })
        assertEquals(listOf(1, 2, 3), projection.pages.map { it.displayNumber })
        assertEquals(0, model.root.revision); assertEquals(0, model.root.scene.objectCount)
    }

    @Test fun sourceIdentityRequiresBothOriginalHashAndPageCount() {
        val model = create()
        assertNull(PdfPageCatalogRules.validateSource(model.root, model.catalog, "local", 0, source))
        for (changed in listOf(source.copy(sha256 = "cd".repeat(32)), source.copy(pageCount = 4))) {
            assertEquals(PdfPageCatalogFailure.SOURCE_CHANGED,
                PdfPageCatalogRules.validateSource(model.root, model.catalog, "local", 0, changed))
        }
        assertEquals(PdfPageCatalogFailure.INVALID_SOURCES,
            PdfPageCatalogRules.validateSource(model.root, model.catalog, "local", 0, source.copy(sha256 = "AB".repeat(32))))
        assertEquals(listOf(source), model.catalog.sources)
        assertEquals(3, model.root.pageCount)
    }

    @Test fun sourceInsertionPairsOriginalCommandWithMetadataAndKeepsRepeatedSourcePagesDistinct() {
        val model = create()
        val inserted = descriptor("copy")
        val plan = assertNotNull(insert(model, inserted, PageId("p1"), sourceIndex = 0).insertion)
        assertEquals(0, plan.expectedDocumentRevision); assertEquals(0, plan.expectedMetadataRevision)
        assertEquals(PdfPageRef(inserted.id, source.sha256, 0), plan.reference)
        assertEquals(1, plan.after.revision); assertEquals(4, plan.after.references.size)
        val owner = MemoryDocumentAuthority(model.root)
        assertIs<DocumentCommit.Accepted>(owner.commit(plan.command))
        val rows = PdfPageCatalogRules.project(owner.snapshot(), plan.after, "local", 1).pages
        assertEquals(listOf("p0", "copy", "p1", "p2"), rows.map { it.pageId.value })
        assertEquals(listOf(1, 1, 2, 3), rows.map { it.sourceNumber })
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.displayNumber })
        assertEquals(listOf(source), plan.after.sources)
        assertEquals(0, model.root.revision); assertEquals(3, model.root.pageCount)
        assertEquals(0, model.catalog.revision); assertEquals(3, model.catalog.references.size)
    }

    @Test fun blankInsertionHasNoSourceReferenceAndDoesNotRenumberExistingSourcePages() {
        val model = create()
        val plan = assertNotNull(insert(model, source = null, sourceIndex = null).insertion)
        assertNull(plan.reference); assertEquals(model.catalog.references, plan.after.references)
        val owner = MemoryDocumentAuthority(model.root)
        assertIs<DocumentCommit.Accepted>(owner.commit(plan.command))
        val rows = PdfPageCatalogRules.project(owner.snapshot(), plan.after, "local", 1).pages
        assertEquals(listOf("p0", "p1", "p2", "new"), rows.map { it.pageId.value })
        assertEquals(listOf(1, 2, 3, null), rows.map { it.sourceNumber })
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.displayNumber })
        assertNull(PdfPageCatalogRules.validateSource(owner.snapshot(), plan.after, "local", 1, source))
        assertEquals(3, model.catalog.references.size)
    }

    @Test fun changedOrMalformedInsertionSourceNeverBecomesBlankOrPartialPlan() {
        val model = create()
        for ((changed, reason) in listOf(
            source.copy(sha256 = "cd".repeat(32)) to PdfPageCatalogFailure.SOURCE_CHANGED,
            source.copy(pageCount = 4) to PdfPageCatalogFailure.SOURCE_CHANGED,
            source.copy(sha256 = "AB".repeat(32)) to PdfPageCatalogFailure.INVALID_SOURCES)) {
            val result = insert(model, source = changed)
            assertNull(result.insertion); assertEquals(reason, result.failure)
        }
        for ((changed, index) in listOf(source to null, source to -1, source to 3)) {
            val result = insert(model, source = changed, sourceIndex = index)
            assertNull(result.insertion); assertEquals(PdfPageCatalogFailure.INVALID_REFERENCES, result.failure)
        }
        val missingSource = insert(model, source = null, sourceIndex = 0)
        assertNull(missingSource.insertion); assertEquals(PdfPageCatalogFailure.INVALID_REFERENCES, missingSource.failure)
        assertEquals(3, model.root.pageCount); assertEquals(3, model.catalog.references.size)
    }

    @Test fun invalidInsertionIdentityAnchorAndRevisionNeverProduceCommands() {
        val model = create()
        for ((page, before, operation, reason) in listOf(
            Quadruple(descriptor("p0", "other"), null, CommandId("duplicate"), PdfPageCatalogFailure.REUSED_IDENTITY),
            Quadruple(descriptor("unique", "l-p0"), null, CommandId("duplicate-layer"), PdfPageCatalogFailure.REUSED_IDENTITY),
            Quadruple(descriptor("new"), PageId("missing"), CommandId("missing"), PdfPageCatalogFailure.MISSING_PAGE),
            Quadruple(descriptor("new", contentRevision = 1), null, CommandId("revision"), PdfPageCatalogFailure.INVALID_PAGE),
            Quadruple(descriptor("new", layer = "\uD800"), null, CommandId("page"), PdfPageCatalogFailure.INVALID_PAGE),
            Quadruple(descriptor("new"), null, CommandId("\uD800"), PdfPageCatalogFailure.INVALID_OPERATION))) {
            val result = insert(model, page, before, operation = operation)
            assertNull(result.insertion); assertEquals(reason, result.failure)
        }
        val exhausted = model.catalog.copy(revision = Long.MAX_VALUE)
        val result = insert(model, catalog = exhausted, revision = Long.MAX_VALUE)
        assertNull(result.insertion); assertEquals(PdfPageCatalogFailure.REVISION_EXHAUSTED, result.failure)
        assertEquals(3, model.root.pageCount); assertEquals(0, model.root.revision)
    }

    @Test fun staleOrInvalidCatalogCannotPlanInsertion() {
        val model = create()
        for ((catalog, space, revision, reason) in listOf(
            Quadruple(model.catalog.copy(spaceId = "other"), "local", 0L, PdfPageCatalogFailure.WRONG_SPACE),
            Quadruple(model.catalog, "other", 0L, PdfPageCatalogFailure.WRONG_SPACE),
            Quadruple(model.catalog.copy(documentId = SceneId("other")), "local", 0L, PdfPageCatalogFailure.WRONG_DOCUMENT),
            Quadruple(model.catalog.copy(revision = 1), "local", 0L, PdfPageCatalogFailure.STALE_METADATA),
            Quadruple(model.catalog.copy(complete = false), "local", 0L, PdfPageCatalogFailure.INCOMPLETE),
            Quadruple(model.catalog.copy(references = model.catalog.references.dropLast(1) +
                PdfPageRef(PageId("missing"), source.sha256, 2)), "local", 0L, PdfPageCatalogFailure.INVALID_REFERENCES))) {
            val result = insert(model, catalog = catalog, space = space, revision = revision)
            assertNull(result.insertion); assertEquals(reason, result.failure)
        }
    }

    @Test fun insertionRevalidationRejectsStaleRevisionsAndChangedPayload() {
        val model = create()
        val candidate = assertNotNull(insert(model).insertion)
        assertNotNull(PdfPageCatalogRules.validateInsert(model.root, model.catalog, "local", 0, candidate).insertion)
        val blank = assertNotNull(insert(model, source = null, sourceIndex = null).insertion)
        assertNotNull(PdfPageCatalogRules.validateInsert(model.root, model.catalog, "local", 0, blank).insertion)
        val owner = MemoryDocumentAuthority(model.root)
        assertIs<DocumentCommit.Accepted>(owner.commit(DocumentCommand.Rename(CommandId("rename"), model.root.id, 0, "renamed")))
        assertEquals(PdfPageCatalogFailure.STALE_PLAN,
            PdfPageCatalogRules.validateInsert(owner.snapshot(), model.catalog, "local", 0, candidate).failure)
        val newerMetadata = model.catalog.copy(revision = 1)
        assertEquals(PdfPageCatalogFailure.STALE_PLAN,
            PdfPageCatalogRules.validateInsert(model.root, newerMetadata, "local", 1, candidate).failure)
        for (changed in listOf(
            candidate.copy(reference = candidate.reference!!.copy(sourceIndex = 1)),
            candidate.copy(after = candidate.after.copy(references = candidate.after.references.dropLast(1))),
            candidate.copy(expectedDocumentRevision = 1),
            candidate.copy(after = candidate.after.copy(sources = emptyList())))) {
            assertNull(PdfPageCatalogRules.validateInsert(model.root, model.catalog, "local", 0, changed).insertion)
        }
        assertEquals(0, model.root.revision); assertEquals(3, model.catalog.references.size)
    }

    @Test fun documentPageCapacityIsCheckedBeforeCreatingOrPlanning() {
        val largeSource = source.copy(pageCount = 4096)
        val pages = (0 until 4096).map { PdfSourcePage(descriptor("p$it"), it) }
        val model = create(largeSource, pages)
        val result = insert(model)
        assertNull(result.insertion); assertEquals(PdfPageCatalogFailure.CAPACITY, result.failure)
        assertEquals(4096, model.root.pageCount); assertEquals(4096, model.catalog.references.size)
    }

    @Test fun invalidInitialSourceTitleIdentityAndPageOrderAreRejectedWithoutModel() {
        val valid = sourcePages()
        for ((source, title, pages, reason) in listOf(
            Quadruple(source.copy(sha256 = "AB".repeat(32)), "title", valid, PdfPageCatalogFailure.INVALID_SOURCES),
            Quadruple(source.copy(pageCount = 0), "title", valid, PdfPageCatalogFailure.INVALID_SOURCES),
            Quadruple(source, "x".repeat(121), valid, PdfPageCatalogFailure.INVALID_TITLE),
            Quadruple(source, "\uD800", valid, PdfPageCatalogFailure.INVALID_TITLE),
            Quadruple(source, "title", emptyList(), PdfPageCatalogFailure.CAPACITY),
            Quadruple(source.copy(pageCount = 4097), "title", valid, PdfPageCatalogFailure.CAPACITY),
            Quadruple(source, "title", valid.take(2), PdfPageCatalogFailure.INVALID_REFERENCES),
            Quadruple(source, "title", listOf(valid[0], valid[2], valid[1]), PdfPageCatalogFailure.INVALID_REFERENCES),
            Quadruple(source, "title", valid.mapIndexed { index, page ->
                if (index == 1) page.copy(descriptor = descriptor("p0", "other")) else page
            }, PdfPageCatalogFailure.REUSED_IDENTITY),
            Quadruple(source, "title", valid.mapIndexed { index, page ->
                if (index == 1) page.copy(descriptor = descriptor("unique", "l-p0")) else page
            }, PdfPageCatalogFailure.REUSED_IDENTITY),
            Quadruple(source, "title", valid.mapIndexed { index, page ->
                if (index == 1) page.copy(descriptor = page.descriptor.copy(contentRevision = 1)) else page
            }, PdfPageCatalogFailure.INVALID_PAGE))) {
            val result = PdfPageCatalogRules.createDocument("local", SceneId("doc"), title, source, pages)
            assertNull(result.model); assertEquals(reason, result.failure)
        }
        for (space in listOf("", " ", "x".repeat(129), "\uD800")) {
            assertEquals(PdfPageCatalogFailure.WRONG_SPACE,
                PdfPageCatalogRules.createDocument(space, SceneId("doc"), "title", source, valid).failure)
        }
        assertEquals(PdfPageCatalogFailure.INVALID_DOCUMENT,
            PdfPageCatalogRules.createDocument("local", SceneId("\uD800"), "title", source, valid).failure)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
