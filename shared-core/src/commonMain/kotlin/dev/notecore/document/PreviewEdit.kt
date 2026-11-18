package dev.notecore.document

import dev.notecore.canvas.prototype.BrushKind
import dev.notecore.canvas.prototype.PointD

/** Unfrozen raw-parameter facade over the existing notebook, never another content owner. */
sealed interface PreviewEdit {
    data class Rename(val title: String) : PreviewEdit
    data class SetPaper(val width: Double, val height: Double, val paper: PaperKind) : PreviewEdit
    /** Kind defaults to MONOLINE for legacy callers; PRESSURE stays closed at algorithm version 1. */
    data class SelectPen(val argb: Int, val width: Double, val kind: BrushKind = BrushKind.MONOLINE) : PreviewEdit
    /** Whole-stroke eraser gesture on the current page; a miss is an accepted no-op without history. */
    data class EraseStrokes(val path: List<PointD>, val radius: Double) : PreviewEdit
    /** Presentation-only lasso on the current page; an empty hit clears the selection without history. */
    data class LassoSelect(val polygon: List<PointD>) : PreviewEdit
    /** Uniform scale around the anchor, then translation, composed once for the whole selection. */
    data class TransformSelection(val dx: Double, val dy: Double, val scaleFactor: Double = 1.0,
                                  val anchor: PointD = PointD(0.0, 0.0)) : PreviewEdit
    data object DeleteSelection : PreviewEdit
    data class SelectPage(val pageId: String) : PreviewEdit
    data class MovePage(val beforePageId: String?) : PreviewEdit
    data object InsertPage : PreviewEdit
    data object CopyPage : PreviewEdit
    data object DeletePage : PreviewEdit
    data object Undo : PreviewEdit
    data object Redo : PreviewEdit
}

enum class PreviewFailureKind {
    CLOSED, PENDING_STROKE, ACTIVE_STROKE, INVALID_ARGUMENT, MISSING_PAGE, CAPACITY,
    IDENTITY_EXHAUSTED, HISTORY_EMPTY, DOCUMENT_REJECTED, NO_ACTIVE_STROKE, INVALID_INPUT, INPUT_BATCH_CAPACITY, READ_ONLY,
    UNSUPPORTED_BRUSH, EMPTY_SELECTION,
}

/** Caller remains on the original owner. Programmer/owner callback failures are not converted. */
class PreviewSessionFailure internal constructor(val kind: PreviewFailureKind, val documentReason: DocumentRejection? = null)

/** Acceptance may be a presentation/tool/no-op action. The root revision is not a durable receipt. */
class PreviewEditResult internal constructor(val accepted: Boolean, val failure: PreviewSessionFailure?, val rootRevision: Long)
