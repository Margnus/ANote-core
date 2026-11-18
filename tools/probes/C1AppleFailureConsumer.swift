import Foundation
import NoteCanvasPrototype

let arguments = CommandLine.arguments.dropFirst()
precondition(arguments.count == 2)
let files = arguments.map { URL(fileURLWithPath: $0) }
let pending = try Data(contentsOf: files[0])
let committed = try Data(contentsOf: files[1])
let pendingText = String(decoding: pending, as: UTF8.self)
let committedText = String(decoding: committed, as: UTF8.self)

func validatedBudget(_ inputBytes: Int64) -> DocumentBudgetCreation {
    DocumentBudget.companion.validated(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
        journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
        historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: inputBytes)
}

func expectNSError(_ action: () throws -> Void) -> String {
    do {
        try action()
        preconditionFailure("Expected the exported throws channel")
    } catch {
        let bridged = error as NSError
        precondition(bridged.userInfo["KotlinException"] != nil)
        return bridged.domain
    }
}

let invalidBudget = validatedBudget(-1)
precondition(invalidBudget.failure == .inputBytes && invalidBudget.budget == nil)
let exactBudget = validatedBudget(2248).budget!
let constructorDomain = expectNSError {
    _ = try DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
        journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
        historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: -1)
}
let copyDomain = expectNSError {
    _ = try exactBudget.doCopy(maxPages: exactBudget.maxPages, maxObjects: exactBudget.maxObjects,
        liveBytes: exactBudget.liveBytes, journalEntries: exactBudget.journalEntries,
        journalBytes: exactBudget.journalBytes, identityBytes: exactBudget.identityBytes,
        historyEntries: exactBudget.historyEntries, historyBytes: exactBudget.historyBytes, inputBytes: -1)
}
precondition(exactBudget.inputBytes == 2248)

let unsupported = PreviewDocumentReader.shared.read(
    text: committedText.replacingOccurrences(of: "\tMONOLINE\t", with: "\tHIGHLIGHTER\t"))
precondition(unsupported.failure == .unsupportedPreviewContent && unsupported.root == nil)
let invalidRecovery = PreviewRecoveryCodec.shared.read(text: "unrecognized checkpoint")
precondition(invalidRecovery.failure == .invalidOrUnsupportedFormat && invalidRecovery.snapshot == nil)
let decodeDomain = expectNSError {
    _ = try PreviewRecoveryCodec.shared.decode(text: "unrecognized checkpoint")
}

let checkpoint = PreviewRecoveryCodec.shared.read(text: pendingText).snapshot!
let refused = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint,
    sessionNonce: "failure-capacity", budget: validatedBudget(2247).budget!, ownerCheck: {})
precondition(refused.failure == .inputCapacity && refused.notebook == nil)
let opened = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint,
    sessionNonce: "failure-exact", budget: exactBudget, ownerCheck: {})
precondition(opened.failure == nil)
let notebook = opened.notebook!
let originalRevision = notebook.changeVersion
precondition(notebook.pageAt(index: -1) == nil && notebook.pageAt(index: notebook.pageCount) == nil)
precondition(notebook.strokeAt(index: -1) == nil && notebook.strokeAt(index: 0) == nil)
let edit = notebook.edit(request: PreviewEditSelectPen(argb: -1, width: .nan))
precondition(!edit.accepted && edit.failure?.kind == .invalidArgument && edit.rootRevision == originalRevision)
precondition(notebook.inputReservedByteCharge == 2248 && notebook.pendingInk()?.samples.size == 4)
let preserved = try notebook.captureRecovery().encode()
precondition(Data(preserved.utf8) == pending)
precondition(notebook.retryPendingStroke() && notebook.retryPendingStroke())
precondition(notebook.changeVersion == 2 && notebook.strokeCount == 1 && notebook.inputReservedByteCharge == 0)
let recovered = try notebook.capture().encode()
precondition(Data(recovered.utf8) == committed)

let result: [String: Any] = [
    "pid": ProcessInfo.processInfo.processIdentifier,
    "budget_typed": "INPUT_BYTES",
    "budget_constructor_nserror": constructorDomain,
    "budget_copy_nserror": copyDomain,
    "unsupported_document_typed": "UNSUPPORTED_PREVIEW_CONTENT",
    "malformed_recovery_typed": "INVALID_OR_UNSUPPORTED_FORMAT",
    "malformed_recovery_nserror": decodeDomain,
    "recovery_refused_bytes": 2247,
    "recovery_admitted_bytes": 2248,
    "missing_index_is_nil": true,
    "invalid_edit_preserved_pending": true,
    "retry_preserved_checkpoint_bytes": true,
]
let receipt = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
print(String(decoding: receipt, as: UTF8.self))
