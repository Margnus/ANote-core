import Foundation
import CoreGraphics
import UIKit
import Combine
import NoteCanvasPrototype

struct SketchTool { let label: String; let argb: Int32; let width: Double }
struct SketchPoint { let x: Double; let y: Double; let pressure: Float; let time: Int64 }
enum SketchRefusal: Equatable { case wrongOwner, readOnly, invalidTool, invalidPhase, unsupportedEdit, batchCapacity, activeInk, coreRejected }
enum SketchAdmission {
    case core(PreviewInputResult)
    case refused(SketchRefusal)
    var accepted: Bool { if case .core(let result) = self { return result.admitted }; return false }
}
enum SketchCreation {
    case opened(SketchSession)
    case refused(SketchRefusal)
    case coreFailure(PreviewOpenFailure)
}

/// Host policy only. The notebook remains the sole document authority; no IO or worker is owned here.
final class SketchSession: ObservableObject {
    let notebook: PagedPreviewNotebook
    let tools: [SketchTool]
    // CGContext is borrowed only for one draw. The resource must not retain it or the view.
    let background: (CGContext, CGRect) -> Void
    var readOnly: Bool { notebook.isReadOnly }
    private(set) var toolIndex = 0
    private init(_ notebook: PagedPreviewNotebook, tools: [SketchTool], background: @escaping (CGContext, CGRect) -> Void) {
        self.notebook = notebook; self.tools = tools; self.background = background
    }
    static func create(id: String = UUID().uuidString, title: String = "内存草图", nonce: String = UUID().uuidString,
                       tools: [SketchTool], background: @escaping (CGContext, CGRect) -> Void) -> SketchCreation {
        guard Thread.isMainThread else { return .refused(.wrongOwner) }
        guard !tools.isEmpty, tools.allSatisfy({ $0.width.isFinite && (0.5...16).contains($0.width) })
        else { return .refused(.invalidTool) }
        let budget = DocumentBudget.companion.validated(maxPages: 128, maxObjects: 5000, liveBytes: 8*1024*1024,
            journalEntries: 128, journalBytes: 8*1024*1024, identityBytes: 2*1024*1024,
            historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024).budget! // Fixed sketch limits.
        let opened = PagedPreviewNotebook.companion.createValidated(documentId: id, title: title, sessionNonce: nonce,
            width: 595, height: 842, paper: .blank, budget: budget,
            ownerCheck: { precondition(Thread.isMainThread, "Sketch core owner is the main thread") })
        guard let notebook = opened.notebook else { return .coreFailure(opened.failure!) }
        let session = SketchSession(notebook, tools: tools, background: background)
        precondition(session.selectTool(0) == nil) // Trusted values already checked above.
        return .opened(session)
    }
    static func standard() -> SketchSession {
        let tools = [SketchTool(label: "蓝笔", argb: Int32(bitPattern: 0xff2563eb), width: 4),
                     SketchTool(label: "红笔", argb: Int32(bitPattern: 0xffdc2626), width: 7)]
        guard case .opened(let session) = create(tools: tools, background: { context, page in
            context.setFillColor(UIColor(red: 1, green: 0.985, blue: 0.92, alpha: 1).cgColor); context.fill(page)
            context.setStrokeColor(UIColor.lightGray.cgColor); context.setLineWidth(0.5)
            var y: CGFloat = 32
            while y < page.height { context.move(to: CGPoint(x: 0, y: y)); context.addLine(to: CGPoint(x: page.width, y: y)); y += 32 }
            context.strokePath()
        }) else { preconditionFailure("Trusted sketch creation failed") }
        return session
    }
    func selectTool(_ index: Int) -> SketchRefusal? {
        guard Thread.isMainThread else { return .wrongOwner }
        guard !readOnly else { return .readOnly }
        guard tools.indices.contains(index) else { return .invalidTool }
        guard !notebook.hasActiveStroke && !notebook.hasPendingStroke else { return .activeInk }
        let tool = tools[index]
        guard notebook.edit(request: PreviewEditSelectPen(argb: tool.argb, width: tool.width, kind: .monoline)).accepted else { return .coreRejected }
        toolIndex = index; return nil
    }
    func setReadOnly(_ value: Bool) -> SketchRefusal? {
        guard Thread.isMainThread else { return .wrongOwner }
        guard !notebook.hasActiveStroke && !notebook.hasPendingStroke else { return .activeInk }
        return notebook.setReadOnly(value: value) ? nil : .coreRejected
    }
    func edit(_ request: PreviewEdit) -> SketchRefusal? {
        guard Thread.isMainThread else { return .wrongOwner }
        guard !readOnly else { return .readOnly }
        guard request is PreviewEditUndo || request is PreviewEditRedo else { return .unsupportedEdit }
        return notebook.edit(request: request).accepted ? nil : .coreRejected
    }
    private func data<T>(_ points: [SketchPoint], scalar: (SketchPoint) -> T) -> Data {
        var result = Data(count: points.count * MemoryLayout<T>.stride)
        result.withUnsafeMutableBytes { (out: UnsafeMutableRawBufferPointer) in
            for (i, point) in points.enumerated() {
                var value = scalar(point)
                withUnsafeBytes(of: &value) { bytes in
                    out.baseAddress!.advanced(by: i * MemoryLayout<T>.stride).copyMemory(from: bytes.baseAddress!, byteCount: bytes.count)
                }
            }
        }
        return result
    }
    func offer(_ phase: PointerPhase, points: [SketchPoint]) -> SketchAdmission {
        guard Thread.isMainThread else { return .refused(.wrongOwner) }
        guard !readOnly else { return .refused(.readOnly) }
        guard phase != .cancel else { return .refused(.invalidPhase) } // Explicit cancelUnfinished has no synthetic event timestamp.
        // Host capacity refusal precedes buffer allocation; core results below are forwarded unchanged.
        guard points.count <= Int(notebook.inputBatchLimit) else { return .refused(.batchCapacity) }
        return .core(ApplePreviewInput.shared.offer(notebook: notebook, phase: phase, eventTimeMicros: points.last?.time ?? 0,
            xs: data(points) { $0.x }, ys: data(points) { $0.y },
            pressures: data(points) { $0.pressure }, times: data(points) { $0.time }))
    }
    func cancelUnfinished() -> SketchRefusal? {
        guard Thread.isMainThread else { return .wrongOwner }
        notebook.cancelStroke(); return nil // Core never discards a completed pending command here.
    }
    func retryPending() -> SketchRefusal? {
        guard Thread.isMainThread else { return .wrongOwner }
        guard !readOnly else { return .readOnly }
        guard !notebook.hasActiveStroke else { return .activeInk }
        return notebook.retryPendingStroke() ? nil : .coreRejected
    }
    func retire() -> SketchRefusal? {
        guard Thread.isMainThread else { return .wrongOwner }
        guard !notebook.hasActiveStroke && !notebook.hasPendingStroke else { return .activeInk }
        return notebook.retire() ? nil : .coreRejected
    }
}
