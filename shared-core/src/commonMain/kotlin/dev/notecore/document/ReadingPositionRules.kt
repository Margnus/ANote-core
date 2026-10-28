package dev.notecore.document

import dev.notecore.canvas.preview.PreviewViewport

internal data class ReadingPosition(
    val spaceId: String, val documentId: String, val pageId: String,
    val centerX: Double, val centerY: Double, val zoom: Double,
)
internal enum class ReadingPositionFailure {
    INVALID_RECORD, WRONG_DOCUMENT, MISSING_PAGE, INVALID_LAYOUT, VIEWPORT_MISMATCH, INPUT_OWNED, SESSION_REJECTED,
}
internal data class ReadingPositionResult(val position: ReadingPosition? = null, val failure: ReadingPositionFailure? = null)

internal object ReadingPositionRules {
    private fun validId(id: String) = id.isNotBlank() && id.length <= 128 && id.hasValidUnicode()
    private fun layout(v: PreviewViewport) = v.width in 1.0..1e7 && v.height in 1.0..1e7 && v.scale > 0
    private fun valid(p: ReadingPosition) = validId(p.spaceId) && validId(p.documentId) && validId(p.pageId) &&
        p.centerX.isFinite() && p.centerY.isFinite() && p.centerX in 0.0..14400.0 && p.centerY in 0.0..14400.0 &&
        p.zoom.isFinite() && p.zoom in 1.0..6.0

    fun capture(space: String, note: PagedPreviewNotebook, viewport: PreviewViewport): ReadingPositionResult {
        if (!validId(space)) return ReadingPositionResult(failure = ReadingPositionFailure.INVALID_RECORD)
        if (!layout(viewport)) return ReadingPositionResult(failure = ReadingPositionFailure.INVALID_LAYOUT)
        val page = note.currentPage
        if (viewport.pageWidth != page.width || viewport.pageHeight != page.height) {
            return ReadingPositionResult(failure = ReadingPositionFailure.VIEWPORT_MISMATCH)
        }
        val center = viewport.toScene(viewport.width / 2, viewport.height / 2)
        val record = ReadingPosition(space, note.documentId, page.id.value, center.x, center.y, viewport.zoom)
        return if (valid(record)) ReadingPositionResult(record) else ReadingPositionResult(failure = ReadingPositionFailure.INVALID_RECORD)
    }

    /** Same owner throughout; no IO, content commit, or accepted input cancellation. */
    fun restore(space: String, note: PagedPreviewNotebook, viewport: PreviewViewport,
                position: ReadingPosition): ReadingPositionFailure? {
        if (!validId(space) || !valid(position)) return ReadingPositionFailure.INVALID_RECORD
        if (position.spaceId != space || position.documentId != note.documentId) return ReadingPositionFailure.WRONG_DOCUMENT
        if (!layout(viewport)) return ReadingPositionFailure.INVALID_LAYOUT
        if (note.hasActiveStroke || note.hasPendingStroke) return ReadingPositionFailure.INPUT_OWNED
        val target = note.snapshot().findPage(PageId(position.pageId)) ?: return ReadingPositionFailure.MISSING_PAGE
        if (!note.edit(PreviewEdit.SelectPage(target.id.value)).accepted) return ReadingPositionFailure.SESSION_REJECTED
        viewport.configurePage(target.width, target.height)
        viewport.reset()
        val x = viewport.width / 2; val y = viewport.height / 2
        viewport.gesture(x, y, x, y, position.zoom)
        val saved = viewport.toView(position.centerX.coerceIn(0.0, target.width), position.centerY.coerceIn(0.0, target.height))
        viewport.gesture(saved.x, saved.y, x, y, 1.0)
        return null
    }
}
