import Foundation
import NoteCanvasPrototype

let files = CommandLine.arguments.dropFirst().map { URL(fileURLWithPath: $0) }
let pending = try Data(contentsOf: files[0])
let canonical = try Data(contentsOf: files[1])
let checkpoint = PreviewRecoveryCodec.shared.read(text: String(decoding: pending, as: UTF8.self)).snapshot!

func budget(_ input: Int64) -> DocumentBudget {
    DocumentBudget.companion.validated(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
        journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
        historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: input).budget!
}

let denied = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint, sessionNonce: "framework-denied",
    budget: budget(2247), ownerCheck: {})
precondition(denied.failure == .inputCapacity && denied.notebook == nil)
let note = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint, sessionNonce: "framework-allowed",
    budget: budget(2248), ownerCheck: {}).notebook!
precondition(note.inputReservedByteCharge == 2248 && note.strokeCount == 0 && note.changeVersion == 1)
let samples = note.pendingInk()!.samples
precondition(samples.size == 4)
let retained = try note.captureRecovery().encode()
precondition(Data(retained.utf8) == pending)
precondition(note.retryPendingStroke() && note.retryPendingStroke())
precondition(note.strokeCount == 1 && note.stroke(index: 0).samples === samples && note.changeVersion == 2)
precondition(note.pendingInk() == nil && note.inputReservedByteCharge == 0)
let committed = try note.capture().encode()
precondition(Data(committed.utf8) == canonical)
print("Framework consumer recovered one stroke / four points / revision 2; both checkpoint byte sequences matched")
