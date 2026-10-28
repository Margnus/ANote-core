package dev.notecore.document

import dev.notecore.canvas.prototype.SceneId

internal data class PageBookmarkSnapshot(
    val documentId: SceneId, val revision: Long, val complete: Boolean, val pages: List<PageId>,
)
internal enum class PageBookmarkFailure {
    WRONG_DOCUMENT, STALE_METADATA, INCOMPLETE_SNAPSHOT, CAPACITY, INVALID_BOOKMARKS, MISSING_PAGE, NOT_BOOKMARKED,
}
internal data class PageBookmarkPatch(
    val documentId: SceneId, val expectedDocumentRevision: Long, val expectedMetadataRevision: Long,
    val pageId: PageId, val beforeMarked: Boolean, val afterMarked: Boolean,
)
internal data class PageBookmarkPlan(val patch: PageBookmarkPatch? = null, val failure: PageBookmarkFailure? = null)
internal data class PageBookmarkNavigation(val request: PreviewEdit.SelectPage? = null, val failure: PageBookmarkFailure? = null)

internal object PageBookmarkRules {
    const val MAX_BOOKMARKS = 1000
    private fun valid(page: PageId) = page.value.isNotBlank() && page.value.length <= 128 && page.value.hasValidUnicode()
    private fun validate(root: DocumentRoot, s: PageBookmarkSnapshot, revision: Long): PageBookmarkFailure? {
        if (root.id != s.documentId) return PageBookmarkFailure.WRONG_DOCUMENT
        if (s.revision < 0 || s.revision != revision) return PageBookmarkFailure.STALE_METADATA
        if (!s.complete) return PageBookmarkFailure.INCOMPLETE_SNAPSHOT
        if (s.pages.size > MAX_BOOKMARKS) return PageBookmarkFailure.CAPACITY
        if (s.pages.toSet().size != s.pages.size || s.pages.any { !valid(it) }) return PageBookmarkFailure.INVALID_BOOKMARKS
        return null
    }

    fun plan(root: DocumentRoot, s: PageBookmarkSnapshot, expectedMetadataRevision: Long,
             page: PageId, marked: Boolean): PageBookmarkPlan {
        validate(root, s, expectedMetadataRevision)?.let { return PageBookmarkPlan(failure = it) }
        if (!valid(page)) return PageBookmarkPlan(failure = PageBookmarkFailure.INVALID_BOOKMARKS)
        val before = page in s.pages
        if (marked && root.findPage(page) == null) return PageBookmarkPlan(failure = PageBookmarkFailure.MISSING_PAGE)
        if (marked && !before && s.pages.size == MAX_BOOKMARKS) return PageBookmarkPlan(failure = PageBookmarkFailure.CAPACITY)
        return PageBookmarkPlan(PageBookmarkPatch(root.id, root.revision, s.revision, page, before, marked))
    }

    fun navigate(root: DocumentRoot, s: PageBookmarkSnapshot, expectedMetadataRevision: Long,
                 page: PageId): PageBookmarkNavigation {
        validate(root, s, expectedMetadataRevision)?.let { return PageBookmarkNavigation(failure = it) }
        if (!valid(page)) return PageBookmarkNavigation(failure = PageBookmarkFailure.INVALID_BOOKMARKS)
        if (page !in s.pages) return PageBookmarkNavigation(failure = PageBookmarkFailure.NOT_BOOKMARKED)
        if (root.findPage(page) == null) return PageBookmarkNavigation(failure = PageBookmarkFailure.MISSING_PAGE)
        return PageBookmarkNavigation(PreviewEdit.SelectPage(page.value))
    }
}
