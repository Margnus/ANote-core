package dev.notecore.document

import dev.notecore.document.hasValidUnicode

/** Platform adapter provides the raw PDF outline tree; destinations are 0-based physical source page indices. */
internal data class PdfOutlineDest(
    val sourceIndex: Int,
    val title: String,
    val children: List<PdfOutlineDest> = emptyList(),
)

/** Mapped outline node bound to the note document's stable page identity and display order. */
internal data class PdfOutlineNode(
    val pageId: PageId,
    val title: String,
    val displayNumber: Int,
    val sourceNumber: Int,
    val children: List<PdfOutlineNode> = emptyList(),
)

internal enum class PdfOutlineFailure { INVALID_CATALOG, CAPACITY }

internal data class PdfOutlineResult(
    val nodes: List<PdfOutlineNode> = emptyList(),
    val outlineAvailable: Boolean = false,
    val skippedCount: Int = 0,
    val failure: PdfOutlineFailure? = null,
    val catalogFailure: PdfPageCatalogFailure? = null,
)

internal object PdfOutlineRules {
    const val MAX_ENTRIES = 1000
    const val MAX_DEPTH = 5
    private const val MAX_TITLE_LENGTH = 120

    private fun countEntries(entries: List<PdfOutlineDest>): Int =
        entries.size + entries.sumOf { countEntries(it.children) }

    private fun validTitle(title: String) = title.isNotBlank() && title.length <= MAX_TITLE_LENGTH &&
        title.hasValidUnicode()

    fun map(root: DocumentRoot, catalog: PdfPageCatalog, space: String, metadataRevision: Long,
            source: PdfSourcePages, outline: List<PdfOutlineDest>?): PdfOutlineResult {
        val projection = PdfPageCatalogRules.project(root, catalog, space, metadataRevision)
        projection.failure?.let {
            return PdfOutlineResult(failure = PdfOutlineFailure.INVALID_CATALOG, catalogFailure = it)
        }
        if (outline == null) return PdfOutlineResult(outlineAvailable = false)
        if (countEntries(outline) > MAX_ENTRIES) return PdfOutlineResult(failure = PdfOutlineFailure.CAPACITY)
        val sourceToPosition = mutableMapOf<Int, PdfPagePosition>()
        for (position in projection.pages) {
            if (position.sourceSha256 == source.sha256 && position.sourceNumber != null) {
                sourceToPosition.putIfAbsent(position.sourceNumber - 1, position)
            }
        }
        var skipped = 0
        fun mapLevel(entries: List<PdfOutlineDest>, depth: Int): List<PdfOutlineNode> {
            val nodes = mutableListOf<PdfOutlineNode>()
            for (entry in entries) {
                if (depth >= MAX_DEPTH || !validTitle(entry.title) ||
                    entry.sourceIndex !in 0 until source.pageCount) {
                    skipped++; continue
                }
                val position = sourceToPosition[entry.sourceIndex]
                if (position == null) { skipped++; continue }
                val children = if (entry.children.isEmpty()) emptyList()
                else mapLevel(entry.children, depth + 1)
                nodes.add(PdfOutlineNode(position.pageId, entry.title,
                    position.displayNumber, position.sourceNumber!!, children))
            }
            return nodes
        }
        val nodes = mapLevel(outline.toList(), 0)
        return PdfOutlineResult(nodes, outlineAvailable = true, skippedCount = skipped)
    }
}
