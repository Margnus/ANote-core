import XCTest
import UIKit
import NoteCanvasPrototype
@testable import SketchHost

final class SketchHostTests: XCTestCase {
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
        if case .refused(let reason) = host.offer(.down, points: [point(50, 5)]) { XCTAssertEqual(reason, .readOnly) }
        else { XCTFail("Read-only input accepted") }
        XCTAssertTrue(host.notebook.snapshot() === completed)
        XCTAssertNil(host.setReadOnly(false)); XCTAssertNil(host.edit(PreviewEditUndo.shared))
        XCTAssertEqual(host.notebook.strokeCount, 0); XCTAssertNil(host.edit(PreviewEditRedo.shared))
        XCTAssertEqual(host.notebook.pointCount(index: 0), 3)
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
