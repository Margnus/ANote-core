package dev.notecore.document

import dev.notecore.canvas.prototype.CommandId
import kotlin.math.min

internal sealed interface PageSizePreset {
    data class A4(val landscape: Boolean = false) : PageSizePreset
    /** Same-unit usable editor viewport captured at creation, not physical display pixels or density. */
    data class Screen(val width: Double, val height: Double) : PageSizePreset
}
internal enum class PageTemplateFailure { INVALID_VIEWPORT, SIZE_LIMIT, PAGE_NOT_FOUND }
internal data class PageTemplate(val width: Double, val height: Double, val paper: PaperKind)
internal data class PageTemplateResult(val template: PageTemplate? = null, val failure: PageTemplateFailure? = null)
internal data class PageTemplateCommand(val command: DocumentCommand.SetPaper? = null, val failure: PageTemplateFailure? = null)

internal object PageTemplateRules {
    // Preserve the existing preview A4 coordinate convention; not a new on-disk format or physical DPI.
    const val SHORT_EDGE = 595.0
    const val A4_LONG_EDGE = 842.0

    fun resolve(paper: PaperKind, size: PageSizePreset): PageTemplateResult {
        val dimensions = when (size) {
            is PageSizePreset.A4 -> if (size.landscape) A4_LONG_EDGE to SHORT_EDGE else SHORT_EDGE to A4_LONG_EDGE
            is PageSizePreset.Screen -> {
                if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0 || size.height <= 0) {
                    return PageTemplateResult(failure = PageTemplateFailure.INVALID_VIEWPORT)
                }
                val short = min(size.width, size.height)
                // Divide before multiplying to avoid overflow for a finite but large viewport.
                (size.width / short * SHORT_EDGE) to (size.height / short * SHORT_EDGE)
            }
        }
        val (width, height) = dimensions
        if (!width.isFinite() || !height.isFinite() || width !in 1.0..14_400.0 || height !in 1.0..14_400.0) {
            return PageTemplateResult(failure = PageTemplateFailure.SIZE_LIMIT)
        }
        return PageTemplateResult(PageTemplate(width, height, paper))
    }

    /** Goes through the existing document authority, revision check, history and codec. */
    fun plan(root: DocumentRoot, page: PageId, operation: CommandId, paper: PaperKind,
             size: PageSizePreset): PageTemplateCommand {
        if (root.findPage(page) == null) return PageTemplateCommand(failure = PageTemplateFailure.PAGE_NOT_FOUND)
        val result = resolve(paper, size)
        val template = result.template ?: return PageTemplateCommand(failure = result.failure)
        return PageTemplateCommand(DocumentCommand.SetPaper(operation, root.id, root.revision, page,
            template.width, template.height, template.paper))
    }
}
