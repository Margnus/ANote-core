import UIKit
import NoteCanvasPrototype

/// Sample software Path consumer. No NoteCore App renderer, native pool or storage dependency.
final class SketchCanvas: UIView {
    let session: SketchSession
    var changed: (String) -> Void = { _ in }
    private var contact: UITouch?
    private var inputOperation: String?
    private var ownsInput: Bool { inputOperation != nil && inputOperation == session.notebook.activeInputOperationId }
    private var active: UIBezierPath?
    private var blocked = false
    private var lastSize = CGSize.zero
    private var cachedRevision: Int64 = -1
    private struct RenderStroke { let path: CGPath; let color: CGColor; let width: CGFloat; let transform: CGAffineTransform }
    private var cached: [RenderStroke] = []
    init(session: SketchSession) { self.session = session; super.init(frame: .zero); isMultipleTouchEnabled = false; isOpaque = true; accessibilityLabel = "内存草图画布" }
    required init?(coder: NSCoder) { fatalError("Use init(session:)") }
    private var scale: CGFloat { min(bounds.width / 595, bounds.height / 842) }
    override func layoutSubviews() {
        super.layoutSubviews()
        if lastSize != bounds.size { cancelUnfinished(); lastSize = bounds.size }
    }
    override func willMove(toWindow newWindow: UIWindow?) {
        if newWindow == nil { cancelUnfinished() }
        super.willMove(toWindow: newWindow)
    }
    func cancelUnfinished() {
        if ownsInput { _ = session.cancelUnfinished() }
        inputOperation = nil; contact = nil; blocked = false; active = nil
        setNeedsDisplay()
    }
    private func color(_ argb: Int32) -> CGColor {
        let v = UInt32(bitPattern: argb)
        return UIColor(red: CGFloat((v >> 16) & 255)/255, green: CGFloat((v >> 8) & 255)/255,
                       blue: CGFloat(v & 255)/255, alpha: CGFloat(v >> 24)/255).cgColor
    }
    private func path(_ samples: StrokeSamples) -> CGPath {
        let p = CGMutablePath(), first = CGPoint(x: samples.x(index: 0), y: samples.y(index: 0))
        p.move(to: first)
        var moved = false
        for i in 1..<Int(samples.size) {
            let point = CGPoint(x: samples.x(index: Int32(i)), y: samples.y(index: Int32(i)))
            p.addLine(to: point); moved = moved || point != first
        }
        if !moved { p.addLine(to: CGPoint(x: first.x + 0.001, y: first.y)) }
        return p
    }
    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext(), scale > 0 else { return }
        // The current path is not part of CGContext's saved graphics state.
        let callerPath = context.path
        context.saveGState(); context.beginPath()
        defer {
            context.beginPath(); context.restoreGState()
            if let callerPath { context.addPath(callerPath) }
        }
        context.setFillColor(UIColor.white.cgColor); context.fill(bounds)
        context.scaleBy(x: scale, y: scale)
        let paper = CGRect(x: 0, y: 0, width: 595, height: 842); context.clip(to: paper)
        context.saveGState(); context.beginPath()
        session.background(context, paper)
        context.beginPath(); context.restoreGState()
        let note = session.notebook
        if cachedRevision != note.changeVersion {
            cached = (0..<Int(note.strokeCount)).map { i in
                let stroke = note.stroke(index: Int32(i)), m = stroke.transform
                return RenderStroke(path: path(stroke.samples), color: color(stroke.brush.argb), width: stroke.brush.width,
                    transform: CGAffineTransform(a: m.a, b: m.b, c: m.c, d: m.d, tx: m.tx, ty: m.ty))
            }
            cachedRevision = note.changeVersion
        }
        for stroke in cached {
            context.saveGState(); context.concatenate(stroke.transform); context.addPath(stroke.path)
            context.setStrokeColor(stroke.color); context.setLineWidth(stroke.width); context.setLineCap(.round); context.setLineJoin(.round)
            context.strokePath(); context.restoreGState()
        }
        if let pending = note.pendingInk() {
            context.addPath(path(pending.samples)); context.setStrokeColor(color(pending.brush.argb))
            context.setLineWidth(pending.brush.width); context.setLineCap(.round); context.setLineJoin(.round); context.strokePath()
        } else if ownsInput, let active {
            context.addPath(active.cgPath); let tool = session.tools[session.toolIndex]
            context.setStrokeColor(color(tool.argb)); context.setLineWidth(tool.width); context.setLineCap(.round); context.setLineJoin(.round); context.strokePath()
        }
        accessibilityValue = "\(note.strokeCount)笔"
    }
    private func points(_ touch: UITouch, _ event: UIEvent?) -> [SketchPoint]? {
        guard scale > 0 else { return nil }
        let real = event?.coalescedTouches(for: touch)
        let touches = real?.isEmpty == false ? real! : [touch]
        guard touches.count <= Int(session.notebook.inputBatchLimit) else { return nil }
        var values: [SketchPoint] = []; values.reserveCapacity(touches.count)
        for touch in touches {
            let p = touch.location(in: self), seconds = touch.timestamp, micros = seconds * 1_000_000
            guard seconds.isFinite, seconds >= 0, micros.isFinite, micros < Double(Int64.max),
                  touch.force.isFinite, touch.maximumPossibleForce.isFinite else { return nil }
            let pressure: Float = touch.maximumPossibleForce > 0 ? Float(max(0, min(1, touch.force / touch.maximumPossibleForce))) : 0.5
            values.append(SketchPoint(x: p.x / scale, y: p.y / scale, pressure: pressure, time: Int64(micros)))
        }
        return values
    }
    private func admit(_ phase: PointerPhase, touch: UITouch, event: UIEvent?) -> Bool {
        guard let values = points(touch, event) else { blocked = true; changed("输入批次无效；已接受前缀保留，请取消或重试"); return false }
        let admission = session.offer(phase, points: values)
        guard admission.accepted else { blocked = true; changed("本批未接收；已接受前缀保留，请取消或重试"); return false }
        for point in values {
            let p = CGPoint(x: point.x, y: point.y)
            if active == nil { active = UIBezierPath(); active?.move(to: p); active?.addLine(to: CGPoint(x: p.x + 0.001, y: p.y)) }
            else { active?.addLine(to: p) }
        }
        setNeedsDisplay(); return true
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if contact != nil && !ownsInput { cancelUnfinished() }
        guard !session.readOnly, contact == nil, !blocked, let touch = touches.first else { return }
        guard !session.notebook.hasActiveStroke else { return }
        guard touch.type != .indirectPointer && touch.type != .indirect else { return }
        if admit(.down, touch: touch, event: event) {
            contact = touch; inputOperation = session.notebook.activeInputOperationId
        }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !blocked, let contact, touches.contains(contact), ownsInput else { return }
        _ = admit(.move, touch: contact, event: event)
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !blocked, let contact, touches.contains(contact), ownsInput else { return }
        if admit(.up, touch: contact, event: event) {
            self.contact = nil; inputOperation = nil; active = nil
            changed(session.notebook.hasPendingStroke ? "完整笔已保留，请重试提交" : "未保存 · \(session.notebook.strokeCount)笔")
        }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelUnfinished() }
}
