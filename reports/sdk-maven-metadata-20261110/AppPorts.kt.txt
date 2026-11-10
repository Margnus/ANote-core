package dev.notecore.probe
import dev.notecore.canvas.preview.PreviewSaveLedger
import dev.notecore.document.DocumentBudget
import dev.notecore.document.PagedPreviewSnapshot
import dev.notecore.document.previewEditorAdmissionFailure

fun savePort(ledger: PreviewSaveLedger) = ledger
fun admissionPort(snapshot: PagedPreviewSnapshot, budget: DocumentBudget) =
    previewEditorAdmissionFailure(snapshot, budget)
