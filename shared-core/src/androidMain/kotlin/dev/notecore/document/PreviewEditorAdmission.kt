package dev.notecore.document

/** Android preview host preflight. Checks the immutable checkpoint without creating an owner. */
fun previewEditorAdmissionFailure(snapshot: PagedPreviewSnapshot, budget: DocumentBudget): PreviewOpenFailure? {
    if (PagedPreviewNotebook.exceedsRecoveryInputBudget(snapshot, budget)) return PreviewOpenFailure.INPUT_CAPACITY
    return PagedPreviewNotebook.checkOpenContentAndBudget(snapshot.root, budget)?.failure
}
