import UIKit
import NoteCanvasPrototype

/// Independent MONOLINE consumer. No App model, file service, render pool or alternate scene.
final class SketchCanvas: UIView {
    let session: SketchSession
    var changed: (String) -> Void = { _ in }
    private var active: UITouch?
    private var blocked = false
    private var transient: [CGPoint] = []
    private var previousSize = CGSize.zero
    private var note: PagedPreviewNotebook { session.notebook }
    private var scale: CGFloat { min(bounds.width / note.currentPage.width, bounds.height / note.currentPage.height) }

    init(session: SketchSession) {
        self.session = session
        super.init(frame: .zero)
        accessibilityIdentifier = "内存画布"; isAccessibilityElement = true
        isMultipleTouchEnabled = true; backgroundColor = .secondarySystemBackground
    }
    required init?(coder: NSCoder) { fatalError("Use the injected session initializer") }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != previousSize { cancelUnfinished(); previousSize = bounds.size }
    }
    override func didMoveToWindow() { super.didMoveToWindow(); if window == nil { cancelUnfinished() } }

    func cancelUnfinished() {
        note.cancelStroke() // Original notebook keeps an already completed pending command.
        active = nil; blocked = false; transient.removeAll(keepingCapacity: false)
        setNeedsDisplay(); changed(note.hasPendingStroke ? "已完成笔待重试" : "")
    }

    private func color(_ argb: Int32) -> CGColor {
        let value = UInt32(bitPattern: argb)
        return UIColor(red: CGFloat((value >> 16) & 255) / 255,
            green: CGFloat((value >> 8) & 255) / 255, blue: CGFloat(value & 255) / 255,
            alpha: CGFloat((value >> 24) & 255) / 255).cgColor
    }

    /// CGContext's current path is separate from the saved graphics state.
    static func borrowBackground(_ context: CGContext, size: CGSize, draw: (CGContext, CGSize) -> Void) {
        let path = context.path
        context.saveGState(); context.beginPath()
        draw(context, size)
        context.beginPath(); context.restoreGState()
        if let path { context.addPath(path) }
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext(), scale.isFinite, scale > 0 else { return }
        let callerPath = context.path
        context.saveGState()
        defer {
            context.beginPath(); context.restoreGState()
            if let callerPath { context.addPath(callerPath) }
        }
        context.scaleBy(x: scale, y: scale)
        let page = note.currentPage
        let size = CGSize(width: page.width, height: page.height)
        context.clip(to: CGRect(origin: .zero, size: size))
        Self.borrowBackground(context, size: size, draw: session.background)
        for index in 0..<note.strokeCount {
            let stroke = note.stroke(index: index), samples = stroke.samples, transform = stroke.transform
            context.saveGState()
            context.concatenate(CGAffineTransform(a: transform.a, b: transform.b, c: transform.c,
                d: transform.d, tx: transform.tx, ty: transform.ty))
            var points: [CGPoint] = []; points.reserveCapacity(Int(samples.size))
            for point in 0..<samples.size { points.append(CGPoint(x: samples.x(index: point), y: samples.y(index: point))) }
            paint(points, width: stroke.brush.width, color: color(stroke.brush.argb), context: context)
            context.restoreGState()
        }
        let tool = session.tools[session.toolIndex]
        paint(transient, width: tool.width, color: color(tool.argb), context: context)
    }

    private func paint(_ points: [CGPoint], width: CGFloat, color: CGColor, context: CGContext) {
        guard let first = points.first else { return }
        context.beginPath(); context.setFillColor(color); context.setStrokeColor(color)
        if points.allSatisfy({ $0 == first }) {
            context.fillEllipse(in: CGRect(x: first.x - width / 2, y: first.y - width / 2, width: width, height: width))
        } else {
            context.setLineWidth(width); context.setLineCap(.round); context.setLineJoin(.round)
            context.move(to: first); for point in points.dropFirst() { context.addLine(to: point) }; context.strokePath()
        }
    }

    private func data<T>(_ values: [T]) -> Data {
        values.withUnsafeBufferPointer { buffer in
            guard let base = buffer.baseAddress else { return Data() }
            return Data(bytes: base, count: buffer.count * MemoryLayout<T>.stride)
        }
    }

    /// Shared bridge consumes bounded scalar copies synchronously; no predicted/retained auxiliary touches.
    @discardableResult func offer(phase: PointerPhase, xs: [Double], ys: [Double], pressures: [Float], times: [Int64]) -> PreviewInputResult {
        ApplePreviewInput.shared.offer(notebook: note, phase: phase, eventTimeMicros: times.last ?? 0,
            xs: data(xs), ys: data(ys), pressures: data(pressures), times: data(times))
    }

    private func ingest(_ touch: UITouch, event: UIEvent?, phase: PointerPhase) -> Bool {
        let coalesced = event?.coalescedTouches(for: touch)
        let real = coalesced?.isEmpty == false ? coalesced! : [touch]
        guard scale.isFinite, scale > 0, real.count <= Int(note.inputBatchLimit),
              note.prepareRealBatch(phase: phase, count: Int32(real.count)) == nil else { block(); return false }
        let snapshotScale = scale
        var xs: [Double] = [], ys: [Double] = [], pressures: [Float] = [], times: [Int64] = []
        for sample in real {
            let location = sample.location(in: self)
            let x = Double(location.x / snapshotScale), y = Double(location.y / snapshotScale)
            let time = sample.timestamp * 1_000_000
            guard x.isFinite, y.isFinite, time.isFinite, time >= 0, time < Double(Int64.max),
                  sample.force.isFinite, sample.maximumPossibleForce.isFinite else { block(); return false }
            xs.append(x); ys.append(y); times.append(Int64(time))
            pressures.append(sample.maximumPossibleForce > 0 ? Float(max(0, min(1, sample.force / sample.maximumPossibleForce))) : 0.5)
        }
        if phase == .down {
            guard let x = xs.first, let y = ys.first, (0...note.currentPage.width).contains(x),
                  (0...note.currentPage.height).contains(y) else { return false }
        }
        let result = offer(phase: phase, xs: xs, ys: ys, pressures: pressures, times: times)
        guard result.admitted else { block(); return false }
        if phase == .up {
            active = nil; transient.removeAll(keepingCapacity: false)
        } else { transient += zip(xs, ys).map { CGPoint(x: $0.0, y: $0.1) } }
        changed(result.disposition == .pendingCommit ? "已完成笔待重试" : "")
        setNeedsDisplay(); return true
    }
    private func block() {
        blocked = true; changed(note.lastError + "；原前缀保留，可取消未完成笔")
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !blocked, !note.isReadOnly, active == nil, !note.hasPendingStroke,
              touches.count == 1, let touch = touches.first else { return }
        if ingest(touch, event: event, phase: .down) { active = touch }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !blocked, let active, touches.contains(active) else { return }
        ingest(active, event: event, phase: .move)
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !blocked, let active, touches.contains(active) else { return }
        ingest(active, event: event, phase: .up)
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelUnfinished() }
}
