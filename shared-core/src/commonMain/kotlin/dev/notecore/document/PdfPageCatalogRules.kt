package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import dev.notecore.canvas.prototype.SceneId

internal data class PdfSourcePages(val sha256: String, val pageCount: Int)
internal data class PdfPageRef(val pageId: PageId, val sourceSha256: String, val sourceIndex: Int)
internal data class PdfPageCatalog(
    val spaceId: String, val documentId: SceneId, val revision: Long, val complete: Boolean,
    val sources: List<PdfSourcePages>, val references: List<PdfPageRef>,
)
internal enum class PdfPageCatalogFailure {
    WRONG_SPACE, WRONG_DOCUMENT, STALE_METADATA, INCOMPLETE, CAPACITY, INVALID_SOURCES, INVALID_REFERENCES, NOT_SOURCE_PAGE,
}
internal data class PdfPagePosition(val pageId: PageId, val displayNumber: Int, val sourceSha256: String?, val sourceNumber: Int?)
internal data class PdfPageProjection(val pages: List<PdfPagePosition> = emptyList(), val failure: PdfPageCatalogFailure? = null)
internal data class PdfPageDeletion(
    val spaceId: String, val documentId: SceneId, val operationId: CommandId,
    val expectedDocumentRevision: Long, val expectedMetadataRevision: Long,
    val before: PdfPageRef, val command: DocumentCommand.DeletePage?,
)
internal data class PdfPageDeletionPlan(val deletion: PdfPageDeletion? = null, val failure: PdfPageCatalogFailure? = null)

internal object PdfPageCatalogRules {
    private fun hash(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    private fun validate(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long): PdfPageCatalogFailure? {
        if (space.isBlank() || space.length > 128 || !space.hasValidUnicode() || space != s.spaceId) return PdfPageCatalogFailure.WRONG_SPACE
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

    fun project(root: DocumentRoot, s: PdfPageCatalog, space: String, revision: Long): PdfPageProjection {
        validate(root, s, space, revision)?.let { return PdfPageProjection(failure = it) }
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
