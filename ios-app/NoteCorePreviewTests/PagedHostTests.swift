import XCTest
import UIKit
import SwiftUI
import NoteCanvasPrototype
@testable import NoteCorePreview

/// Runs in the real iOS app host. API/file/render tests, not UI taps or real Pencil evidence.
final class PagedHostTests: XCTestCase {
    @MainActor func testCapturedStrokeTraversalRemainsImmutableAfterPageUndoAndRetirement() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.beginStroke(x: 10, y: 20, pressure: 0.25, timeMicros: 1))
        XCTAssertTrue(note.endStroke(x: 30, y: 40, pressure: 0.75, timeMicros: 2))
        let page = note.currentPageId, stroke = note.stroke(index: 0), samples = stroke.samples
        let snapshot = note.capture(), encoded = try snapshot.encode()
        XCTAssertTrue(note.insertPage()); XCTAssertEqual(note.strokeCount, 0)
        XCTAssertTrue(note.selectPage(id: page)); XCTAssertTrue(note.stroke(index: 0) === stroke)
        XCTAssertTrue(note.undo()); XCTAssertEqual(note.strokeCount, 1)
        XCTAssertTrue(note.undo()); XCTAssertEqual(note.strokeCount, 0)
        model.close(); XCTAssertEqual(model.beginOwnerRetirement(), .started)
        try await eventually("read traversal owner retires through original writes") { model.ownerRetirementStatus.phase == .retired }
        XCTAssertEqual(try snapshot.encode(), encoded)
        var points = [(Double, Double, Float, Int64)]()
        for index in 0..<samples.size {
            points.append((samples.x(index: index), samples.y(index: index), samples.pressure(index: index), samples.timeMicros(index: index)))
        }
        XCTAssertEqual(points.count, 2); XCTAssertEqual(points[0].0, 10); XCTAssertEqual(points[1].1, 40)
        XCTAssertEqual(points[0].2, 0.25); XCTAssertEqual(points[1].3, 2)
    }
    @MainActor func testRawBudgetFailuresDoNotTrapAndPreviewStillChecksPolicy() async throws {
        func validate(pages: Int32 = 128, objects: Int32 = 5000, live: Int64 = 8*1024*1024,
                      journal: Int32 = 128, journalBytes: Int64 = 8*1024*1024, identity: Int64 = 2*1024*1024,
                      history: Int32 = 128, historyBytes: Int64 = 8*1024*1024, input: Int64 = 2*1024*1024) -> DocumentBudgetCreation {
            DocumentBudget.companion.validated(maxPages: pages, maxObjects: objects, liveBytes: live,
                journalEntries: journal, journalBytes: journalBytes, identityBytes: identity,
                historyEntries: history, historyBytes: historyBytes, inputBytes: input)
        }
        let failures: [(DocumentBudgetCreation, DocumentBudgetFailure)] = [
            (validate(pages: 0), .maxPages), (validate(pages: 4097), .maxPages),
            (validate(objects: Int32.max), .maxObjects), (validate(live: Int64.min), .liveBytes),
            (validate(journal: 0), .journalEntries), (validate(journalBytes: -1), .journalBytes),
            (validate(identity: -1), .identityBytes), (validate(history: 16385), .historyEntries),
            (validate(historyBytes: -1), .historyBytes), (validate(input: -1), .inputBytes)]
        for (result, reason) in failures { XCTAssertNil(result.budget); XCTAssertEqual(result.failure, reason) }
        let zero = try XCTUnwrap(validate(live: 0, journalBytes: 0, identity: 0, historyBytes: 0, input: 0).budget)
        XCTAssertEqual(zero.inputBytes, 0); XCTAssertEqual(zero.liveBytes, 0)
        let huge = try XCTUnwrap(validate(live: Int64.max, input: Int64.max).budget)
        XCTAssertEqual(huge.liveBytes, Int64.max); XCTAssertEqual(huge.inputBytes, Int64.max)
        let unsupported = PagedPreviewNotebook.companion.createValidated(documentId: "budget", title: "budget", sessionNonce: "budget",
            width: 595, height: 842, paper: .blank, budget: huge, ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(unsupported.notebook); XCTAssertEqual(unsupported.failure, .unsupportedBudget)
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        XCTAssertNotNil(model.current); model.close()
        XCTAssertEqual(model.beginOwnerRetirement(), .started)
        try await eventually("budget consumer drains original writes and owner") { model.ownerRetirementStatus.phase == .retired }
    }
    @MainActor func testWindowDeparturePreservesAuthorityAndBoundsPresentationIdentities() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current), id = note.documentId
        let a = PreviewWindowState(model: model), b = PreviewWindowState(model: model)
        a.appear(); b.appear()
        XCTAssertTrue(a.current === note && b.current === note); XCTAssertEqual(model.editorCount, 2)
        XCTAssertEqual(model.beginOwnerRetirement(), .windowPending)
        model.close(); XCTAssertTrue(model.current === note)
        XCTAssertTrue(note.beginStroke(x: 1, y: 2, pressure: 0.5, timeMicros: 1))
        let input = note.activeInputOperationId
        a.cancelInput(); a.prepareExport()
        XCTAssertEqual(note.activeInputOperationId, input); XCTAssertTrue(note.hasActiveStroke)
        XCTAssertFalse(model.transferBusy); XCTAssertNil(model.exportDocument)
        XCTAssertEqual(a.leave(), .detached); XCTAssertTrue(note.hasActiveStroke)
        XCTAssertNil(a.current); XCTAssertTrue(b.current === note); XCTAssertEqual(a.leave(), .stale)
        a.create(); XCTAssertNil(a.current); XCTAssertTrue(model.current === note)
        a.open(id); XCTAssertTrue(a.current === note); XCTAssertEqual(model.editorCount, 2)
        XCTAssertEqual(a.leave(), .detached)
        XCTAssertTrue(note.endStroke(x: 3, y: 4, pressure: 0.5, timeMicros: 2)); model.changed()
        let expected = try note.capture().encode()
        XCTAssertEqual(b.leave(), .documentClosed); XCTAssertNil(model.current)
        try await eventually("last window writes through original worker") { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent(id + ".ncp"), encoding: .utf8), expected)
        a.open(id); try await eventually("requesting library window rejoins") { a.current != nil }
        let reopened = try XCTUnwrap(a.current)
        var inputView: InkUIView? = InkUIView(note: reopened)
        inputView?.inputWindow = a; a.bindInput(try XCTUnwrap(inputView))
        inputView?.frame = CGRect(x: 0, y: 0, width: 300, height: 400); inputView?.layoutIfNeeded()
        inputView?.touchesBegan([SyntheticInkTouch(80, 3)], with: nil)
        XCTAssertTrue(reopened.hasActiveStroke)
        weak var goneInput = inputView
        inputView = nil; XCTAssertNil(goneInput)
        a.cancelInput() // Explicit cancellation uses its original operation, never ARC as a receipt.
        XCTAssertFalse(reopened.hasActiveStroke); XCTAssertEqual(reopened.inputReservedByteCharge, 0)
        XCTAssertEqual(reopened.totalStrokeCount, 1)
        var windows = [a]
        for _ in 0..<7 { let next = PreviewWindowState(model: model); next.appear(); windows.append(next) }
        XCTAssertEqual(model.editorCount, 8)
        a.appear(); XCTAssertEqual(model.editorCount, 8)
        let refused = PreviewWindowState(model: model); refused.appear()
        XCTAssertNil(refused.current); XCTAssertEqual(model.editorCount, 8)
        for window in windows { window.disappear() }
        XCTAssertEqual(model.editorCount, 0); XCTAssertTrue(model.current === reopened)
        b.appear() // B explicitly returned earlier, so appearance must stay in its library.
        XCTAssertNil(b.current); XCTAssertEqual(model.editorCount, 0)
        XCTAssertNotNil(model.createForEditor()); XCTAssertFalse(model.current === reopened)
        a.disappear(); b.disappear(); refused.disappear()
        XCTAssertEqual(model.beginOwnerRetirement(), .started)
        try await eventually("window service retires") { model.ownerRetirementStatus.phase == .retired }
    }
    @MainActor func testWindowPendingRecoveryRequiresOriginalWriteBeforeLastDeparture() async throws {
        let dir = directory(); try Data("blocked".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        let checkpoint = try windowCheckpoint()
        let limited = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8*1024*1024,
            journalEntries: 4096, journalBytes: 32*1024*1024, identityBytes: 4*1024*1024,
            historyEntries: 128, historyBytes: 0, inputBytes: 2*1024*1024)
        let model = PreviewModel(directory: dir, documentBudget: limited)
        try model.adopt(checkpoint.root, persisted: false, recovery: checkpoint)
        let note = try XCTUnwrap(model.current)
        let a = PreviewWindowState(model: model), b = PreviewWindowState(model: model)
        a.appear(); b.appear()
        XCTAssertEqual(a.leave(), .detached); XCTAssertEqual(b.leave(), .retryRequired)
        XCTAssertTrue(note.hasPendingStroke); XCTAssertTrue(b.current === note); XCTAssertEqual(model.editorCount, 1)
        XCTAssertTrue(note.captureRecovery().sameContent(other: checkpoint))
        try await eventually("failed pending remains in original ledger") { model.ownerRetirementStatus.retryRequired == 1 }
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertTrue(model.retryRetainedWrites())
        try await eventually("original pending write confirms") { model.ownerRetirementStatus.pendingWrites == 0 }
        let file = dir.appendingPathComponent(checkpoint.documentId + ".ncp")
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), try checkpoint.encode())
        XCTAssertEqual(b.leave(), .documentClosed); XCTAssertNil(model.current)
        let restored = PreviewModel(directory: dir), window = PreviewWindowState(model: restored)
        window.appear(); window.open(checkpoint.documentId)
        try await eventually("new window session reads full checkpoint") { window.current != nil }
        let resumed = try XCTUnwrap(window.current)
        XCTAssertTrue(resumed.hasPendingStroke); XCTAssertTrue(resumed.captureRecovery().sameContent(other: checkpoint))
        XCTAssertTrue(resumed.retryPendingStroke()); XCTAssertTrue(resumed.retryPendingStroke())
        XCTAssertEqual(resumed.totalStrokeCount, 1); XCTAssertEqual(resumed.pointCount(index: 0), 3)
        XCTAssertEqual(window.leave(), .documentClosed)
        try await eventually("committed replacement writes") { restored.ownerRetirementStatus.pendingWrites == 0 }
        let canonical = try PreviewRecoveryCodec.shared.decode(text: String(contentsOf: file, encoding: .utf8))
        XCTAssertNil(canonical.pending); XCTAssertEqual(canonical.root.scene.objectCount, 1)
        a.disappear(); b.disappear(); window.disappear()
        for owner in [model, restored] {
            XCTAssertEqual(owner.beginOwnerRetirement(), .started)
            try await eventually("pending window owner retires") { owner.ownerRetirementStatus.phase == .retired }
        }
    }
    @MainActor func testHostingWindowsKeepLocalNavigationThroughHierarchyReplacement() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current)
        let a = PreviewWindowState(model: model), b = PreviewWindowState(model: model)
        let wa = UIWindow(frame: CGRect(x: 0, y: 0, width: 600, height: 800))
        let wb = UIWindow(frame: CGRect(x: 0, y: 0, width: 600, height: 800))
        wa.rootViewController = UIHostingController(rootView: PreviewRoot(model: model, window: a))
        wb.rootViewController = UIHostingController(rootView: PreviewRoot(model: model, window: b))
        wa.makeKeyAndVisible(); wb.makeKeyAndVisible()
        defer { wa.isHidden = true; wb.isHidden = true; wa.rootViewController = nil; wb.rootViewController = nil }
        try await eventually("both actual hosting roots appear") { model.editorCount == 2 }
        XCTAssertTrue(a.current === note && b.current === note)
        XCTAssertEqual(a.leave(), .detached)
        try await eventually("one window stays in library") { a.current == nil && b.current === note && model.editorCount == 1 }
        draw(note); model.changed(); let root = note.snapshot()
        wb.isHidden = true; wb.rootViewController = nil
        try await eventually("removed hosting root forgets presentation only") { model.editorCount == 0 }
        XCTAssertTrue(model.current === note); XCTAssertTrue(note.snapshot() === root)
        wb.rootViewController = UIHostingController(rootView: PreviewRoot(model: model, window: b))
        wb.makeKeyAndVisible()
        try await eventually("replacement hosting root resumes same session") { b.current === note && model.editorCount == 1 }
        XCTAssertNil(a.current); XCTAssertTrue(note.snapshot() === root)
        XCTAssertEqual(b.leave(), .documentClosed)
        try await eventually("hosting window command saves") { model.ownerRetirementStatus.pendingWrites == 0 }
        a.disappear(); b.disappear()
        XCTAssertEqual(model.beginOwnerRetirement(), .started)
        try await eventually("hosting owner stops") { model.ownerRetirementStatus.phase == .retired }
    }
    @MainActor func testExportResultRoutesOnlyToRequestWindowAndCancelsOnDisappearance() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current); draw(note); model.changed()
        let root = note.snapshot(), expected = try note.capture().encode()
        let a = PreviewWindowState(model: model), b = PreviewWindowState(model: model)
        a.appear(); b.appear(); a.prepareExport()
        try await eventually("request window receives backup") { model.exportDocument != nil }
        XCTAssertTrue(a.ownsExport); XCTAssertFalse(b.ownsExport)
        let backup = try XCTUnwrap(model.exportDocument)
        b.finishExport(); XCTAssertEqual(model.exportDocument?.data, backup.data)
        XCTAssertEqual(a.leave(), .detached)
        XCTAssertEqual(b.leave(), .retryRequired)
        a.disappear() // Explicitly cancels this window's derived result, not content.
        XCTAssertNil(model.exportDocument); XCTAssertEqual(model.exportRetainedByteCharge, 0)
        XCTAssertTrue(model.current === note); XCTAssertTrue(note.snapshot() === root)
        b.prepareExport(); let cancelledRequest = try XCTUnwrap(b.exportRequest)
        b.disappear() // Cancel while accepted compute is still pending.
        try await eventually("cancelled export ACKs without publishing orphan data") { !model.transferBusy }
        XCTAssertNil(model.exportDocument); XCTAssertNil(model.exportEditor)
        XCTAssertEqual(try note.capture().encode(), expected)
        b.appear(); b.prepareExport()
        try await eventually("same surviving window can export again") { model.exportDocument != nil }
        XCTAssertTrue(b.ownsExport); XCTAssertFalse(a.ownsExport)
        XCTAssertNotEqual(b.exportRequest, cancelledRequest)
        b.finishExport(request: cancelledRequest) // A late callback cannot release a newer export.
        XCTAssertNotNil(model.exportDocument)
        XCTAssertEqual(String(data: model.exportDocument!.data, encoding: .utf8), expected)
        b.finishExport(); XCTAssertEqual(b.leave(), .documentClosed); b.disappear()
        XCTAssertEqual(model.beginOwnerRetirement(), .started)
        try await eventually("export owner stops") { model.ownerRetirementStatus.phase == .retired }
    }
    @MainActor func testLateWindowOpenCannotReplaceEditorOrAttachAfterDisappearance() async throws {
        let dir = directory(); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let base = "NCPREVIEW\t2\nD\tlate\tlate\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        try PreviewFileIO.writeUTF8(base, to: dir.appendingPathComponent("late.ncp"))
        try Data("broken".utf8).write(to: dir.appendingPathComponent("bad.ncp"))
        let started = DispatchSemaphore(value: 0), gate = DispatchSemaphore(value: 0)
        defer { gate.signal() }
        var calls = 0
        let model = PreviewModel(directory: dir, beforeOpenRead: {
            calls += 1
            if calls == 1 { started.signal(); if gate.wait(timeout: .now()+8) != .success { throw CocoaError(.fileReadUnknown) } }
        })
        let a = PreviewWindowState(model: model); a.appear(); a.open("late")
        var observedStart = false
        try await eventually("actual read starts") {
            if !observedStart { observedStart = started.wait(timeout: .now()) == .success }
            return observedStart
        }
        a.disappear()
        let b = PreviewWindowState(model: model); b.appear(); b.create()
        let note = try XCTUnwrap(b.current), root = note.snapshot()
        gate.signal()
        try await eventually("later original write completes after read ACK") { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertNil(a.current); XCTAssertTrue(b.current === note); XCTAssertTrue(note.snapshot() === root)
        a.appear(); a.open("late"); XCTAssertNil(a.current); XCTAssertTrue(b.current === note)
        b.disappear()
        a.open("bad")
        try await eventually("invalid target read preserves idle original owner") { model.status == LocalReadFailure.invalidFormat.message }
        XCTAssertTrue(model.current === note); XCTAssertTrue(note.snapshot() === root); XCTAssertNil(a.current)
        a.open("late")
        try await eventually("valid target switches idle owner and joins requester") { a.current?.documentId == "late" }
        XCTAssertEqual(model.editorCount, 1); XCTAssertNil(b.current)
        XCTAssertEqual(a.leave(), .documentClosed); a.disappear()
        XCTAssertEqual(model.beginOwnerRetirement(), .started)
        try await eventually("late-read owner stops") { model.ownerRetirementStatus.phase == .retired }
    }
    private func windowCheckpoint() throws -> PagedPreviewSnapshot {
        let base = "NCPREVIEW\t2\nD\tpending-window\tpending\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        let ink = "NCPREVIEW\t2\nD\tpending-window\t\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nS\treused-ink-2\tlayer\t1\tMONOLINE\t1\t-14339513\t2.5\t3\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.25\t2.5\t0.125\t7\nP\t3.0\t4.0\t0.625\t9\nP\t8.0\t6.0\t1.0\t10\nEND\n"
        let operation = "reused-op-1".utf16.map { String(format: "%04x", $0) }.joined()
        return try PreviewRecoveryCodec.shared.decode(text: "NCRECOVERY\t1\nD\tpending-window\tpending\t1\t0\t0\n\(base.utf16.count)\t\(ink.utf16.count)\t\(operation)\n" + base + ink + "RECOVERY-END\n")
    }
    @MainActor func testPendingRecoveryOriginalModelFailureRetryNewOwnerAndCanonicalReopen() async throws {
        let dir = directory(), moved = directory()
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: moved) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let base = "NCPREVIEW\t2\nD\tpending-swift\tpending\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        let ink = "NCPREVIEW\t2\nD\tpending-swift\t\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nS\treused-ink-2\tlayer\t1\tMONOLINE\t1\t-14339513\t2.5\t3\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.25\t2.5\t0.125\t7\nP\t3.0\t4.0\t0.625\t9\nP\t8.0\t6.0\t1.0\t10\nEND\n"
        let operation = "reused-op-1".utf16.map { String(format: "%04x", $0) }.joined()
        let encoded = "NCRECOVERY\t1\nD\tpending-swift\tpending\t1\t0\t0\n\(base.utf16.count)\t\(ink.utf16.count)\t\(operation)\n" + base + ink + "RECOVERY-END\n"
        let checkpoint = try PreviewRecoveryCodec.shared.decode(text: encoded)
        let reading = PreviewRecoveryCodec.shared.read(text: encoded)
        XCTAssertNil(reading.failure); XCTAssertTrue(reading.snapshot!.sameContent(other: checkpoint))
        XCTAssertNotNil(reading.snapshot?.pending)
        // Exact original command metadata plus three real samples: 2048 + 2*(11+4+12+5) + 3*28.
        let inputCharge: Int64 = 2196
        for bytes in [Int64(0), inputCharge - 1, inputCharge] {
            let inputBudget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8*1024*1024,
                journalEntries: 128, journalBytes: 8*1024*1024, identityBytes: 2*1024*1024,
                historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: bytes)
            let result = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint, sessionNonce: "consumer",
                budget: inputBudget, ownerCheck: { precondition(Thread.isMainThread) })
            if bytes < inputCharge {
                XCTAssertNil(result.notebook); XCTAssertEqual(result.failure, .inputCapacity)
            } else {
                let note = try XCTUnwrap(result.notebook)
                XCTAssertEqual(note.inputReservedByteCharge, inputCharge)
                XCTAssertTrue(note.pendingStroke()!.isEqual(checkpoint.pending))
                XCTAssertTrue(note.retryPendingStroke()); XCTAssertTrue(note.retryPendingStroke())
                XCTAssertEqual(note.totalStrokeCount, 1); XCTAssertEqual(note.inputReservedByteCharge, 0)
            }
            XCTAssertEqual(try checkpoint.encode(), encoded)
        }
        let file = dir.appendingPathComponent("pending-swift.ncp")
        try PreviewFileIO.writeUTF8(encoded, to: file)
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
            historyEntries: 128, historyBytes: 0, inputBytes: 2 * 1024 * 1024)
        try FileManager.default.removeItem(at: dir); try Data("blocked".utf8).write(to: dir)
        let model = PreviewModel(directory: dir, documentBudget: budget)
        // Same adoption boundary used by open, with a validated but not-yet-persisted checkpoint.
        try model.adopt(checkpoint.root, persisted: false, recovery: checkpoint)
        let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.captureRecovery().sameContent(other: checkpoint)); XCTAssertEqual(note.totalStrokeCount, 0)
        XCTAssertTrue(model.save()); model.close(); XCTAssertTrue(model.current === note)
        try await eventually("pending checkpoint failed IO retains full content") { model.ownerRetirementStatus.retryRequired == 1 }
        XCTAssertTrue(note.hasPendingStroke); XCTAssertTrue(note.captureRecovery().sameContent(other: checkpoint))
        try FileManager.default.removeItem(at: dir); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertTrue(model.retryRetainedWrites())
        try await eventually("pending checkpoint writes through original worker") { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertEqual(try PreviewFileIO.readUTF8(file), try checkpoint.encode())
        model.close(); XCTAssertNil(model.current); XCTAssertFalse(note.retryPendingStroke())
        let reopened = PreviewModel(directory: dir); reopened.open("pending-swift")
        try await eventually("fresh default owner retains pending without automatic commit") { reopened.current?.hasPendingStroke == true }
        let restored = try XCTUnwrap(reopened.current)
        XCTAssertTrue(restored.pendingStroke()!.isEqual(checkpoint.pending)); XCTAssertTrue(restored.retryPendingStroke())
        XCTAssertTrue(restored.retryPendingStroke()); XCTAssertEqual(restored.totalStrokeCount, 1); XCTAssertEqual(restored.changeVersion, 1)
        let held = directory()
        defer { try? FileManager.default.removeItem(at: held) }
        try FileManager.default.moveItem(at: dir, to: held); try Data("blocked".utf8).write(to: dir)
        XCTAssertTrue(reopened.save())
        try await eventually("actual failed IO remains in original ledger") { reopened.ownerRetirementStatus.retryRequired == 1 }
        XCTAssertEqual(try PreviewFileIO.readUTF8(held.appendingPathComponent("pending-swift.ncp")), try checkpoint.encode())
        XCTAssertTrue(restored.totalStrokeCount == 1 && !restored.hasPendingStroke)
        try FileManager.default.removeItem(at: dir); try FileManager.default.moveItem(at: held, to: dir)
        XCTAssertTrue(reopened.retryRetainedWrites())
        try await eventually("repair writes canonical replacement") { reopened.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertTrue(try PreviewFileIO.readUTF8(file).hasPrefix("NCPREVIEW\t2\n"))
        reopened.close(); XCTAssertNil(reopened.current)
        let final = PreviewModel(directory: dir); final.open("pending-swift")
        try await eventually("normal new owner reopens one committed stroke") { final.current?.totalStrokeCount == 1 }
        XCTAssertFalse(final.current!.hasPendingStroke)
        final.close()
        let signedOperation = encoded.replacingOccurrences(of: "\t\(operation)\n", with: "\t-001\(operation.dropFirst(4))\n")
        let signedLength = encoded.replacingOccurrences(of: "\n\(base.utf16.count)\t", with: "\n+\(base.utf16.count)\t")
        let refused = PreviewModel(directory: dir)
        let refusedInputs: [(String, PreviewReadFailure)] = [
            (String(encoded.dropLast()), .invalidOrUnsupportedFormat), (signedOperation, .invalidOrUnsupportedFormat),
            (signedLength, .invalidOrUnsupportedFormat), (encoded.replacingOccurrences(of: "MONOLINE", with: "PRESSURE"), .unsupportedPreviewContent)]
        for (corrupt, reason) in refusedInputs {
            let reading = PreviewRecoveryCodec.shared.read(text: corrupt)
            XCTAssertNil(reading.snapshot); XCTAssertEqual(reading.failure, reason)
            XCTAssertThrowsError(try PreviewRecoveryCodec.shared.decode(text: corrupt))
            try PreviewFileIO.writeUTF8(corrupt, to: file)
            refused.open("pending-swift")
            try await eventually("malformed recovery refuses entire opening") { refused.status == (reason == .unsupportedPreviewContent ? LocalReadFailure.unsupportedContent.message : LocalReadFailure.invalidFormat.message) }
            XCTAssertNil(refused.current); XCTAssertEqual(try PreviewFileIO.readUTF8(file), corrupt)
        }
    }
    @MainActor func testKnownReadRefusalsReachOwnerSeparatelyFromIOAndUnknown() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let id = "read-failures", file = dir.appendingPathComponent("read-failures.ncp")
        let valid = "NCPREVIEW\t2\nD\tread-failures\ttitle\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        let snapshot = try XCTUnwrap(PreviewRecoveryCodec.shared.read(text: valid).snapshot)
        if case .opened(let same) = PreviewFileIO.acceptRecovery(snapshot, documentId: id) { XCTAssertTrue(same === snapshot) }
        else { XCTFail("Retained snapshot rejected") }
        let unsupported = valid.replacingOccurrences(of: "\t0\t0\nG", with: "\t1\t0\nG").replacingOccurrences(of: "END\n", with:
            "S\tink\tlayer\t1\tPRESSURE\t1\t-1\t2.5\t1\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.0\t2.0\t0.5\t1\nEND\n")
        let unsupportedSnapshot = try PagedPreviewSnapshot.companion.fromRoot(root: PagedPreviewCodec.shared.decode(text: unsupported))
        if case .refused(let reason) = PreviewFileIO.acceptRecovery(unsupportedSnapshot, documentId: id) { XCTAssertEqual(reason.message, LocalReadFailure.unsupportedContent.message) }
        else { XCTFail("Unsupported retained content accepted") }
        let model = PreviewModel(directory: dir)
        for (bytes, reason) in [(Data("bad format".utf8), LocalReadFailure.invalidFormat),
            (Data(valid.replacingOccurrences(of: id, with: "other-note").utf8), .wrongDocument),
            (Data([0xc3, 0x28]), .invalidUTF8), (Data(repeating: 120, count: Int(PagedPreviewCodec.shared.maximumCharacterCount()) + 1), .characterCapacity)] {
            try bytes.write(to: file); model.open(id)
            try await eventually("known read refusal reaches owner") { model.status == reason.message }
            XCTAssertNil(model.current); XCTAssertEqual(try Data(contentsOf: file), bytes)
        }
        let handle = try FileHandle(forWritingTo: file)
        try handle.truncate(atOffset: UInt64(PreviewFileIO.maxReadBytes + 1)); try handle.close()
        let large = try Data(contentsOf: file)
        model.open(id); try await eventually("byte capacity reaches owner") { model.status == LocalReadFailure.byteCapacity.message }
        XCTAssertNil(model.current); XCTAssertEqual(try Data(contentsOf: file), large)
        try FileManager.default.removeItem(at: file)
        model.open(id); try await eventually("known file IO reaches owner") { model.status == LocalReadFailure.ioFailure.message }
        XCTAssertNil(model.current)
        // A system Cocoa corruption error is IO, not proof that our UTF-8 decoding failed.
        try PreviewFileIO.writeUTF8(valid, to: file)
        let systemIO = PreviewModel(directory: dir, beforeOpenRead: { throw CocoaError(.fileReadCorruptFile) })
        systemIO.open(id); try await eventually("system IO is not mislabeled as UTF8") { systemIO.status == LocalReadFailure.ioFailure.message }
        XCTAssertNil(systemIO.current); XCTAssertEqual(try PreviewFileIO.readUTF8(file), valid)
        enum Probe: Error { case injected }
        let unknown = PreviewModel(directory: dir, beforeOpenRead: { throw Probe.injected })
        try PreviewFileIO.writeUTF8(valid, to: file)
        unknown.open(id); try await eventually("unrecognized error remains unknown") { unknown.status == "读取结果暂无法确认，请稍后重试" }
        XCTAssertNil(unknown.current); XCTAssertEqual(try PreviewFileIO.readUTF8(file), valid)
        model.open(id); try await eventually("later valid read recovers") { model.current != nil }
        XCTAssertEqual(model.current?.documentId, id); model.close()
    }
    @MainActor func testValidatedCreationRejectsRawParametersAndModelUsesFactoryNotebook() async throws {
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 128, journalBytes: 8 * 1024 * 1024, identityBytes: 2 * 1024 * 1024,
            historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: 2 * 1024 * 1024)
        let invalid = PagedPreviewNotebook.companion.createValidated(documentId: "invalid", title: "title", sessionNonce: "swift",
            width: .nan, height: 842, paper: .blank, budget: budget, ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(invalid.notebook); XCTAssertEqual(invalid.failure, .invalidPage)
        let badId = PagedPreviewNotebook.companion.createValidated(documentId: String(repeating: "a", count: 129), title: "title", sessionNonce: "swift",
            width: 595, height: 842, paper: .blank, budget: budget, ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(badId.notebook); XCTAssertEqual(badId.failure, .invalidDocumentId)
        let longTitle = PagedPreviewNotebook.companion.createValidated(documentId: "valid", title: String(repeating: "a", count: 121), sessionNonce: "swift",
            width: 595, height: 842, paper: .blank, budget: budget, ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(longTitle.notebook); XCTAssertEqual(longTitle.failure, .invalidTitle)
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir)
        model.create(); let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.currentPageId.value.hasSuffix("-page-0"))
        draw(note); model.changed(); let expected = try note.capture().encode()
        model.close(); try await eventually("created notebook persisted before reopen") { model.current == nil && model.ownerRetirementStatus.pendingWrites == 0 }
        model.open(note.documentId)
        try await eventually("factory notebook reopens through original model") { model.current?.totalStrokeCount == 1 }
        XCTAssertEqual(try model.current!.capture().encode(), expected); model.close()
    }
    @MainActor func testValidatedOpeningReturnsTypedFailureAndUsesOriginalNotebook() throws {
        let root = DocumentRoot.companion.empty(id: SceneId(value: "validated-swift"), title: "unchanged",
            firstPage: PageDescriptor(id: PageId(value: "page"), layerId: LayerId(value: "layer"),
                width: 595, height: 842, paper: .blank, contentRevision: 0))
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 128, journalBytes: 8 * 1024 * 1024, identityBytes: 2 * 1024 * 1024,
            historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: 2 * 1024 * 1024)
        let refused = PagedPreviewNotebook.companion.open(initial: root, sessionNonce: "bad nonce", budget: budget,
            ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(refused.notebook); XCTAssertEqual(refused.failure, .invalidSessionNonce)
        let opening = PagedPreviewNotebook.companion.open(initial: root, sessionNonce: "swift-valid", budget: budget,
            ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(opening.failure)
        let note = try XCTUnwrap(opening.notebook)
        XCTAssertTrue(note.snapshot() === root)
        draw(note); XCTAssertEqual(note.totalStrokeCount, 1)
        XCTAssertEqual(root.scene.objectCount, 0); XCTAssertTrue(note.retire())
    }
    @MainActor func testFullSaveLedgerRejectsRetirementUntilRetainedWritesAreExplicitlyRetried() async throws {
        let dir = directory(); try Data("blocked".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir)
        var accepted: [String: String] = [:]
        for _ in 0..<8 {
            model.create(); let note = try XCTUnwrap(model.current)
            accepted[note.documentId] = try note.capture().encode()
            model.close(); XCTAssertNil(model.current)
        }
        model.create(); let blocked = try XCTUnwrap(model.current)
        accepted[blocked.documentId] = try blocked.capture().encode()
        XCTAssertEqual(model.beginOwnerRetirement(), .saveRejected)
        XCTAssertTrue(model.current === blocked); XCTAssertEqual(model.ownerRetirementStatus.phase, .active)
        XCTAssertEqual(model.ownerRetirementStatus.pendingWrites, 8)
        try await eventually("all accepted failed snapshots remain retryable") { model.ownerRetirementStatus.retryRequired == 8 }
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertTrue(model.retryRetainedWrites())
        try await eventually("explicit retry frees old document slots") { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertTrue(model.current === blocked)
        var finished = false
        XCTAssertEqual(model.beginOwnerRetirement { state in
            XCTAssertTrue(state.workerStopped); XCTAssertEqual(state.pendingWrites, 0); finished = true
        }, .started)
        try await eventually("ninth document saves before owner retirement") { finished }
        for (id, expected) in accepted {
            XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent(id + ".ncp"), encoding: .utf8), expected)
        }
    }
    @MainActor func testOwnerRetirementWritesAcceptedRootThenStopsAndReleasesOwner() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        var model: PreviewModel? = PreviewModel(directory: dir)
        model?.create()
        let note = try XCTUnwrap(model?.current); draw(note); model?.changed()
        let expected = try note.capture().encode(), file = dir.appendingPathComponent(note.documentId + ".ncp")
        weak var observer = model
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertEqual(status.phase, .retired); XCTAssertTrue(status.workerStopped)
            XCTAssertEqual(status.pendingWrites, 0); XCTAssertEqual(status.renderBytes, 0)
            finished = true
        }, .started)
        XCTAssertNil(model?.current)
        model?.create(); model?.open(note.documentId); model?.prepareExport()
        XCTAssertNil(model?.current); XCTAssertFalse(model?.transferBusy ?? true)
        XCTAssertEqual(model?.beginOwnerRetirement(), .alreadyRetiring)
        model = nil
        try await eventually("owner retirement completed") { finished && observer == nil }
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), expected)
    }
    @MainActor func testOwnerReferenceDropKeepsFailedWriteUntilOriginalRetirement() async throws {
        let dir = directory(); try Data("blocked".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        var model: PreviewModel? = PreviewModel(directory: dir); model?.create()
        let note = try XCTUnwrap(model?.current); draw(note); model?.changed()
        let identity = ObjectIdentifier(try XCTUnwrap(model))
        let expected = try note.capture().encode(), file = dir.appendingPathComponent(note.documentId + ".ncp")
        weak var observer = model
        model = nil
        try await eventually("original owner retains failed accepted write before retirement") {
            observer?.ownerRetirementStatus.retryRequired == 1
        }
        model = try XCTUnwrap(observer)
        XCTAssertEqual(ObjectIdentifier(model!), identity); XCTAssertTrue(model?.current === note)
        XCTAssertEqual(model?.ownerRetirementStatus.phase, .active)
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertEqual(model?.retryRetainedWrites(), true)
        try await eventually("original owner writes repaired file") { model?.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), expected)
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertTrue(status.workerStopped); XCTAssertEqual(status.pendingWrites, 0); finished = true
        }, .started)
        model = nil
        try await eventually("explicit retirement releases original owner") { finished && observer == nil }
    }
    @MainActor func testOwnerRetirementRetainsFailedWritesUntilExplicitRepairAndRetry() async throws {
        let dir = directory(); try Data("blocked".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        var model: PreviewModel? = PreviewModel(directory: dir); model?.create()
        let note = try XCTUnwrap(model?.current); draw(note); model?.changed()
        let expected = try note.capture().encode(), file = dir.appendingPathComponent(note.documentId + ".ncp")
        weak var observer = model
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertTrue(status.workerStopped); XCTAssertEqual(status.pendingWrites, 0); finished = true
        }, .started)
        model = nil
        try await eventually("failed retirement still holds original write") { observer?.ownerRetirementStatus.retryRequired == 1 }
        XCTAssertFalse(finished); XCTAssertNotNil(observer)
        XCTAssertEqual(observer?.ownerRetirementStatus.phase, .retiring)
        XCTAssertFalse(observer?.ownerRetirementStatus.workerStopped ?? true)
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertEqual(observer?.retryRetainedWrites(), true)
        try await eventually("repair releases the original owner") { finished && observer == nil }
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), expected)
    }
    @MainActor func testOwnerRetirementRefusesActiveInputAndOutstandingExportWithoutCancellingThem() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.beginStroke(x: 1, y: 2, pressure: 0.5, timeMicros: 1))
        XCTAssertEqual(model.beginOwnerRetirement(), .inputPending)
        XCTAssertTrue(note.hasActiveStroke); XCTAssertEqual(model.ownerRetirementStatus.phase, .active)
        note.cancelStroke() // Explicit caller cancellation, not a side effect of retirement refusal.
        model.prepareExport()
        XCTAssertEqual(model.beginOwnerRetirement(), .transferPending)
        try await eventually("export is held by original consumer") { !model.transferBusy && model.exportDocument != nil }
        let bytes = model.exportDocument?.data
        XCTAssertEqual(model.beginOwnerRetirement(), .transferPending)
        XCTAssertEqual(model.exportDocument?.data, bytes)
        model.finishExport()
        var finished = false
        XCTAssertEqual(model.beginOwnerRetirement { _ in finished = true }, .started)
        try await eventually("idle terminal owner stops") { finished }
        XCTAssertEqual(model.beginOwnerRetirement(), .retired)
        XCTAssertFalse(model.retryRetainedWrites())
    }
    @MainActor func testSavedIdentitySkipsUnchangedIOButChangedFailureRemainsRetryable() async throws {
        let dir = directory(), moved = directory()
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: moved) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current)
        draw(note); model.changed()
        let file = dir.appendingPathComponent(note.documentId + ".ncp")
        let original = try note.capture().encode()
        let savedIdentity = note.snapshot()
        defer { withExtendedLifetime(savedIdentity) {} }
        try await eventually("initial saved marker") { model.status.hasPrefix("已保存") && (try? String(contentsOf: file, encoding: .utf8)) == original }
        try FileManager.default.moveItem(at: dir, to: moved)
        try Data("blocked directory".utf8).write(to: dir)
        XCTAssertTrue(model.save()); XCTAssertTrue(model.status.hasPrefix("已保存"))
        XCTAssertTrue(note.rename(value: "Changed while storage unavailable")); model.changed()
        try await eventually("changed root did not inherit saved marker") { model.status.contains("保存失败") }
        let changed = try note.capture().encode()
        XCTAssertNotEqual(changed, original)
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.moveItem(at: moved, to: dir)
        XCTAssertTrue(model.save())
        try await eventually("repair saves retained current root") { model.status.hasPrefix("已保存") && (try? String(contentsOf: file, encoding: .utf8)) == changed }
        model.close(); XCTAssertNil(model.current)
        let reopened = PreviewModel(directory: dir); reopened.open(note.documentId)
        try await eventually("reopen adopts saved revision") { reopened.current != nil }
        XCTAssertTrue(reopened.save())
        try await eventually("reopened save completes even if weak identity expires") { reopened.status.hasPrefix("已保存") }
        reopened.close()
    }
    @MainActor func testPendingExportKeepsFirstCapturedContentUntilConsumerClearsIt() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current); draw(note)
        let original = try note.capture().encode()
        model.prepareExport()
        XCTAssertTrue(note.rename(value: "Later export"))
        model.prepareExport() // A second request cannot replace the accepted capture.
        try await eventually("first export ready") { !model.transferBusy && model.exportDocument != nil }
        let first = try XCTUnwrap(model.exportDocument?.data)
        XCTAssertEqual(String(data: first, encoding: .utf8), original)
        model.prepareExport(); XCTAssertEqual(model.exportDocument?.data, first)
        model.finishExport() // Same ownership release as exporter cancellation/completion.
        model.prepareExport()
        let later = try note.capture().encode()
        try await eventually("next export admitted after clear") { !model.transferBusy && model.exportDocument != nil }
        XCTAssertEqual(String(data: try XCTUnwrap(model.exportDocument?.data), encoding: .utf8), later)
        model.finishExport(); XCTAssertTrue(model.save())
        try await eventually("post-export root saved") { model.status.hasPrefix("已保存") }
        model.close()
    }
    @MainActor func testExportResultCapacityRefusalKeepsSourceAndExactChargeUntilConsumerRelease() async throws {
        let fixture = try PagedPreviewNotebook.companion.create(documentId: "export-budget", title: "中文😀", sessionNonce: "export-budget")
        draw(fixture)
        let root = fixture.snapshot(), encoded = try fixture.capture().encode()
        let bytes = Data(encoded.utf8), charge = 512 + bytes.count
        XCTAssertNotEqual(bytes.count, encoded.utf16.count)
        for limit in [0, charge - 1, charge] {
            let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
            let model = PreviewModel(directory: dir, exportResultByteLimit: limit)
            try model.adopt(root, persisted: false)
            XCTAssertTrue(model.save())
            try await eventually("source written before export capacity check") { model.status.hasPrefix("已保存") }
            let file = dir.appendingPathComponent("export-budget.ncp")
            let original = try Data(contentsOf: file)
            model.prepareExport()
            try await eventually("original worker export completes") { !model.transferBusy }
            XCTAssertTrue(model.current?.snapshot() === root)
            XCTAssertEqual(try Data(contentsOf: file), original)
            if limit < charge {
                XCTAssertNil(model.exportDocument); XCTAssertEqual(model.exportRetainedByteCharge, 0)
                XCTAssertEqual(model.status, "备份超过预览导出容量，原笔记仍保留")
                if limit == charge - 1 {
                    XCTAssertTrue(model.current!.rename(value: "a"))
                    let smaller = try model.current!.capture().encode()
                    model.prepareExport()
                    try await eventually("same owner retries smaller result at unchanged capacity") { !model.transferBusy && model.exportDocument != nil }
                    XCTAssertEqual(model.exportDocument?.data, Data(smaller.utf8))
                    XCTAssertEqual(model.exportRetainedByteCharge, 512 + smaller.utf8.count)
                    model.finishExport(); XCTAssertEqual(model.exportRetainedByteCharge, 0)
                }
            } else {
                XCTAssertEqual(model.exportDocument?.data, bytes)
                XCTAssertEqual(model.exportRetainedByteCharge, charge)
                XCTAssertEqual(model.beginOwnerRetirement(), .transferPending)
                model.prepareExport(); XCTAssertEqual(model.exportDocument?.data, bytes)
                XCTAssertEqual(model.exportRetainedByteCharge, charge)
                model.finishExport(); model.finishExport()
                XCTAssertNil(model.exportDocument); XCTAssertEqual(model.exportRetainedByteCharge, 0)
                model.prepareExport()
                try await eventually("same result accepted after consumer releases prior one") { !model.transferBusy && model.exportDocument != nil }
                XCTAssertEqual(model.exportDocument?.data, bytes)
                XCTAssertEqual(model.exportRetainedByteCharge, charge)
                model.finishExport(); XCTAssertEqual(model.exportRetainedByteCharge, 0)
            }
            XCTAssertEqual(model.beginOwnerRetirement(), .started)
            try await eventually("export owner drains after release or refusal") { model.ownerRetirementStatus.phase == .retired }
        }
    }
    @MainActor func testCatalogCapacityPreservesCompleteOldRowsAndChargesRetainedOverlay() async throws {
        let dir = directory(), held = directory()
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: held) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        func fixture(_ id: String, title: String = "中文😀") -> String {
            "NCPREVIEW\t2\nD\t\(id)\t\(title)\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        }
        for id in ["catalog-a", "catalog-b"] { try Data(fixture(id).utf8).write(to: dir.appendingPathComponent(id + ".ncp")) }
        for limit in [Int64(0), 1587, 1588] {
            let model = PreviewModel(directory: dir, catalogResultByteLimit: limit)
            if limit < 1588 {
                try await eventually("catalog quota refuses whole result") { model.catalogError }
                XCTAssertTrue(model.notes.isEmpty); XCTAssertEqual(model.catalogRetainedByteCharge, 0)
                XCTAssertEqual(model.status, "列表超过预览元数据容量，原列表与文件仍保留")
            } else {
                try await eventually("exact metadata bytes accepted") { model.notes.count == 2 }
                XCTAssertFalse(model.catalogError); XCTAssertEqual(model.catalogRetainedByteCharge, 1588)
                let extra = dir.appendingPathComponent("extra.ncp")
                try Data(fixture("extra").utf8).write(to: extra)
                model.refresh(); try await eventually("larger metadata result refuses without replacing prior rows") { model.catalogError }
                XCTAssertEqual(model.notes.count, 2); XCTAssertEqual(model.catalogRetainedByteCharge, 1588)
                XCTAssertEqual(model.status, "列表超过预览元数据容量，原列表与文件仍保留")
                try FileManager.default.removeItem(at: extra)
                model.refresh(); try await eventually("same catalog owner repairs capacity refusal") { !model.catalogError }
            }
            XCTAssertEqual(model.beginOwnerRetirement(), .started)
            try await eventually("catalog owner retires") { model.ownerRetirementStatus.phase == .retired }
            XCTAssertEqual(model.catalogRetainedByteCharge, 0)
        }
        let model = PreviewModel(directory: dir)
        try await eventually("two initial disk notes") { model.notes.count == 2 }
        try FileManager.default.moveItem(at: dir, to: held); try Data("blocked".utf8).write(to: dir)
        model.refresh()
        try await eventually("IO failure reaches existing list owner") { model.catalogError }
        XCTAssertEqual(Set(model.notes.map { $0.id }), Set(["catalog-a", "catalog-b"]))
        try FileManager.default.removeItem(at: dir); try FileManager.default.moveItem(at: held, to: dir)
        model.refresh(); try await eventually("catalog IO repair") { !model.catalogError }
        let title = String(repeating: "中", count: 118) + "😀"
        for index in 2..<2000 {
            let number = String(index), id = "n" + String(repeating: "0", count: 127 - number.count) + number
            try Data(fixture(id, title: title).utf8).write(to: dir.appendingPathComponent(id + ".ncp"))
        }
        model.refresh(); try await eventually("all 2000 notes without truncation") { model.notes.count == 2000 && !model.catalogError }
        XCTAssertEqual(model.notes.filter { $0.id.utf16.count == 128 && $0.title == title }.count, 1998)
        let ids = Set(model.notes.map { $0.id })
        let overflow = dir.appendingPathComponent("overflow.ncp")
        try Data(fixture("overflow").utf8).write(to: overflow)
        model.refresh(); try await eventually("2001 explicitly refused") { model.catalogError }
        XCTAssertEqual(Set(model.notes.map { $0.id }), ids)
        XCTAssertEqual(try String(contentsOf: overflow, encoding: .utf8), fixture("overflow"))
        try FileManager.default.removeItem(at: overflow)
        model.refresh(); try await eventually("full catalog recovers after capacity repair") { !model.catalogError }
        try FileManager.default.moveItem(at: dir, to: held); try Data("blocked".utf8).write(to: dir)
        for _ in 0..<8 {
            model.create(); XCTAssertNotNil(model.current)
            XCTAssertTrue(model.current!.rename(value: String(repeating: "中", count: 120)))
            XCTAssertTrue(model.save()); model.close(); XCTAssertNil(model.current)
        }
        try await eventually("eight accepted writes retained after IO failure") { model.ownerRetirementStatus.retryRequired == 8 }
        model.refresh(); try await eventually("ledger overlays complete old disk list") { model.notes.count == 2008 && model.catalogError }
        XCTAssertEqual(model.notes.filter { $0.title.hasSuffix("（未保存）") }.count, 8)
        XCTAssertLessThanOrEqual(model.catalogRetainedByteCharge, 2 * 1024 * 1024)
        try FileManager.default.removeItem(at: dir); try FileManager.default.moveItem(at: held, to: dir)
        XCTAssertTrue(model.retryRetainedWrites())
        try await eventually("original ledger writes complete after repair") { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertEqual(model.beginOwnerRetirement(), .started)
        try await eventually("catalog owner releases snapshot on retirement") { model.ownerRetirementStatus.phase == .retired }
        XCTAssertEqual(model.catalogRetainedByteCharge, 0)
    }
    @MainActor func testNativeScalarBatchBridgeValidatesLengthsAndCopiesAcceptedData() throws {
        func data<T>(_ values: [T]) -> NSData {
            values.withUnsafeBufferPointer { NSData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<T>.stride) }
        }
        let note = try PagedPreviewNotebook.companion.create(documentId: "native-batch", title: "batch", sessionNonce: "native-batch")
        func offer(_ phase: PointerPhase, _ time: Int64, _ x: NSData, _ y: NSData, _ p: NSData, _ t: NSData) -> PreviewInputResult {
            ApplePreviewInput.shared.offer(notebook: note, phase: phase, eventTimeMicros: time, xs: x as Data, ys: y as Data, pressures: p as Data, times: t as Data)
        }
        let empty = NSData()
        let oversized = offer(.down, 0, Data(count: (Int(note.inputBatchLimit) + 1) * 8) as NSData, empty, empty, empty)
        XCTAssertFalse(oversized.admitted); XCTAssertEqual(oversized.failure?.kind, .inputBatchCapacity)
        let badLength = offer(.down, 1, Data([0xff]) as NSData, empty, empty, empty)
        XCTAssertFalse(badLength.admitted); XCTAssertEqual(badLength.acceptedSamples, 0)
        let mutableX = data([1.0, 2.0]).mutableCopy() as! NSMutableData
        let down = offer(.down, 2, mutableX, data([3.0, 4.0]), data([Float(0.2), Float(0.3)]), data([Int64(1), Int64(2)]))
        XCTAssertEqual(down.disposition, .accepted); XCTAssertEqual(down.acceptedSamples, 2)
        mutableX.resetBytes(in: NSRange(location: 0, length: mutableX.length))
        let badTail = offer(.move, 4, data([3.0, Double.nan]), data([5.0, 6.0]), data([Float(0.4), Float(0.5)]), data([Int64(3), Int64(4)]))
        XCTAssertFalse(badTail.admitted); XCTAssertEqual(badTail.activeSamples, 2); XCTAssertEqual(badTail.acceptedSamples, 0)
        let good = offer(.move, 4, data([3.0, 4.0]), data([5.0, 6.0]), data([Float(0.4), Float(0.5)]), data([Int64(3), Int64(4)]))
        XCTAssertTrue(good.admitted)
        let up = offer(.up, 4, empty, empty, empty, empty)
        XCTAssertEqual(up.disposition, .committed); XCTAssertEqual(up.acceptedSamples, 0)
        XCTAssertEqual(note.pointCount(index: 0), 4); XCTAssertEqual(note.pointX(index: 0, point: 0), 1)
        XCTAssertEqual(note.pointY(index: 0, point: 0), 3); XCTAssertEqual(note.pointPressure(index: 0, point: 0), Float(0.2))
        XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: 3), 4)
    }
    @MainActor func testTypedEditModelRejectsInvalidParametersWithoutLosingActiveInk() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current), before = note.snapshot()
        XCTAssertTrue(note.beginStroke(x: 1, y: 2, pressure: 0.5, timeMicros: 1))
        model.applyEdit(PreviewEditSetPaper(width: .nan, height: 500, paper: .grid))
        XCTAssertEqual(note.lastFailure?.kind, PreviewFailureKind.invalidArgument)
        XCTAssertTrue(note.hasActiveStroke); XCTAssertTrue(note.snapshot() === before)
        model.applyEdit(PreviewEditUndo.shared)
        XCTAssertEqual(note.lastFailure?.kind, PreviewFailureKind.historyEmpty); XCTAssertTrue(note.hasActiveStroke)
        let invalidId = note.edit(request: PreviewEditSelectPage(pageId: ""))
        XCTAssertFalse(invalidId.accepted); XCTAssertEqual(invalidId.failure?.kind, PreviewFailureKind.invalidArgument)
        XCTAssertTrue(note.endStroke(x: 3, y: 4, pressure: 0.6, timeMicros: 2)); XCTAssertEqual(note.pointCount(index: 0), 2)
        model.applyEdit(PreviewEditRename(title: "合法😀")); model.applyEdit(PreviewEditInsertPage.shared)
        XCTAssertEqual(note.pageCount, 2); XCTAssertNil(note.lastFailure)
        let file = dir.appendingPathComponent(note.documentId + ".ncp"), expected = try note.capture().encode()
        try await eventually("typed edits save through original ledger") { (try? String(contentsOf: file, encoding: .utf8)) == expected }
        model.close(); XCTAssertNil(model.current)
        let reopened = try PagedPreviewCodec.shared.decode(text: PreviewFileIO.readUTF8(file))
        XCTAssertEqual(reopened.title, "合法😀"); XCTAssertEqual(reopened.pageCount, 2); XCTAssertEqual(reopened.scene.objectCount, 1)
    }
    @MainActor func testAuthorityConstructionFailureIsRecoverableInSwift() throws {
        // Swift strings cannot inject malformed UTF-16. Exercise the same annotated
        // constructor's IllegalArgumentException using a representable over-budget root.
        let text = "NCPREVIEW\t2\nD\ttitle-host\t合法😀\t2\t0\t0\nG\tfirst\tlayer-a\t400\t400\tBLANK\t0\nG\tsecond\tlayer-b\t400\t400\tBLANK\t0\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let budget = DocumentBudget(maxPages: 1, maxObjects: 5000, liveBytes: 8*1024*1024, journalEntries: 128,
            journalBytes: 8*1024*1024, identityBytes: 2*1024*1024, historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024)
        XCTAssertThrowsError(try MemoryDocumentAuthority(initial: root, budget: budget, ownerCheck: {}))
        let valid = try MemoryDocumentAuthority(initial: root, budget: DocumentBudget(maxPages: 128, maxObjects: 5000,
            liveBytes: 8*1024*1024, journalEntries: 128, journalBytes: 8*1024*1024, identityBytes: 2*1024*1024,
            historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024), ownerCheck: {})
        XCTAssertEqual(valid.snapshot().title, "合法😀")
    }
    func testSharedBrushContoursMatchAnalyticCoverageOffscreen() throws {
        struct Fixture: Decodable {
            struct Case: Decodable { let name: String; let alpha: Double; let contours: [[[Double]]]; let oracle_mask: [String] }
            let width: Int; let height: Int; let fill_rule: String; let cases: [Case]
        }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "brush-paths", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        XCTAssertEqual(fixture.fill_rule, "nonzero-once"); XCTAssertEqual(fixture.cases.count, 6)
        var testedPixels = 0
        for c in fixture.cases {
            let path = CGMutablePath()
            for contour in c.contours {
                for (index, point) in contour.enumerated() {
                    let p = CGPoint(x: point[0], y: point[1])
                    if index == 0 { path.move(to: p) } else { path.addLine(to: p) }
                }
                path.closeSubpath()
            }
            for antialias in [false, true] {
                var pixels = [UInt8](repeating: 0, count: fixture.width * fixture.height * 4)
                try pixels.withUnsafeMutableBytes { bytes in
                    let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: fixture.width, height: fixture.height,
                        bitsPerComponent: 8, bytesPerRow: fixture.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
                    context.setAllowsAntialiasing(antialias); context.setShouldAntialias(antialias)
                    // Fixture uses top-down scene pixels; raw CGBitmapContext rows
                    // are vertically reversed relative to its default user space.
                    context.translateBy(x: 0, y: CGFloat(fixture.height))
                    context.scaleBy(x: 1, y: -1)
                    context.setFillColor(red: 1, green: 1, blue: 1, alpha: c.alpha)
                    context.addPath(path); context.fillPath(using: .winding) // ONE compound fill.
                }
                for y in 0..<fixture.height {
                    let row = Array(c.oracle_mask[y])
                    for x in 0..<fixture.width where row[x] != "?" {
                        let actual = Int(pixels[(y * fixture.width + x) * 4 + 3])
                        let expected = row[x] == "#" ? Int((c.alpha * 255).rounded()) : 0
                        XCTAssertLessThanOrEqual(abs(actual - expected), 1, "\(c.name) AA=\(antialias) x=\(x) y=\(y) expected=\(expected) actual=\(actual)")
                        testedPixels += 1
                    }
                }
            }
        }
        XCTAssertGreaterThan(testedPixels, 20000)
    }
    private func directory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("NoteCoreHostTests-" + UUID().uuidString, isDirectory: true)
    }
    @MainActor private func eventually(_ message: String, _ predicate: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(8)
        while !predicate() && Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertTrue(predicate(), message)
    }
    private func draw(_ note: PagedPreviewNotebook, x: Double = 100) {
        XCTAssertTrue(note.beginStroke(x: x, y: 200, pressure: 0.3, timeMicros: 1))
        XCTAssertTrue(note.endStroke(x: x + 50, y: 250, pressure: 0.8, timeMicros: 2))
    }
    private func androidFixture() throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: "android-paged-v2", withExtension: "ncp"))
    }
    @MainActor func testActualAndroidFileImportsFreshIdsAndExportsAllPages() async throws {
        let sourceURL = try androidFixture(), original = try Data(contentsOf: sourceURL)
        let source = try PagedPreviewCodec.shared.decode(text: String(decoding: original, as: UTF8.self))
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.importCopy(sourceURL)
        try await eventually("Android root imported") { !model.transferBusy }
        let note = try XCTUnwrap(model.current), copy = note.snapshot()
        XCTAssertEqual(copy.pageCount, 3); XCTAssertEqual(copy.scene.objectCount, 3)
        XCTAssertNotEqual(source.id.value, copy.id.value)
        XCTAssertTrue(Set(source.pages().map { $0.id.value }).isDisjoint(with: copy.pages().map { $0.id.value }))
        XCTAssertTrue(Set(source.pages().map { $0.layerId.value }).isDisjoint(with: copy.pages().map { $0.layerId.value }))
        XCTAssertTrue(Set(source.scene.objects().map { $0.id.value }).isDisjoint(with: copy.scene.objects().map { $0.id.value }))
        for index in 0..<source.pageCount {
            let a = source.page(index: index), b = copy.page(index: index)
            XCTAssertEqual(a.width, b.width); XCTAssertEqual(a.height, b.height); XCTAssertEqual(a.paper, b.paper)
            let old = source.pageObjects(id: a.id), new = copy.pageObjects(id: b.id)
            XCTAssertEqual(old.count, new.count)
            for (before, after) in zip(old, new) {
                XCTAssertEqual(before.brush, after.brush); XCTAssertEqual(before.transform, after.transform)
                XCTAssertEqual(before.samples, after.samples)
            }
        }
        model.editPage(content: false) { $0.selectPage(id: $0.page(index: 2).id) }
        model.prepareExport(); try await eventually("whole root export ready") { !model.transferBusy }
        let exported = try XCTUnwrap(model.exportDocument)
        XCTAssertEqual(String(decoding: exported.data, as: UTF8.self), try note.capture().encode())
        XCTAssertEqual(try Data(contentsOf: sourceURL), original)
        model.close()
    }
    @MainActor func testUnsupportedBrushAndCorruptUTF8DoNotCreateCopyOrChangeSource() async throws {
        let dir = directory(), inputs = directory()
        try FileManager.default.createDirectory(at: inputs, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: inputs) }
        let supported = try String(contentsOf: androidFixture(), encoding: .utf8)
        let unsupported = supported.replacingOccurrences(of: "MONOLINE", with: "PRESSURE")
        let url = inputs.appendingPathComponent("unsupported.ncp"); try Data(unsupported.utf8).write(to: url)
        let model = PreviewModel(directory: dir); model.importCopy(url)
        try await eventually("unsupported brush returns failure") { !model.transferBusy }
        XCTAssertNil(model.current); XCTAssertTrue(model.status.hasPrefix("导入失败"))
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), unsupported)
        XCTAssertTrue(try PreviewFileIO.catalog(dir).isEmpty)
        let corrupt = inputs.appendingPathComponent("bad-utf.ncp"); try Data([0xff]).write(to: corrupt)
        model.importCopy(corrupt); try await eventually("bad UTF returns failure") { !model.transferBusy }
        XCTAssertNil(model.current); XCTAssertEqual(try Data(contentsOf: corrupt), Data([0xff]))
        XCTAssertTrue(try PreviewFileIO.catalog(dir).isEmpty)
    }
    @MainActor func testLateImportCannotReplaceNewActiveOwner() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir)
        model.importCopy(try androidFixture()); model.create()
        let active = try XCTUnwrap(model.current)
        try await eventually("import finishes alongside new note") { !model.transferBusy }
        XCTAssertTrue(model.current === active); XCTAssertEqual(active.pageCount, 1)
        try await eventually("both documents saved") { (try? PreviewFileIO.catalog(dir).count) == 2 }
        model.close()
    }
    @MainActor func testLateOpenCannotReplaceNewActiveOwner() async throws {
        let dir = directory(); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let root = try PagedPreviewCodec.shared.decode(text: String(contentsOf: androidFixture(), encoding: .utf8))
        try PreviewFileIO.writeUTF8(PagedPreviewCodec.shared.encode(root: root), to: dir.appendingPathComponent(root.id.value + ".ncp"))
        let model = PreviewModel(directory: dir); model.open(root.id.value); model.create()
        let active = try XCTUnwrap(model.current)
        try await eventually("new active note saved after old read result") { model.status.hasPrefix("已保存") }
        XCTAssertTrue(model.current === active); XCTAssertNotEqual(active.documentId, root.id.value)
        model.close()
    }
    @MainActor func testPagedSaveCloseAndNewOwnerReadAllPages() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current), id = note.documentId
        draw(note); model.changed(); let first = note.currentPageId
        model.editPage { $0.insertPage() }; XCTAssertEqual(note.pageCount, 2)
        XCTAssertTrue(note.selectPen(argb: Int32(bitPattern: 0xff3568be), width: 5))
        draw(note, x: 200); model.changed()
        let beforeSelection = note.snapshot()
        model.editPage(content: false) { $0.selectPage(id: first) }
        XCTAssertTrue(note.snapshot() === beforeSelection)
        model.editPage { $0.doCopyCurrentPage() }; XCTAssertEqual(note.totalStrokeCount, 3)
        model.editPage { $0.deleteCurrentPage() }; XCTAssertEqual(note.totalStrokeCount, 2)
        model.editPage { $0.undo() }; XCTAssertEqual(note.totalStrokeCount, 3)
        let encoded = try note.capture().encode(), file = dir.appendingPathComponent(id + ".ncp")
        try await eventually("full root written") { (try? String(contentsOf: file, encoding: .utf8)) == encoded && model.status.hasPrefix("已保存") }
        model.close(); XCTAssertNil(model.current)
        XCTAssertFalse(note.beginStroke(x: 1, y: 2, pressure: 0.5, timeMicros: 3))
        let reopened = PreviewModel(directory: dir); reopened.open(id)
        try await eventually("new owner receives disk root") { reopened.current != nil }
        let restored = try XCTUnwrap(reopened.current)
        XCTAssertEqual(try restored.capture().encode(), encoded)
        XCTAssertEqual(restored.pageCount, 3); XCTAssertEqual(restored.totalStrokeCount, 3)
        XCTAssertFalse(restored.canUndo)
        for index in 0..<restored.pageCount {
            reopened.editPage(content: false) { $0.selectPage(id: $0.page(index: index).id) }
            XCTAssertEqual(restored.strokeCount, 1)
        }
        reopened.close()
    }
    @MainActor func testLegacyNoEditPreservesBytesThenEditMigratesOriginalExtent() async throws {
        let dir = directory(); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let original = "NCPREVIEW\t1\nD\tlegacy-corner\tcorner\t2\t1\t1\nS\tink-1\t-1\t2.5\t2\nP\t900\t1300\t0.3\t1\nP\t999\t1399\t0.8\t2\nEND\n"
        let file = dir.appendingPathComponent("legacy-corner.ncp")
        try PreviewFileIO.writeUTF8(original, to: file)
        let model = PreviewModel(directory: dir); model.open("legacy-corner")
        try await eventually("v1 opens") { model.current != nil }
        XCTAssertEqual(model.current?.currentPage.width, 1000)
        XCTAssertEqual(model.current?.currentPage.height, 1400)
        XCTAssertTrue(model.save()); model.flushForBackground(); model.close()
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), original)
        model.open("legacy-corner"); try await eventually("v1 reopens") { model.current != nil }
        model.editPage { $0.insertPage() }
        let expected = try XCTUnwrap(model.current).capture().encode()
        try await eventually("first edit writes v2") { (try? String(contentsOf: file, encoding: .utf8)) == expected }
        let root = try PagedPreviewCodec.shared.decode(text: expected)
        XCTAssertEqual(root.pageCount, 2)
        XCTAssertEqual(root.scene.objects().first?.samples.y(index: 1), 1399)
        model.close()
    }
    @MainActor func testFailedSaveRetainsRootAcrossCloseAndRepair() async throws {
        let dir = directory(); try Data("blocked directory".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current); draw(note); model.changed()
        let expected = try note.capture().encode(), id = note.documentId
        try await eventually("write failure visible") { model.status.contains("保存失败") }
        model.close(); XCTAssertNil(model.current)
        try await eventually("failure still retained") { model.notes.contains { $0.id == id && $0.title.contains("未保存") } }
        model.open(id); try await eventually("latest retained root reopens") { model.current != nil }
        XCTAssertEqual(try model.current?.capture().encode(), expected)
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertTrue(model.save())
        let file = dir.appendingPathComponent(id + ".ncp")
        try await eventually("repaired retry writes exact root") { (try? String(contentsOf: file, encoding: .utf8)) == expected && model.status.hasPrefix("已保存") }
        model.close()
    }
    @MainActor func testNativeRendererChangesPageCacheAndAppliesFullAffineDotWidth() throws {
        let text = "NCPREVIEW\t2\nD\trender\tRender\t2\t1\t1\nG\tfirst\tlayer-a\t400\t400\tBLANK\t1\nG\tsecond\tlayer-b\t400\t400\tBLANK\t0\nS\tdot\tlayer-a\t1\tMONOLINE\t1\t-65536\t10\t1\t4\t0\t0\t2\t0\t0\nP\t50\t100\t0.5\t1\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8*1024*1024, journalEntries: 128,
            journalBytes: 8*1024*1024, identityBytes: 2*1024*1024, historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024)
        let note = try PagedPreviewNotebook(initial: root, sessionNonce: "render-session", budget: budget, ownerCheck: { precondition(Thread.isMainThread) })
        let view = InkUIView(note: note); view.frame = CGRect(x: 0, y: 0, width: 400, height: 400); view.layoutIfNeeded()
        func image() -> CGImage {
            let format = UIGraphicsImageRendererFormat(); format.scale = 1
            return UIGraphicsImageRenderer(size: view.bounds.size, format: format).image { _ in view.draw(view.bounds) }.cgImage!
        }
        func pixel(_ image: CGImage, _ x: Int, _ y: Int) -> [UInt8] {
            var bytes = [UInt8](repeating: 0, count: 400*400*4)
            bytes.withUnsafeMutableBytes { data in
                let context = CGContext(data: data.baseAddress, width: 400, height: 400, bitsPerComponent: 8, bytesPerRow: 1600,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
                context.draw(image, in: CGRect(x: 0, y: 0, width: 400, height: 400))
            }
            return Array(bytes[(y*400+x)*4..<(y*400+x)*4+4])
        }
        let first = image()
        let red = pixel(first, 215, 200); XCTAssertGreaterThan(red[0], 240); XCTAssertLessThan(red[1], 20)
        let white = pixel(first, 200, 215); XCTAssertGreaterThan(white[1], 240) // Ellipse is only 20 high, 40 wide.
        XCTAssertTrue(note.selectPage(id: note.page(index: 1).id)); view.refreshPage()
        let empty = pixel(image(), 200, 200); XCTAssertGreaterThan(empty[1], 240)
        XCTAssertEqual(view.accessibilityValue, "0笔")
        XCTAssertEqual(note.changeVersion, 1) // Same root revision, different page projection.
    }

    private func candidateBrushPlan(kind: BrushKind = .highlighter, width: Double = 2,
                                    transform: Affine2D? = nil) throws -> BrushPathPlan {
        let text = "NCPREVIEW\t2\nD\tbrush\tBrush\t1\t1\t1\nG\tp\tl\t128\t128\tBLANK\t1\nS\ts\tl\t1\tMONOLINE\t1\t-256\t2\t3\t1\t0\t0\t1\t0\t0\nP\t0\t0\t0\t1\nP\t4\t0\t1\t2\nP\t0\t0\t0\t3\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let samples = try XCTUnwrap(root.scene.objects().first).samples
        let geometryResult = BrushGeometryBuilder.shared.build(samples: samples,
            brush: BrushSpec(kind: kind, algorithmVersion: 2, width: width, argb: Int32(bitPattern: 0xffffff00)),
            budget: BrushGeometryBudget(maxSamples: 20000, maxVertices: 400000, logicalBytes: 8*1024*1024))
        let geometry = try XCTUnwrap(geometryResult as? BrushGeometryResultReady).geometry
        let m = transform ?? Affine2D(a: 16, b: 0, c: 0, d: 16, tx: 24, ty: 64)
        let result = BrushPathPlanner.shared.prepare(geometry: geometry, sceneToTarget: m, precision: .float32, maxVertices: 400000)
        return try XCTUnwrap(result as? BrushPathPlanResultReady).plan
    }
    private func candidateBrushPixels(plan: BrushPathPlan, times: Int = 1) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 128*128*4)
        bytes.withUnsafeMutableBytes { data in
            let context = CGContext(data: data.baseAddress, width: 128, height: 128, bitsPerComponent: 8, bytesPerRow: 512,
                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            let renderer = NativeBrushRenderer(plan: plan)
            for _ in 0..<times { renderer.draw(in: context) }
        }
        return bytes
    }
    @MainActor func testCandidateHighlighterSingleFillAndSeparateStrokeAlpha() throws {
        let plan = try candidateBrushPlan()
        let once = candidateBrushPixels(plan: plan), twice = candidateBrushPixels(plan: plan, times: 2)
        XCTAssertEqual(once[(64*128+56)*4+3], 96)
        XCTAssertEqual(twice[(64*128+56)*4+3], 156)
        XCTAssertEqual(once[(100*128+56)*4+3], 0)
    }
    @MainActor func testCandidatePressureEndpointWidthsUseCommonGeometry() throws {
        let plan = try candidateBrushPlan(kind: .pressure, width: 8,
            transform: Affine2D(a: 8, b: 0, c: 0, d: 8, tx: 24, ty: 64))
        let pixels = candidateBrushPixels(plan: plan)
        XCTAssertEqual(pixels[(75*128+24)*4+3], 0)
        XCTAssertEqual(pixels[(88*128+56)*4+3], 255)
    }
    @MainActor func testCandidateReflectedAffineRemainsFilled() throws {
        let plan = try candidateBrushPlan(transform: Affine2D(a: -16, b: 4, c: 2, d: 16, tx: 88, ty: 56))
        let pixels = candidateBrushPixels(plan: plan)
        XCTAssertEqual(pixels[(64*128+56)*4+3], 96)
        XCTAssertEqual(pixels[(100*128+56)*4+3], 0)
    }
    @MainActor func testCandidateRendererPreservesCallerCurrentPath() throws {
        let renderer = NativeBrushRenderer(plan: try candidateBrushPlan())
        let context = try XCTUnwrap(CGContext(data: nil, width: 128, height: 128, bitsPerComponent: 8, bytesPerRow: 512,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.move(to: CGPoint(x: 1, y: 2)); context.addLine(to: CGPoint(x: 3, y: 4))
        let before = context.path?.boundingBoxOfPath
        renderer.draw(in: context)
        XCTAssertEqual(context.path?.boundingBoxOfPath, before)
    }
    @MainActor private func inputView() throws -> InkUIView {
        let note = try PagedPreviewNotebook.companion.create(documentId: "input-" + UUID().uuidString,
            title: "Synthetic callbacks", sessionNonce: UUID().uuidString)
        XCTAssertTrue(note.setPaper(width: 200, height: 200, paper: .blank))
        let view = InkUIView(note: note)
        view.frame = CGRect(x: 0, y: 0, width: 200, height: 200); view.layoutIfNeeded()
        return view
    }
    @MainActor func testSiblingSurfaceCannotCancelOrAppendAnotherAcceptedInput() throws {
        let a = try inputView(), note = a.note
        let touchA = SyntheticInkTouch(10, 1)
        a.touchesBegan([touchA], with: nil)
        XCTAssertTrue(note.hasActiveStroke)
        let b = InkUIView(note: note)
        b.frame = a.frame; b.layoutIfNeeded() // Initial configuration must not cancel A.
        b.cancelInk()
        XCTAssertTrue(note.hasActiveStroke, "idle sibling cancelled original accepted input")
        let touchB = SyntheticInkTouch(100, 2)
        b.touchesBegan([touchB], with: nil) // Competing DOWN must preserve A.
        XCTAssertTrue(note.hasActiveStroke); XCTAssertTrue(a.hasInk); XCTAssertFalse(b.hasInk)
        touchA.position.x = 20; touchA.seconds = 1.1
        a.touchesEnded([touchA], with: nil)
        XCTAssertEqual(note.totalStrokeCount, 1); XCTAssertEqual(note.pointCount(index: 0), 2)
        XCTAssertEqual(note.pointX(index: 0, point: 0), 10, accuracy: 0.001)
        XCTAssertEqual(note.pointX(index: 0, point: 1), 20, accuracy: 0.001)
        touchA.position.x = 30; touchA.seconds = 3
        a.touchesBegan([touchA], with: nil)
        note.cancelStroke() // An explicit App-level action cancels A, whose UIKit touch remains stale.
        touchB.position.x = 100; touchB.seconds = 4
        b.touchesBegan([touchB], with: nil)
        touchA.position.x = 199; touchA.seconds = 4.1
        a.touchesMoved([touchA], with: nil); a.touchesEnded([touchA], with: nil)
        a.cancelInk(); NativeInkCanvas.dismantleUIView(a, coordinator: ())
        XCTAssertTrue(note.hasActiveStroke, "stale sibling affected later input")
        var changes = 0; b.changed = { changes += 1 }
        touchB.position.x = 120; touchB.seconds = 4.2
        b.touchesEnded([touchB], with: nil)
        XCTAssertEqual(note.totalStrokeCount, 2); XCTAssertEqual(changes, 1)
        XCTAssertEqual(note.pointCount(index: 1), 2)
        XCTAssertEqual(note.pointX(index: 1, point: 0), 100, accuracy: 0.001)
        XCTAssertEqual(note.pointX(index: 1, point: 1), 120, accuracy: 0.001)
        XCTAssertFalse(note.hasActiveStroke); XCTAssertEqual(note.inputReservedByteCharge, 0)
        XCTAssertTrue(note.retire())
    }
    @MainActor func testCoalescedDownMoveUpRetainsEveryRealSampleAndNoPredictions() throws {
        let view = try inputView(), note = view.note
        let main = SyntheticInkTouch(30, 0.003)
        let event = SyntheticInkEvent()
        event.real = [SyntheticInkTouch(10, 0.001), SyntheticInkTouch(20, 0.002), SyntheticInkTouch(30, 0.003)]
        view.touchesBegan([main], with: event)
        XCTAssertTrue(note.hasActiveStroke); XCTAssertEqual(note.strokeCount, 0)
        // UIKit main touchs are mutable across events; accepted auxiliary values must be copied.
        event.real?[0].position.x = 199
        main.position.x = 50; main.seconds = 0.005
        event.real = [SyntheticInkTouch(40, 0.004), SyntheticInkTouch(50, 0.005)]
        view.touchesMoved([main], with: event)
        main.position.x = 70; main.seconds = 0.007
        event.real = [SyntheticInkTouch(60, 0.006), SyntheticInkTouch(70, 0.007)]
        var changes = 0; view.changed = { changes += 1 }
        view.touchesEnded([main], with: event)
        XCTAssertEqual(changes, 1); XCTAssertEqual(note.strokeCount, 1); XCTAssertEqual(note.pointCount(index: 0), 7)
        for index in 0..<7 {
            XCTAssertEqual(note.pointX(index: 0, point: Int32(index)), Double((index + 1) * 10), accuracy: 0.0001)
            XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: Int32(index)), Int64((index + 1) * 1000))
        }
        XCTAssertEqual(event.predictionReads, 0); XCTAssertFalse(view.hasInk)
    }
    @MainActor func testMaximumProducerBatchPreservesAllScalarsAndRejectsOversizeBeforeRecovery() throws {
        let view = try inputView(), note = view.note, event = SyntheticInkEvent()
        let main = SyntheticInkTouch(0, 1)
        let real = (0..<1024).map { index in
            let touch = SyntheticInkTouch(index == 0 ? -0.0 : Double(index % 199), Double(index) / 1024)
            touch.position.y = CGFloat(index % 197)
            touch.forceValue = CGFloat(index % 9) / 8
            return touch
        }
        event.real = real
        view.touchesBegan([main], with: event)
        XCTAssertTrue(note.hasActiveStroke)
        // Native buffers and the arena must copy scalars before mutable UIKit objects change.
        real[1].position = CGPoint(x: 199, y: 199); real[1].forceValue = 1; real[1].seconds = 99
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.totalStrokeCount, 1)
        let samples = note.stroke(index: 0).samples
        XCTAssertEqual(samples.size, 1025)
        for index in 0..<1024 {
            let i = Int32(index)
            let x = index == 0 ? -0.0 : Double(index % 199)
            XCTAssertEqual(samples.x(index: i).bitPattern, x.bitPattern)
            XCTAssertEqual(samples.y(index: i).bitPattern, Double(index % 197).bitPattern)
            XCTAssertEqual(samples.pressure(index: i).bitPattern, (Float(index % 9) / 8).bitPattern)
            XCTAssertEqual(samples.timeMicros(index: i), Int64(Double(index) / 1024 * 1_000_000))
        }
        XCTAssertEqual(samples.timeMicros(index: 1024), 1_000_000)
        let before = try note.capture().encode()
        event.real = (0..<1025).map { SyntheticInkTouch(Double($0 % 199), 2) }
        view.touchesBegan([main], with: event)
        XCTAssertFalse(note.hasActiveStroke); XCTAssertEqual(try note.capture().encode(), before)
        event.real = nil; main.seconds = 3
        view.touchesBegan([main], with: event); main.seconds = 4
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.totalStrokeCount, 2); XCTAssertEqual(event.predictionReads, 0)
    }
    @MainActor func testNilAndEmptyCoalescedFallbackKeepsEqualTimestampPoints() throws {
        let view = try inputView(), note = view.note
        let main = SyntheticInkTouch(10, 0.001), event = SyntheticInkEvent()
        view.touchesBegan([main], with: event) // nil => original main
        event.real = []; main.position.x = 20
        view.touchesMoved([main], with: event) // empty => original main, same timestamp/different point
        main.position.x = 30; main.seconds = 0.002
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.pointCount(index: 0), 3)
        XCTAssertEqual(note.pointX(index: 0, point: 1), 20, accuracy: 0.0001)
        XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: 0), note.stroke(index: 0).samples.timeMicros(index: 1))
        XCTAssertTrue(note.undo()); XCTAssertEqual(note.strokeCount, 0)
        XCTAssertTrue(note.redo()); XCTAssertEqual(note.pointCount(index: 0), 3)
    }
    @MainActor func testInvalidTerminalValuesRejectWholeUnfinishedStrokeAndRecover() throws {
        let view = try inputView(), note = view.note
        var failures = 0; view.failed = { _ in failures += 1 }
        let invalid = [Double.nan, Double.infinity, -1, Double(Int64.max) / 1_000_000]
        for value in invalid {
            let main = SyntheticInkTouch(10, 0.001), event = SyntheticInkEvent()
            view.touchesBegan([main], with: nil)
            event.real = [SyntheticInkTouch(20, 0.002), SyntheticInkTouch(30, value)]
            view.touchesEnded([main], with: event)
            XCTAssertFalse(note.hasActiveStroke); XCTAssertFalse(view.hasInk); XCTAssertEqual(note.strokeCount, 0)
        }
        let badPosition = SyntheticInkTouch(30, 0.003); badPosition.position.x = .nan
        let badForce = SyntheticInkTouch(30, 0.003); badForce.forceValue = .nan
        let badMaximum = SyntheticInkTouch(30, 0.003); badMaximum.maximumValue = .nan
        let outOfRange = SyntheticInkTouch(1e10, 0.003)
        let badOrder = SyntheticInkTouch(30, 0.0005)
        for bad in [badPosition, badForce, badMaximum, outOfRange, badOrder] {
            let main = SyntheticInkTouch(10, 0.001), event = SyntheticInkEvent()
            view.touchesBegan([main], with: nil)
            event.real = [SyntheticInkTouch(20, 0.002), bad]
            view.touchesEnded([main], with: event)
            XCTAssertFalse(note.hasActiveStroke); XCTAssertFalse(view.hasInk); XCTAssertEqual(note.strokeCount, 0)
        }
        XCTAssertEqual(failures, invalid.count + 5)
        let main = SyntheticInkTouch(40, 0.004)
        view.touchesBegan([main], with: nil); main.seconds = 0.005
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.strokeCount, 1); XCTAssertEqual(note.pointCount(index: 0), 2)
    }
    @MainActor func testCoordinateSnapshotAndResizeCancelRejectsLateUp() throws {
        let view = try inputView(), note = view.note
        view.viewport.gesture(oldX: 100, oldY: 100, newX: 100, newY: 100, ratio: 2)
        let main = SyntheticInkTouch(60, 0.001)
        view.touchesBegan([main], with: nil)
        main.position.x = 80; main.seconds = 0.002
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.pointX(index: 0, point: 0), 80, accuracy: 0.0001)
        XCTAssertEqual(note.pointX(index: 0, point: 1), 90, accuracy: 0.0001)
        let next = SyntheticInkTouch(100, 0.003)
        view.touchesBegan([next], with: nil); XCTAssertTrue(note.hasActiveStroke)
        view.frame.size.width = 240; view.setNeedsLayout(); view.layoutIfNeeded()
        XCTAssertFalse(note.hasActiveStroke)
        next.seconds = 0.004; view.touchesEnded([next], with: nil)
        XCTAssertEqual(note.strokeCount, 1)
    }

}


/// Synthetic UIKit API callbacks only: no injected OS event or real Pencil/latency claim.
private final class SyntheticInkTouch: UITouch {
    var position: CGPoint
    var seconds: TimeInterval
    init(_ x: Double, _ seconds: TimeInterval) { self.position = CGPoint(x: x, y: 100); self.seconds = seconds; super.init() }
    override var timestamp: TimeInterval { seconds }
    override var type: UITouch.TouchType { .pencil }
    var forceValue: CGFloat = 0.5
    var maximumValue: CGFloat = 1
    override var force: CGFloat { forceValue }
    override var maximumPossibleForce: CGFloat { maximumValue }
    override func location(in view: UIView?) -> CGPoint { position }
}
private final class SyntheticInkEvent: UIEvent {
    var real: [SyntheticInkTouch]?
    var predictionReads = 0
    override func coalescedTouches(for touch: UITouch) -> [UITouch]? { real }
    override func predictedTouches(for touch: UITouch) -> [UITouch]? {
        predictionReads += 1; return [SyntheticInkTouch(199, 1)]
    }
}
