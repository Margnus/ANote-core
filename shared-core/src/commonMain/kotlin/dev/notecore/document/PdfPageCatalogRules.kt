package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import dev.notecore.canvas.prototype.SceneId
import dev.notecore.canvas.prototype.SceneSnapshot

internal data class PdfSourcePages(val sha256: String, val pageCount: Int)
internal data class PdfSourcePage(val descriptor: PageDescriptor, val sourceIndex: Int)
internal data class PdfDocumentModel(val root: DocumentRoot, val catalog: PdfPageCatalog)
internal data class PdfDocumentModelResult(val model: PdfDocumentModel? = null, val failure: PdfPageCatalogFailure? = null)
internal data class PdfPageRef(val pageId: PageId, val sourceSha256: String, val sourceIndex: Int)
internal data class PdfPageCatalog(
    val spaceId: String, val documentId: SceneId, val revision: Long, val complete: Boolean,
    val sources: List<PdfSourcePages>, val references: List<PdfPageRef>,
)
internal enum class PdfPageCatalogFailure {
    WRONG_SPACE, WRONG_DOCUMENT, STALE_METADATA, INCOMPLETE, CAPACITY, INVALID_SOURCES, INVALID_REFERENCES, NOT_SOURCE_PAGE,
    INVALID_TITLE, INVALID_DOCUMENT, INVALID_PAGE, INVALID_OPERATION, REUSED_IDENTITY, MISSING_PAGE, SOURCE_CHANGED,
    REVISION_EXHAUSTED, STALE_PLAN,
}
internal data class PdfPagePosition(val pageId: PageId, val displayNumber: Int, val sourceSha256: String?, val sourceNumber: Int?)
internal data class PdfPageProjection(val pages: List<PdfPagePosition> = emptyList(), val failure: PdfPageCatalogFailure? = null)
internal data class PdfPageDeletion(
    val spaceId: String, val documentId: SceneId, val operationId: CommandId,
    val expectedDocumentRevision: Long, val expectedMetadataRevision: Long,
    val before: PdfPageRef, val command: DocumentCommand.DeletePage?,
)
internal data class PdfPageDeletionPlan(val deletion: PdfPageDeletion? = null, val failure: PdfPageCatalogFailure? = null)
internal data class PdfPageInsertion(
    val spaceId: String, val documentId: SceneId, val operationId: CommandId,
    val expectedDocumentRevision: Long, val expectedMetadataRevision: Long,
    val command: DocumentCommand.InsertPage, val reference: PdfPageRef?, val after: PdfPageCatalog,
)
internal data class PdfPageInsertionPlan(val insertion: PdfPageInsertion? = null, val failure: PdfPageCatalogFailure? = null)

internal object PdfPageCatalogRules {
    private const val MAX_DOCUMENT_PAGES = 4096
    private fun hash(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    private fun validSpace(value: String) = value.isNotBlank() && value.length <= 128 && value.hasValidUnicode()
    private fun validPageIdentity(page: PageDescriptor) = page.id.value.hasValidUnicode() && page.layerId.value.hasValidUnicode()
    private fun validate(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long): PdfPageCatalogFailure? {
        if (!validSpace(space) || space != s.spaceId) return PdfPageCatalogFailure.WRONG_SPACE
        if (root.id != s.documentId) return PdfPageCatalogFailure.WRONG_DOCUMENT
        if (s.revision < 0 || s.revision != revision) return PdfPageCatalogFailure.STALE_METADATA
        if (!s.complete) return PdfPageCatalogFailure.INCOMPLETE
        if (s.sources.size > 1000 || s.references.size > root.pageCount) return PdfPageCatalogFailure.CAPACITY
        val sources = s.sources.associateBy { it.sha256 }
        if (sources.size != s.sources.size || s.sources.any { !hash(it.sha256) || it.pageCount !in 1..100000 }) {
            return PdfPageCatalogFailure.INVALID_SOURCES
        }
        val pages = root.pages().map { it.id }.toSet()
        if (s.references.map { it.pageId }.toSet().size != s.references.size || s.references.any {
            !it.pageId.value.hasValidUnicode() || it.pageId !in pages || it.sourceIndex < 0 ||
                it.sourceIndex >= (sources[it.sourceSha256]?.pageCount ?: 0)
        }) return PdfPageCatalogFailure.INVALID_REFERENCES
        return null
    }

    fun createDocument(space: String, documentId: SceneId, title: String, source: PdfSourcePages,
                       pages: List<PdfSourcePage>): PdfDocumentModelResult {
        if (!validSpace(space)) return PdfDocumentModelResult(failure = PdfPageCatalogFailure.WRONG_SPACE)
        if (!documentId.value.hasValidUnicode()) return PdfDocumentModelResult(failure = PdfPageCatalogFailure.INVALID_DOCUMENT)
        if (title.length > 120 || !title.hasValidUnicode()) return PdfDocumentModelResult(failure = PdfPageCatalogFailure.INVALID_TITLE)
        if (!hash(source.sha256) || source.pageCount !in 1..100000) {
            return PdfDocumentModelResult(failure = PdfPageCatalogFailure.INVALID_SOURCES)
        }
        if (pages.isEmpty() || pages.size > MAX_DOCUMENT_PAGES || source.pageCount > MAX_DOCUMENT_PAGES) {
            return PdfDocumentModelResult(failure = PdfPageCatalogFailure.CAPACITY)
        }
        if (pages.size != source.pageCount) return PdfDocumentModelResult(failure = PdfPageCatalogFailure.INVALID_REFERENCES)
        if (pages.any { it.descriptor.contentRevision != 0L || !validPageIdentity(it.descriptor) }) {
            return PdfDocumentModelResult(failure = PdfPageCatalogFailure.INVALID_PAGE)
        }
        if (pages.map { it.descriptor.id }.toSet().size != pages.size ||
            pages.map { it.descriptor.layerId }.toSet().size != pages.size) {
            return PdfDocumentModelResult(failure = PdfPageCatalogFailure.REUSED_IDENTITY)
        }
        if (pages.withIndex().any { (index, page) -> page.sourceIndex != index }) {
            return PdfDocumentModelResult(failure = PdfPageCatalogFailure.INVALID_REFERENCES)
        }
        val descriptors = pages.map { it.descriptor }
        val root = DocumentRoot.compose(title, SceneSnapshot.empty(documentId), descriptors)
        val catalog = PdfPageCatalog(space, documentId, 0, true, listOf(source),
            descriptors.mapIndexed { index, page -> PdfPageRef(page.id, source.sha256, index) })
        validate(root, catalog, space, 0)?.let { return PdfDocumentModelResult(failure = it) }
        return PdfDocumentModelResult(PdfDocumentModel(root, catalog))
    }

    fun validateSource(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long,
                       expected: PdfSourcePages): PdfPageCatalogFailure? {
        validate(root, s, space, revision)?.let { return it }
        return validateRegisteredSource(s, expected)
    }

    private fun validateRegisteredSource(s: PdfPageCatalog, expected: PdfSourcePages): PdfPageCatalogFailure? {
        if (!hash(expected.sha256) || expected.pageCount !in 1..100000) return PdfPageCatalogFailure.INVALID_SOURCES
        val registered = s.sources.singleOrNull { it.sha256 == expected.sha256 } ?: return PdfPageCatalogFailure.SOURCE_CHANGED
        return if (registered == expected) null else PdfPageCatalogFailure.SOURCE_CHANGED
    }

    fun planInsert(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long,
                   page: PageDescriptor, before: PageId?, source: PdfSourcePages?, sourceIndex: Int?,
                   operation: CommandId): PdfPageInsertionPlan {
        validate(root, s, space, revision)?.let { return PdfPageInsertionPlan(failure = it) }
        if (!operation.value.hasValidUnicode()) return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.INVALID_OPERATION)
        if (page.contentRevision != 0L || !validPageIdentity(page) || before?.value?.hasValidUnicode() == false) {
            return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.INVALID_PAGE)
        }
        if (root.findPage(page.id) != null || root.pages().any { it.layerId == page.layerId }) {
            return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.REUSED_IDENTITY)
        }
        if (before != null && root.findPage(before) == null) return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.MISSING_PAGE)
        if (root.pageCount >= MAX_DOCUMENT_PAGES) return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.CAPACITY)
        if (root.revision == Long.MAX_VALUE || revision == Long.MAX_VALUE) {
            return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.REVISION_EXHAUSTED)
        }
        val reference = if (source == null && sourceIndex == null) null else {
            if (source == null || sourceIndex == null) return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.INVALID_REFERENCES)
            validateRegisteredSource(s, source)?.let { return PdfPageInsertionPlan(failure = it) }
            if (sourceIndex !in 0 until source.pageCount) return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.INVALID_REFERENCES)
            PdfPageRef(page.id, source.sha256, sourceIndex)
        }
        val command = DocumentCommand.InsertPage(operation, root.id, root.revision, page, before)
        val after = s.copy(revision = revision + 1, sources = s.sources.toList(),
            references = s.references + listOfNotNull(reference))
        return PdfPageInsertionPlan(PdfPageInsertion(space, root.id, operation, root.revision, revision,
            command, reference, after))
    }

    fun validateInsert(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long,
                       candidate: PdfPageInsertion): PdfPageInsertionPlan {
        val reference = candidate.reference
        val source = if (reference == null) null else {
            candidate.after.sources.singleOrNull { it.sha256 == reference.sourceSha256 }
                ?: return PdfPageInsertionPlan(failure = PdfPageCatalogFailure.STALE_PLAN)
        }
        val fresh = planInsert(root, s, space, revision, candidate.command.page, candidate.command.before,
            source, reference?.sourceIndex, candidate.operationId)
        val insertion = fresh.insertion ?: return fresh
        return if (insertion == candidate) fresh else PdfPageInsertionPlan(failure = PdfPageCatalogFailure.STALE_PLAN)
    }

    fun project(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long,
                expectedSource: PdfSourcePages? = null): PdfPageProjection {
        validate(root, s, space, revision)?.let { return PdfPageProjection(failure = it) }
        if (expectedSource != null) {
            validateRegisteredSource(s, expectedSource)?.let { return PdfPageProjection(failure = it) }
        }
        val refs = s.references.associateBy { it.pageId }
        return PdfPageProjection(root.pages().mapIndexed { index, page ->
            val ref = refs[page.id]
            PdfPagePosition(page.id, index + 1, ref?.sourceSha256, ref?.sourceIndex?.plus(1))
        })
    }

    fun planDelete(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long,
                   page: PageId, operation: CommandId): PdfPageDeletionPlan {
        validate(root, s, space, revision)?.let { return PdfPageDeletionPlan(failure = it) }
        val ref = s.references.singleOrNull { it.pageId == page }
            ?: return PdfPageDeletionPlan(failure = PdfPageCatalogFailure.NOT_SOURCE_PAGE)
        val metadataOnly = root.pageCount == 1 && root.page(0).paper == PaperKind.BLANK && root.scene.objectCount == 0
        val command = if (metadataOnly) null else DocumentCommand.DeletePage(operation, root.id, root.revision, page)
        return PdfPageDeletionPlan(PdfPageDeletion(space, root.id, operation, root.revision, revision, ref, command))
    }
}
