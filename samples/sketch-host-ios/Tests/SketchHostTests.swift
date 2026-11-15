import XCTest
import UIKit
import NoteCanvasPrototype
@testable import SketchHost

final class SketchHostTests: XCTestCase {
    @MainActor func testPublicPrepareIsObservationAndBridgeRechecksChangedState() throws {
        let note = try session().notebook, root = note.snapshot()
        let invalid: [(Int32, PreviewFailureKind)] = [(-1, .invalidInput), (0, .invalidInput),
                                                     (1025, .inputBatchCapacity), (Int32.max, .inputBatchCapacity)]
        for (count, kind) in invalid {
            let refused = try XCTUnwrap(note.prepareRealBatch(phase: .down, count: count))
            XCTAssertEqual(refused.failure?.kind, kind); XCTAssertEqual(refused.acceptedSamples, 0)
            XCTAssertTrue(note.snapshot() === root); XCTAssertFalse(note.hasActiveStroke)
            XCTAssertEqual(note.inputReservedByteCharge, 0)
        }
        func offerDown() -> PreviewInputResult {
            ApplePreviewInput.shared.offer(notebook: note, phase: .down, eventTimeMicros: 1,
                xs: [10.0].withUnsafeBytes { Data($0) }, ys: [20.0].withUnsafeBytes { Data($0) },
                pressures: [Float(0.5)].withUnsafeBytes { Data($0) }, times: [Int64(1)].withUnsafeBytes { Data($0) })
        }
        for _ in 0..<3 { XCTAssertNil(note.prepareRealBatch(phase: .down, count: 1)) }
        XCTAssertNil(note.activeInputOperationId); XCTAssertEqual(note.inputReservedByteCharge, 0)
        XCTAssertTrue(note.setReadOnly(value: true))
        let readonly = offerDown()
        XCTAssertEqual(readonly.disposition, .rejected); XCTAssertEqual(readonly.failure?.kind, .readOnly)
        XCTAssertEqual(readonly.acceptedSamples, 0); XCTAssertTrue(note.snapshot() === root)
        XCTAssertTrue(note.setReadOnly(value: false)); XCTAssertNil(note.prepareRealBatch(phase: .down, count: 1))
        let admittedCapture = note.captureRecovery() // Caller retains immutable responsibility before terminal retire.
        XCTAssertTrue(note.retire())
        let closed = offerDown()
        XCTAssertEqual(closed.disposition, .rejected); XCTAssertEqual(closed.failure?.kind, .closed)
        XCTAssertEqual(closed.acceptedSamples, 0); XCTAssertFalse(note.hasActiveStroke)
        XCTAssertEqual(note.inputReservedByteCharge, 0); XCTAssertTrue(admittedCapture.root === root)
    }

    @MainActor func testPublicPendingRetirementRequiresExactCheckpointAndHostIOReceipt() throws {
        // Bytes produced by the original Android Store/worker, not a hand-seeded pending command.
        let bytes = try XCTUnwrap(Data(base64Encoded: "TkNSRUNPVkVSWQkxCkQJcHJvY2Vzcy1yZWNvdmVyeQlwcm9jZXNzIGNoZWNrcG9pbnQJMQkwCTEKOTMJMjA5CTAwNzAwMDcyMDA2ZjAwNjMwMDY1MDA3MzAwNzMwMDJkMDA3MzAwNjUwMDY1MDA2NDAwMmQwMDZmMDA3MDAwMmQwMDMyCk5DUFJFVklFVwkyCkQJcHJvY2Vzcy1yZWNvdmVyeQlwcm9jZXNzIGNoZWNrcG9pbnQJMQkwCTEKRwlwYWdlCWxheWVyCTU5NS4wCTg0Mi4wCUJMQU5LCTAKRU5ECk5DUFJFVklFVwkyCkQJcHJvY2Vzcy1yZWNvdmVyeQkJMQkxCTAKRwlwYWdlCWxheWVyCTU5NS4wCTg0Mi4wCUJMQU5LCTAKUwlwcm9jZXNzLXNlZWQtaW5rLTMJbGF5ZXIJMQlNT05PTElORQkxCS0xNDMzOTUxMwkyLjUJMwkxLjAJMC4wCTAuMAkxLjAJMC4wCTAuMApQCTEuMjUJMi41CTAuMTI1CTcKUAkzLjAJNC4wCTAuNjI1CTkKUAk4LjAJNi4wCTEuMAkxMApFTkQKUkVDT1ZFUlktRU5ECg=="))
        let text = String(decoding: bytes, as: UTF8.self)
        let reading = PreviewRecoveryCodec.shared.read(text: text)
        XCTAssertNil(reading.failure)
        let checkpoint = try XCTUnwrap(reading.snapshot)
        let budget = try DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8*1024*1024,
            journalEntries: 128, journalBytes: 8*1024*1024, identityBytes: 2*1024*1024,
            historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024)
        func open(_ value: PagedPreviewSnapshot, nonce: String) throws -> PagedPreviewNotebook {
            let result = PagedPreviewNotebook.companion.openRecovery(snapshot: value, sessionNonce: nonce,
                budget: budget, ownerCheck: { precondition(Thread.isMainThread) })
            XCTAssertNil(result.failure)
            return try XCTUnwrap(result.notebook)
        }
        let note = try open(checkpoint, nonce: "swift-checkpoint")
        let root = note.snapshot(), pending = try XCTUnwrap(note.pendingStroke())
        XCTAssertTrue(note.hasPendingStroke); XCTAssertEqual(note.totalStrokeCount, 0)
        let charge = note.inputReservedByteCharge
        let raw = try XCTUnwrap(pending.command as? CanvasCommandAddStroke)
        let ink = try XCTUnwrap(note.pendingInk())
        XCTAssertTrue(ink.samples === raw.samples); XCTAssertTrue(ink.brush === raw.target.brush)
        XCTAssertEqual(ink.samples.size, 3); XCTAssertEqual(ink.samples.x(index: 0), 1.25)
        XCTAssertEqual(note.inputReservedByteCharge, charge)
        XCTAssertTrue(note.snapshot() === root); XCTAssertTrue(note.pendingStroke() === pending)
        XCTAssertFalse(note.retire()); XCTAssertEqual(note.lastFailure?.kind, .pendingStroke)
        XCTAssertFalse(note.retireAfterRecovery(snapshot: note.capture()))
        let foreign = try XCTUnwrap(PreviewRecoveryCodec.shared.read(text: text).snapshot)
        XCTAssertTrue(checkpoint.sameContent(other: foreign))
        XCTAssertTrue(note.captureRecovery().sameContent(other: foreign))
        XCTAssertFalse(note.capture().sameContent(other: foreign)) // Pending is part of content responsibility.
        XCTAssertFalse(note.retireAfterRecovery(snapshot: foreign)) // Value equality is not the original handoff identity.
        XCTAssertTrue(note.snapshot() === root); XCTAssertTrue(note.pendingStroke() === pending)
        let exact = note.captureRecovery()
        XCTAssertEqual(try exact.encode(), text)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".ncp")
        defer { try? FileManager.default.removeItem(at: file) }
        // Synchronous test fixture IO only; not a product UI storage path or power-loss guarantee.
        try bytes.write(to: file, options: .atomic)
        XCTAssertEqual(try Data(contentsOf: file), bytes)
        XCTAssertTrue(note.retireAfterRecovery(snapshot: exact))
        XCTAssertTrue(note.hasPendingStroke); XCTAssertFalse(note.retryPendingStroke())
        XCTAssertTrue(note.pendingInk()?.samples === ink.samples)
        XCTAssertEqual(note.lastFailure?.kind, .closed); XCTAssertEqual(try exact.encode(), text)
        let reopened = try open(exact, nonce: "swift-reopened")
        XCTAssertTrue(reopened.hasPendingStroke); XCTAssertTrue(reopened.pendingInk()?.samples === ink.samples)
        XCTAssertTrue(reopened.retryPendingStroke()); XCTAssertNil(reopened.pendingInk())
        XCTAssertTrue(reopened.stroke(index: 0).samples === ink.samples)
        XCTAssertTrue(reopened.retryPendingStroke()); XCTAssertEqual(reopened.totalStrokeCount, 1)
        XCTAssertEqual(reopened.pointCount(index: 0), 3); XCTAssertEqual(reopened.changeVersion, 2)
        XCTAssertEqual(try exact.encode(), text)
        let committed = reopened.captureRecovery(), canonical = try committed.encode()
        let decoded = try XCTUnwrap(PreviewRecoveryCodec.shared.read(text: canonical).snapshot)
        XCTAssertTrue(committed.sameContent(other: decoded))
        let changedTitle = canonical.replacingOccurrences(of: "process checkpoint", with: "same revision different title")
        XCTAssertNotEqual(changedTitle, canonical)
        let other = try XCTUnwrap(PreviewRecoveryCodec.shared.read(text: changedTitle).snapshot)
        XCTAssertEqual(committed.version, other.version)
        XCTAssertFalse(committed.sameContent(other: other))
        XCTAssertEqual(try committed.encode(), canonical)
        try Data(canonical.utf8).write(to: file, options: .atomic)
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), canonical)
        XCTAssertTrue(reopened.retire()); XCTAssertEqual(try committed.encode(), canonical)
    }

    @MainActor func testPublicReadersRefuseWithoutPartialRootOrCapabilityConversion() throws {
        let host = try session(), note = host.notebook
        XCTAssertTrue(host.offer(.down, points: [point(10, 1)]).accepted)
        XCTAssertTrue(host.offer(.up, points: [point(20, 2)]).accepted)
        let captured = note.captureRecovery(), text = try captured.encode(), originalRoot = note.snapshot()
        let reading = PreviewDocumentReader.shared.read(text: text)
        XCTAssertNil(reading.failure)
        let root = try XCTUnwrap(reading.root)
        let accepted = PreviewDocumentReader.shared.accept(root: root)
        XCTAssertNil(accepted.failure); XCTAssertTrue(accepted.root === root)
        for malformed in ["", "NCPREVIEW\t99\n", String(text.dropLast(4)), text + "trailing"] {
            let refused = PreviewDocumentReader.shared.read(text: malformed)
            XCTAssertNil(refused.root); XCTAssertEqual(refused.failure, .invalidOrUnsupportedFormat)
        }
        let invalidRecovery = PreviewRecoveryCodec.shared.read(text: "NCRECOVERY\t99\n")
        XCTAssertNil(invalidRecovery.snapshot); XCTAssertEqual(invalidRecovery.failure, .invalidOrUnsupportedFormat)
        for kind in ["PRESSURE", "HIGHLIGHTER"] {
            let unsupported = text.replacingOccurrences(of: "MONOLINE", with: kind)
            XCTAssertNotEqual(unsupported, text)
            let refused = PreviewDocumentReader.shared.read(text: unsupported)
            XCTAssertNil(refused.root); XCTAssertEqual(refused.failure, .unsupportedPreviewContent)
            // Trusted canonical decode creates a valid non-preview fixture, not a recommended raw SDK read path.
            let retained = try PagedPreviewCodec.shared.decode(text: unsupported)
            let before = try PagedPreviewCodec.shared.encode(root: retained)
            let adopted = PreviewDocumentReader.shared.accept(root: retained)
            XCTAssertNil(adopted.root); XCTAssertEqual(adopted.failure, .unsupportedPreviewContent)
            XCTAssertEqual(try PagedPreviewCodec.shared.encode(root: retained), before)
        }
        XCTAssertTrue(note.snapshot() === originalRoot)
        XCTAssertEqual(try captured.encode(), text); XCTAssertEqual(note.totalStrokeCount, 1)
        XCTAssertTrue(note.retire()) // Captured immutable responsibility remains with this test host.
    }

    private let tools = [SketchTool(label: "blue", argb: Int32(bitPattern: 0xff2563eb), width: 4),
                         SketchTool(label: "red", argb: Int32(bitPattern: 0xffdc2626), width: 7)]
    @MainActor private func session(background: @escaping (CGContext, CGRect) -> Void = { c, r in c.setFillColor(UIColor.white.cgColor); c.fill(r) }) throws -> SketchSession {
        guard case .opened(let session) = SketchSession.create(tools: tools, background: background) else { throw NSError(domain: "SampleCreation", code: 1) }
        return session
    }
    private func point(_ x: Double, _ time: Int64) -> SketchPoint { SketchPoint(x: x, y: 100, pressure: 0.5, time: time) }
    @MainActor func testEveryToolAndUnsupportedEditRejectBeforeMutatingCore() throws {
        for width in [Double.nan, Double.infinity, 0.49, 16.01] {
            let bad = [tools[0], SketchTool(label: "invalid", argb: 1, width: width)]
            guard case .refused(let reason) = SketchSession.create(tools: bad, background: { _, _ in }) else {
                XCTFail("A later invalid tool was accepted"); continue
            }
            XCTAssertEqual(reason, .invalidTool)
        }
        let valid = [SketchTool(label: "minimum", argb: 1, width: 0.5), SketchTool(label: "maximum", argb: 2, width: 16)]
        guard case .opened(let boundary) = SketchSession.create(tools: valid, background: { _, _ in }) else {
            return XCTFail("Valid endpoint tool widths were refused")
        }
        XCTAssertNil(boundary.selectTool(1)); XCTAssertNil(boundary.retire())
        let host = try session(), note = host.notebook, before = note.snapshot()
        XCTAssertTrue(host.offer(.down, points: [point(10, 1), point(20, 2)]).accepted)
        let operation = note.activeInputOperationId, bytes = note.inputReservedByteCharge
        let unsupported: [PreviewEdit] = [PreviewEditRename(title: "unsupported"),
            PreviewEditSetPaper(width: 500, height: 600, paper: .grid), PreviewEditSelectPen(argb: 1, width: 3),
            PreviewEditInsertPage.shared, PreviewEditCopyPage.shared, PreviewEditDeletePage.shared,
            PreviewEditSelectPage(pageId: "missing"), PreviewEditMovePage(beforePageId: nil)]
        for request in unsupported {
            XCTAssertEqual(host.edit(request), .unsupportedEdit)
            XCTAssertTrue(note.snapshot() === before); XCTAssertTrue(note.hasActiveStroke)
            XCTAssertEqual(note.activeInputOperationId, operation); XCTAssertEqual(note.inputReservedByteCharge, bytes)
            XCTAssertNil(note.lastFailure)
        }
        XCTAssertTrue(host.offer(.up, points: [point(30, 3)]).accepted)
        XCTAssertEqual(note.pointCount(index: 0), 3)
        XCTAssertNil(host.edit(PreviewEditUndo.shared)); XCTAssertEqual(note.totalStrokeCount, 0)
        XCTAssertNil(host.edit(PreviewEditRedo.shared)); XCTAssertEqual(note.totalStrokeCount, 1)
        XCTAssertNil(host.retire())
    }
    @MainActor func testValidatedCreationAndHostInjectedToolsUseOriginalNotebook() throws {
        if case .coreFailure(let reason) = SketchSession.create(nonce: "bad nonce", tools: tools, background: { _, _ in }) {
            XCTAssertEqual(reason, .invalidSessionNonce)
        } else { XCTFail("Invalid nonce created a host") }
        if case .refused(let refusal) = SketchSession.create(tools: [], background: { _, _ in }) {
            XCTAssertEqual(refusal, .invalidTool)
        } else { XCTFail("Empty tools created a host") }
        var injected = tools
        guard case .opened(let host) = SketchSession.create(tools: injected, background: { _, _ in }) else { return XCTFail("Valid creation refused") }
        let original = host.notebook
        injected.removeAll(); XCTAssertEqual(host.tools.count, 2)
        XCTAssertNil(host.selectTool(1))
        XCTAssertTrue(host.offer(.down, points: [point(10, 1)]).accepted)
        XCTAssertTrue(host.offer(.up, points: [point(20, 2)]).accepted)
        XCTAssertTrue(host.notebook === original)
        XCTAssertEqual(original.stroke(index: 0).brush.argb, tools[1].argb)
        XCTAssertEqual(original.stroke(index: 0).brush.width, 7)
        XCTAssertEqual(original.pointCount(index: 0), 2)
    }
    @MainActor func testReadOnlyInvalidToolAndRejectedBatchKeepAcceptedPrefix() throws {
        let host = try session(), before = host.notebook.snapshot()
        XCTAssertEqual(host.edit(PreviewEditRename(title: "unsupported")), .unsupportedEdit)
        if case .refused(let reason) = host.offer(.cancel, points: []) { XCTAssertEqual(reason, .invalidPhase) }
        else { XCTFail("Synthetic cancel phase admitted") }
        XCTAssertTrue(host.offer(.down, points: [point(10, 1), point(20, 2)]).accepted)
        XCTAssertEqual(host.selectTool(-1), .invalidTool)
        XCTAssertEqual(host.selectTool(1), .activeInk)
        XCTAssertEqual(host.setReadOnly(true), .activeInk)
        XCTAssertEqual(host.retryPending(), .activeInk)
        let bad = host.offer(.move, points: [point(.nan, 3)])
        guard case .core(let result) = bad else { return XCTFail("Core refusal not forwarded") }
        XCTAssertFalse(result.admitted); XCTAssertEqual(result.acceptedSamples, 0); XCTAssertEqual(result.activeSamples, 2)
        XCTAssertTrue(host.notebook.snapshot() === before)
        let excessive = host.offer(.move, points: Array(repeating: point(30, 3), count: 1025))
        if case .refused(let reason) = excessive { XCTAssertEqual(reason, .batchCapacity) } else { XCTFail("Oversize producer admitted") }
        XCTAssertTrue(host.offer(.up, points: [point(30, 3)]).accepted)
        XCTAssertEqual(host.notebook.pointCount(index: 0), 3)
        let completed = host.notebook.snapshot()
        XCTAssertNil(host.setReadOnly(true)); XCTAssertEqual(host.edit(PreviewEditUndo.shared), .readOnly)
        XCTAssertTrue(host.notebook.isReadOnly)
        let directUndo = host.notebook.edit(request: PreviewEditUndo.shared)
        XCTAssertFalse(directUndo.accepted); XCTAssertEqual(directUndo.failure?.kind, .readOnly)
        if case .refused(let reason) = host.offer(.down, points: [point(50, 5)]) { XCTAssertEqual(reason, .readOnly) }
        else { XCTFail("Read-only input accepted") }
        XCTAssertTrue(host.notebook.snapshot() === completed)
        XCTAssertNil(host.setReadOnly(false)); XCTAssertNil(host.edit(PreviewEditUndo.shared))
        XCTAssertEqual(host.notebook.strokeCount, 0); XCTAssertNil(host.edit(PreviewEditRedo.shared))
        XCTAssertEqual(host.notebook.pointCount(index: 0), 3)
        // Another same-owner consumer changes the original mode; the host must observe it.
        XCTAssertTrue(host.notebook.setReadOnly(value: true)); XCTAssertTrue(host.readOnly)
        XCTAssertEqual(host.selectTool(1), .readOnly)
        XCTAssertTrue(host.notebook.setReadOnly(value: false)); XCTAssertFalse(host.readOnly)
        XCTAssertNil(host.selectTool(1))
    }
    @MainActor func testCoalescedUIKitAndResizeReplacementKeepCanonicalSession() throws {
        let host = try session(), view = SketchCanvas(session: host)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 595, height: 842))
        let controller = UIViewController(); window.rootViewController = controller; window.makeKeyAndVisible()
        controller.view.addSubview(view)
        defer { window.isHidden = true; window.rootViewController = nil }
        view.frame = CGRect(x: 0, y: 0, width: 595, height: 842); view.layoutIfNeeded()
        let main = SampleTouch(30, 0.003), event = SampleEvent()
        event.real = [SampleTouch(10, 0.001), SampleTouch(20, 0.002), SampleTouch(30, 0.003)]
        view.touchesBegan([main], with: event); event.real![0].position.x = 199
        main.position.x = 50; main.seconds = 0.005
        event.real = [SampleTouch(40, 0.004), SampleTouch(50, 0.005)]; view.touchesMoved([main], with: event)
        main.position.x = 70; main.seconds = 0.007
        event.real = [SampleTouch(60, 0.006), SampleTouch(70, 0.007)]; view.touchesEnded([main], with: event)
        let note = host.notebook, root = note.snapshot()
        XCTAssertEqual(note.pointCount(index: 0), 7)
        for i in 0..<7 {
            XCTAssertEqual(note.pointX(index: 0, point: Int32(i)).bitPattern, Double((i + 1) * 10).bitPattern)
            XCTAssertEqual(note.pointY(index: 0, point: Int32(i)), 100)
            XCTAssertEqual(note.stroke(index: 0).samples.pressure(index: Int32(i)), 0.5)
            XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: Int32(i)), Int64((i + 1) * 1000))
        }
        XCTAssertEqual(event.predictionReads, 0)
        main.seconds = 1; view.touchesBegan([main], with: nil); XCTAssertTrue(note.hasActiveStroke)
        view.frame.size = CGSize(width: 300, height: 500); view.layoutIfNeeded()
        XCTAssertFalse(note.hasActiveStroke); XCTAssertTrue(note.snapshot() === root)
        XCTAssertNil(host.selectTool(1)); XCTAssertNil(host.setReadOnly(true))
        let replacement = SketchCanvas(session: host)
        view.removeFromSuperview(); controller.view.addSubview(replacement)
        replacement.frame = controller.view.bounds; replacement.layoutIfNeeded()
        XCTAssertNil(view.window); XCTAssertTrue(replacement.window === window)
        XCTAssertTrue(replacement.session === host); XCTAssertTrue(replacement.session.notebook === note)
        XCTAssertTrue(replacement.session.notebook.snapshot() === root)
        XCTAssertEqual(host.toolIndex, 1); XCTAssertTrue(host.readOnly)
    }
    @MainActor func testSiblingCanvasLifecycleAndLateCallbacksCannotTakeAnotherInput() throws {
        let host = try session(), a = SketchCanvas(session: host), b = SketchCanvas(session: host)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 595, height: 842))
        let controller = UIViewController(); window.rootViewController = controller; window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil }
        controller.view.addSubview(a); a.frame = window.bounds; a.layoutIfNeeded()
        let note = host.notebook, root = note.snapshot(), first = SampleTouch(10, 0.001)
        a.touchesBegan([first], with: nil)
        let operation = note.activeInputOperationId, charge = note.inputReservedByteCharge
        XCTAssertNotNil(operation); XCTAssertGreaterThan(charge, 0)
        controller.view.addSubview(b); b.frame = window.bounds; b.layoutIfNeeded()
        XCTAssertEqual(note.activeInputOperationId, operation); XCTAssertEqual(note.inputReservedByteCharge, charge)
        b.touchesCancelled([], with: nil); b.removeFromSuperview()
        XCTAssertEqual(note.activeInputOperationId, operation); XCTAssertEqual(note.inputReservedByteCharge, charge)
        XCTAssertTrue(note.snapshot() === root)
        guard note.hasActiveStroke else { return XCTFail("Sibling lifecycle cancelled accepted DOWN") }
        first.position.x = 20; first.seconds = 0.002; a.touchesEnded([first], with: nil)
        XCTAssertEqual(note.totalStrokeCount, 1)
        XCTAssertEqual(note.pointCount(index: 0), 2)
        let old = SampleTouch(30, 0.003); a.touchesBegan([old], with: nil)
        XCTAssertNil(host.cancelUnfinished()) // Explicit same-owner cancellation ends A's operation.
        controller.view.addSubview(b); b.frame = window.bounds; b.layoutIfNeeded()
        let next = SampleTouch(40, 0.004); b.touchesBegan([next], with: nil)
        let nextOperation = note.activeInputOperationId, nextCharge = note.inputReservedByteCharge
        XCTAssertNotNil(nextOperation); XCTAssertNotEqual(nextOperation, operation)
        old.position.x = 50; old.seconds = 0.005; a.touchesMoved([old], with: nil)
        old.position.x = 60; old.seconds = 0.006; a.touchesEnded([old], with: nil)
        a.removeFromSuperview()
        XCTAssertEqual(note.activeInputOperationId, nextOperation); XCTAssertEqual(note.inputReservedByteCharge, nextCharge)
        next.position.x = 70; next.seconds = 0.007; b.touchesEnded([next], with: nil)
        guard note.totalStrokeCount == 2 else { return XCTFail("Late A callback consumed B's operation") }
        XCTAssertEqual(note.pointCount(index: 1), 2)
        XCTAssertEqual(note.pointX(index: 1, point: 0), 40)
        XCTAssertEqual(note.pointX(index: 1, point: 1), 70)
        XCTAssertFalse(note.hasActiveStroke); XCTAssertFalse(note.hasPendingStroke)
        XCTAssertEqual(note.inputReservedByteCharge, 0)
    }
    @MainActor func testBorrowedBackgroundDrawsActualPixels() throws {
        var draws = 0
        let host = try session { context, page in draws += 1; context.setFillColor(UIColor.green.cgColor); context.fill(page) }
        let view = SketchCanvas(session: host); view.frame = CGRect(x: 0, y: 0, width: 64, height: 64); view.layoutIfNeeded()
        var rgba = [UInt8](repeating: 0, count: 64*64*4)
        rgba.withUnsafeMutableBytes { bytes in
            let context = CGContext(data: bytes.baseAddress, width: 64, height: 64, bitsPerComponent: 8, bytesPerRow: 64*4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)!
            UIGraphicsPushContext(context); view.draw(view.bounds); UIGraphicsPopContext()
        }
        XCTAssertEqual(draws, 1); XCTAssertEqual(Array(rgba[0..<4]), [0,255,0,255])
        XCTAssertEqual(host.notebook.totalStrokeCount, 0)
    }
    @MainActor func testBackgroundMutationKeepsInkVisibleAndRestoresBorrowedCaller() throws {
        let host = try session { context, paper in
            context.setFillColor(UIColor.white.cgColor); context.fill(paper)
            context.move(to: CGPoint(x: 200, y: 200)); context.addLine(to: CGPoint(x: 240, y: 200))
            context.translateBy(x: 1000, y: 1000)
            context.clip(to: CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        XCTAssertTrue(host.offer(.down, points: [point(100, 1)]).accepted)
        XCTAssertTrue(host.offer(.up, points: [point(100, 2)]).accepted)
        let root = host.notebook.snapshot(), view = SketchCanvas(session: host)
        view.frame = CGRect(x: 0, y: 0, width: 595, height: 842); view.layoutIfNeeded()
        var rgba = [UInt8](repeating: 0, count: 595 * 842 * 4)
        try rgba.withUnsafeMutableBytes { bytes in
            let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: 595, height: 842,
                bitsPerComponent: 8, bytesPerRow: 595 * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.translateBy(x: 0, y: 842); context.scaleBy(x: 1, y: -1) // UIKit's caller coordinates.
            context.clip(to: CGRect(x: 0, y: 0, width: 500, height: 700)); context.setLineWidth(9)
            context.move(to: CGPoint(x: 3, y: 4)); context.addLine(to: CGPoint(x: 30, y: 40))
            let transform = context.ctm, clip = context.boundingBoxOfClipPath
            let path = try XCTUnwrap(context.path)
            UIGraphicsPushContext(context); view.draw(view.bounds); UIGraphicsPopContext()
            XCTAssertEqual(context.ctm, transform); XCTAssertEqual(context.boundingBoxOfClipPath, clip)
            XCTAssertEqual(context.path?.boundingBox, path.boundingBox)
            XCTAssertEqual(context.path?.currentPoint, path.currentPoint)
        }
        func pixel(_ x: Int, _ y: Int) -> [UInt8] {
            let offset = (y * 595 + x) * 4; return Array(rgba[offset..<offset + 4])
        }
        XCTAssertEqual(pixel(100, 100), [0x25, 0x63, 0xeb, 255])
        XCTAssertEqual(pixel(220, 200), [255, 255, 255, 255]) // Background's leftover path was not stroked.
        XCTAssertEqual(pixel(15, 20), [255, 255, 255, 255]) // Caller's path was not stroked as ink.
        XCTAssertTrue(host.notebook.snapshot() === root); XCTAssertEqual(host.notebook.pointCount(index: 0), 2)
    }
    @MainActor func testForeignOwnerRefusesBeforeCoreAndImmutableCheckpointEncodesOffOwner() async throws {
        let host = try session()
        XCTAssertTrue(host.offer(.down, points: [point(10, 1)]).accepted)
        XCTAssertTrue(host.offer(.up, points: [point(20, 2)]).accepted)
        let original = host.notebook.snapshot(), checkpoint = host.notebook.capture()
        XCTAssertEqual(host.notebook.pointCount(index: 0), 2)
        let probe = ForeignProbe(host: host, checkpoint: checkpoint)
        Thread(target: probe, selector: #selector(ForeignProbe.run), object: nil).start()
        let deadline = Date().addingTimeInterval(3)
        while probe.outcome == nil && Date() < deadline { try await Task.sleep(nanoseconds: 10_000_000) }
        let outcome = try XCTUnwrap(probe.outcome)
        XCTAssertEqual(outcome.0, .wrongOwner); XCTAssertEqual(host.toolIndex, 0)
        let encoded = try outcome.1.get()
        XCTAssertEqual(encoded, try checkpoint.encode()); XCTAssertTrue(host.notebook.snapshot() === original)
    }
    @MainActor func testExplicitCancelAndRetirementKeepCompletedInk() throws {
        let host = try session()
        XCTAssertTrue(host.offer(.down, points: [point(10, 1)]).accepted)
        XCTAssertTrue(host.offer(.up, points: [point(20, 2)]).accepted)
        let committed = host.notebook.snapshot()
        XCTAssertTrue(host.offer(.down, points: [point(30, 3)]).accepted)
        XCTAssertEqual(host.retire(), .activeInk); XCTAssertTrue(host.notebook.hasActiveStroke)
        XCTAssertNil(host.cancelUnfinished()); XCTAssertTrue(host.notebook.snapshot() === committed)
        XCTAssertEqual(host.notebook.strokeCount, 1); XCTAssertNil(host.retire())
        guard case .core(let closed) = host.offer(.down, points: [point(50, 5)]) else { return XCTFail("Core closed result not forwarded") }
        XCTAssertFalse(closed.admitted); XCTAssertEqual(closed.failure?.kind, .closed)
        XCTAssertTrue(host.notebook.snapshot() === committed)
        XCTAssertEqual(host.setReadOnly(true), .coreRejected)
        XCTAssertFalse(host.readOnly)
    }
}
private final class SampleTouch: UITouch {
    var position: CGPoint; var seconds: TimeInterval
    init(_ x: Double, _ seconds: TimeInterval) { position = CGPoint(x: x, y: 100); self.seconds = seconds; super.init() }
    override var timestamp: TimeInterval { seconds }
    override var type: UITouch.TouchType { .pencil }
    override var force: CGFloat { 0.5 }
    override var maximumPossibleForce: CGFloat { 1 }
    override func location(in view: UIView?) -> CGPoint { position }
}
private final class SampleEvent: UIEvent {
    var real: [SampleTouch]?; var predictionReads = 0
    override func coalescedTouches(for touch: UITouch) -> [UITouch]? { real }
    override func predictedTouches(for touch: UITouch) -> [UITouch]? { predictionReads += 1; return [SampleTouch(199, 99)] }
}

/// Only probe results use this lock. It never transfers ownership of the mutable core session.
private final class ForeignProbe: NSObject {
    let host: SketchSession; let checkpoint: PagedPreviewSnapshot
    private let lock = NSLock()
    private var value: (SketchRefusal?, Result<String, Error>)?
    init(host: SketchSession, checkpoint: PagedPreviewSnapshot) { self.host = host; self.checkpoint = checkpoint }
    var outcome: (SketchRefusal?, Result<String, Error>)? { lock.lock(); defer { lock.unlock() }; return value }
    @objc func run() {
        let refusal = host.selectTool(1)
        let encoded = Result { try checkpoint.encode() }
        lock.lock(); value = (refusal, encoded); lock.unlock()
    }
}
