package dev.notecore.probe

import dev.notecore.document.DocumentBudget
import dev.notecore.document.PagedPreviewNotebook
import dev.notecore.document.PreviewOpenFailure
import dev.notecore.document.PreviewRecoveryCodec
import java.io.File

fun main(args: Array<String>) {
    val pendingFile = File(args[0])
    val expectedFile = File(args[1])
    val pending = PreviewRecoveryCodec.read(pendingFile.readText()).snapshot ?: error("missing checkpoint")
    val denied = PagedPreviewNotebook.openRecovery(pending, "aar-denied", DocumentBudget(inputBytes = 2247))
    check(denied.failure == PreviewOpenFailure.INPUT_CAPACITY && denied.notebook == null)
    val note = checkNotNull(PagedPreviewNotebook.openRecovery(pending, "aar-allowed",
        DocumentBudget(inputBytes = 2248)).notebook)
    check(note.inputReservedByteCharge == 2248L)
    val samples = checkNotNull(note.pendingInk()).samples
    check(samples.size == 4 && note.strokeCount == 0 && note.changeVersion == 1L)
    check(note.captureRecovery().encode().toByteArray().contentEquals(pendingFile.readBytes()))
    check(note.retryPendingStroke() && note.retryPendingStroke())
    check(note.strokeCount == 1 && note.pointCount(0) == 4 && note.changeVersion == 2L)
    check(note.pendingInk() == null && note.inputReservedByteCharge == 0L)
    check(note.capture().encode().toByteArray().contentEquals(expectedFile.readBytes()))
    println("AAR consumer recovered one stroke / four points / revision 2; both checkpoint byte sequences matched")
}
