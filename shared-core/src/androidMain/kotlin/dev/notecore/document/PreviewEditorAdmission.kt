package dev.notecore.document

/** Android preview host preflight. Checks the immutable checkpoint without creating an owner. */
fun previewEditorAdmissionFailure(snapshot: PagedPreviewSnapshot, budget: DocumentBudget): PreviewOpenFailure? =
    PagedPreviewNotebook.recoveryAdmissionFailure(snapshot, budget)
