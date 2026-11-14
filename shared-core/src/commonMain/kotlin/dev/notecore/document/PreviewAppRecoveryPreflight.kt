package dev.notecore.document

/** Validates an immutable target before the App saves or retires its current editor. */
internal fun previewAppRecoveryFailure(snapshot: PagedPreviewSnapshot, budget: DocumentBudget): PreviewOpenFailure? {
    if (PagedPreviewNotebook.exceedsRecoveryInputBudget(snapshot, budget)) {
        return PreviewOpenFailure.INPUT_CAPACITY
    }
    return PagedPreviewNotebook.checkOpenContentAndBudget(snapshot.root, budget)?.failure
}
