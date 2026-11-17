package dev.notecore.document

internal fun previewAppOpenWorkByteCharge(retained: PreviewSaveSnapshot?): Long =
    // Keep the immutable capture charged through worker ACK, even if its save entry clears first.
    // Only the disk path needs the text conversion and sample reconstruction allowance.
    if (retained == null) PagedPreviewCodec.maximumDecodingWorkByteCharge() else retained.retainedByteCharge + 4096L

/** Validates an immutable target before the App saves or retires its current editor. */
internal fun previewAppRecoveryFailure(snapshot: PagedPreviewSnapshot, budget: DocumentBudget): PreviewOpenFailure? {
    if (PagedPreviewNotebook.exceedsRecoveryInputBudget(snapshot, budget)) {
        return PreviewOpenFailure.INPUT_CAPACITY
    }
    return PagedPreviewNotebook.checkOpenContentAndBudget(snapshot.root, budget)?.failure
}
