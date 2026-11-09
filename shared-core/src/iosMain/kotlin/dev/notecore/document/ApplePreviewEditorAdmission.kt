package dev.notecore.document

/** Apple preview host preflight for an immutable target, before saving or retiring the current owner. */
object ApplePreviewEditorAdmission {
    fun failure(snapshot: PagedPreviewSnapshot, budget: DocumentBudget): PreviewOpenFailure? =
        PagedPreviewNotebook.recoveryAdmissionFailure(snapshot, budget)
}
