package dev.notecore.document

/** Unfrozen raw-parameter facade over the existing notebook, never another content owner. */
sealed interface PreviewEdit {
    data class Rename(val title: String) : PreviewEdit
    data class SetPaper(val width: Double, val height: Double, val paper: PaperKind) : PreviewEdit
    data class SelectPen(val argb: Int, val width: Double) : PreviewEdit
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
}

/** Caller remains on the original owner. Programmer/owner callback failures are not converted. */
class PreviewSessionFailure internal constructor(val kind: PreviewFailureKind, val documentReason: DocumentRejection? = null)

/** Acceptance may be a presentation/tool/no-op action. The root revision is not a durable receipt. */
class PreviewEditResult internal constructor(val accepted: Boolean, val failure: PreviewSessionFailure?, val rootRevision: Long)
