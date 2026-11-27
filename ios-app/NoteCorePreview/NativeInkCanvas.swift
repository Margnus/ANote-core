import SwiftUI
import UIKit
import NoteCanvasPrototype

func previewFailureMessage(_ failure: PreviewSessionFailure?) -> String {
    switch failure?.kind {
    case .closed: return "此会话已关闭，请重新打开笔记"
    case .pendingStroke: return "上一笔尚未提交，请先重试保存"
    case .activeStroke: return "请先完成当前笔画"
    case .invalidArgument: return "操作参数无效，请检查后重试"
    case .missingPage: return "页面不存在"
    case .capacity: return "此预览已达到笔迹容量"
    case .identityExhausted: return "此会话编号已耗尽，请保留笔记并导出备份"
    case .historyEmpty: return "没有可撤销或重做的操作"
    case .documentRejected:
        switch failure?.documentReason {
        case .journalCapacity, .historyCapacity, .liveByteCapacity, .identityCapacity, .objectCapacity, .pageCapacity:
            return "当前预览已达到编辑容量，请保留笔记并导出备份"
        default: return "操作未完成，原内容仍保留，请重试"
        }
    case .noActiveStroke: return "没有进行中的笔画"
    case .invalidInput: return "输入数据无效"
    case .inputBatchCapacity: return "输入批次超限"
    case .readOnly: return "阅读模式不能修改笔记"
    default: return "当前操作无法完成"
    }
}

/** Preview tool routing: pen strokes, whole-stroke eraser gestures, or lasso selection. */
enum InkTool { case pen, eraser, lasso }

struct NativeInkCanvas: UIViewRepresentable {
    let note: PagedPreviewNotebook
    let renderModel: PreviewModel
    let color: Int32
    let width: Double
    let kind: BrushKind
    let tool: InkTool
    let finger: Bool
    let revision: Int64
    let viewportRequest: Int
    let viewportRatio: Double
    let changed: () -> Void
    let failed: (String) -> Void
    var inputOwner: PreviewWindowState? = nil
    func makeUIView(context: Context) -> InkUIView {
        let view = InkUIView(note: note); view.renderModel = renderModel
        view.inputWindow = inputOwner
        inputOwner?.bindInput(view)
        view.lastViewportRequest = viewportRequest; return view
    }
    func updateUIView(_ view: InkUIView, context: Context) {
        view.inputWindow = inputOwner
        inputOwner?.bindInput(view)
        if (!note.hasActiveStroke && view.hasInk) || view.fingerDrawing != finger || view.penColor != color || view.penWidth != width || view.penKind != kind || view.tool != tool { view.cancelInk() }
        view.refreshPage()
        view.penColor = color; view.penWidth = width; view.penKind = kind; view.fingerDrawing = finger; view.tool = tool
        if view.lastViewportRequest != viewportRequest {
            view.cancelInk(); view.lastViewportRequest = viewportRequest
            if viewportRatio == 0 { view.viewport.reset() }
            else { view.viewport.gesture(oldX: view.bounds.midX, oldY: view.bounds.midY, newX: view.bounds.midX, newY: view.bounds.midY, ratio: viewportRatio) }
        }
        view.changed = changed; view.failed = failed; view.setNeedsDisplay()
    }
    static func dismantleUIView(_ view: InkUIView, coordinator: ()) {
        view.cancelInk(); view.renderModel?.detachInk(surface: view.surfaceIdentity)
    }
}

/// Standalone candidate-2 backend; the algorithm-1 App does not enable this renderer yet.
final class NativeBrushRenderer {
    private let path: CGPath
    private let color: CGColor
    init(plan: BrushPathPlan) {
        precondition(plan.fillRule == .nonZero && plan.blendMode == .sourceOver)
        let outline = CGMutablePath()
        for contour in 0..<Int(plan.contourCount) {
            let start = plan.contourStart(contour: Int32(contour)), end = plan.contourEnd(contour: Int32(contour))
            outline.move(to: CGPoint(x: plan.x(vertex: start), y: plan.y(vertex: start)))
            for vertex in Int(start + 1)..<Int(end) {
                outline.addLine(to: CGPoint(x: plan.x(vertex: Int32(vertex)), y: plan.y(vertex: Int32(vertex))))
            }
            outline.closeSubpath()
        }
        path = outline.copy()!
        let argb = UInt32(bitPattern: plan.argb)
        color = UIColor(red: CGFloat((argb >> 16) & 255) / 255, green: CGFloat((argb >> 8) & 255) / 255,
                        blue: CGFloat(argb & 255) / 255, alpha: CGFloat(argb >> 24) / 255).cgColor
    }
    /// The plan is already in context-local target pixels. Preserve the caller's graphics state AND current path.
    func draw(in context: CGContext) {
        let previousPath = context.path
        context.saveGState()
        defer {
            context.restoreGState(); context.beginPath()
            if let previousPath = previousPath { context.addPath(previousPath) }
        }
        context.beginPath(); context.setBlendMode(.normal); context.setAlpha(1)
        context.setFillColor(color); context.addPath(path); context.fillPath(using: .winding)
    }
}

final class InkUIView: UIView {
    let note: PagedPreviewNotebook
    weak var renderModel: PreviewModel?
    weak var inputWindow: PreviewWindowState?
    let surfaceIdentity = UUID()
    var changed: () -> Void = { }
    var failed: (String) -> Void = { _ in }
    var penColor: Int32 = Int32(bitPattern: 0xff253247)
    var penWidth: Double = 2.5
    var penKind: BrushKind = .monoline
    var tool: InkTool = .pen
    var eraserRadius: Double = 8.0
    private var toolTouch: UITouch?
    // Same point ceiling as shared EraserRules/LassoRules, before native retention.
    private let maxToolPathPoints = 4096
    private var eraserPoints: [CGPoint] = []
    private var eraserPath: UIBezierPath?
    private var lassoPolygon: [CGPoint] = []
    private var lassoPath: UIBezierPath?
    private var lassoDragStart: CGPoint?
    private var lassoDragCurrent: CGPoint?
    var fingerDrawing = true
    private var active: UITouch?
    private var inputOperation: String?
    private var ownsInput: Bool { inputOperation != nil && inputOperation == note.activeInputOperationId }
    var hasInk: Bool { active != nil && ownsInput }
    private var path: UIBezierPath?
    private struct CachedStroke {
        let path: UIBezierPath
        let color: UIColor
        let width: CGFloat
        let transform: CGAffineTransform
        let dot: CGPoint?
    }
    private var cached: [CachedStroke] = []
    private var cachedPage = ""
    private var configuredPage = ""
    private var cachedVersion: Int64 = -1
    private var oldBounds: CGRect = .zero
    let viewport = PreviewViewport()
    var lastViewportRequest = 0
    private var scale: CGFloat { viewport.scale }
    private var offsetX: CGFloat { viewport.offsetX }
    private var offsetY: CGFloat { viewport.offsetY }
    private var navigating = false
    private var navTouches = Set<UITouch>()
    private var navCenter = CGPoint.zero
    private var navSpan: CGFloat = 0
    private var suppressSequence = false
    private func fingers(_ event: UIEvent?, excluding: Set<UITouch> = []) -> [UITouch] {
        Array((event?.allTouches ?? []).filter { $0.type == .direct && !excluding.contains($0) && $0.phase != .ended && $0.phase != .cancelled }).prefix(2).map { $0 }
    }
    private func navigate(_ fingers: [UITouch], moving: Bool) {
        guard !fingers.isEmpty else { navTouches = []; return }
        let points = fingers.map { $0.location(in: self) }
        let center = CGPoint(x: points.reduce(0) { $0 + $1.x } / CGFloat(points.count), y: points.reduce(0) { $0 + $1.y } / CGFloat(points.count))
        let span = points.count == 2 ? hypot(points[0].x - points[1].x, points[0].y - points[1].y) : 0
        let ids = Set(fingers)
        if moving && ids == navTouches {
            viewport.gesture(oldX: navCenter.x, oldY: navCenter.y, newX: center.x, newY: center.y, ratio: span > 1 && navSpan > 1 ? span / navSpan : 1)
        }
        navTouches = ids; navCenter = center; navSpan = span; setNeedsDisplay()
    }
    init(note: PagedPreviewNotebook) {
        self.note = note; super.init(frame: .zero)
        isMultipleTouchEnabled = true; backgroundColor = UIColor(red: 0.93, green: 0.95, blue: 0.97, alpha: 1)
        isAccessibilityElement = true; accessibilityLabel = "手写画布"; accessibilityIdentifier = "note-canvas"
    }
    func refreshPage() {
        let page = note.currentPage
        if configuredPage != page.id.value || viewport.pageWidth != page.width || viewport.pageHeight != page.height {
            cancelInk(); viewport.configurePage(width: page.width, height: page.height); viewport.reset()
            configuredPage = page.id.value
        }
        accessibilityValue = "\(note.strokeCount)笔"
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func layoutSubviews() {
        super.layoutSubviews()
        if oldBounds != .zero && oldBounds != bounds { cancelInk() }
        oldBounds = bounds; refreshPage(); viewport.resize(width: bounds.width, height: bounds.height)
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { renderModel?.detachInk(surface: surfaceIdentity) }
    }
    override func draw(_ rect: CGRect) {
        guard let c = UIGraphicsGetCurrentContext(), scale > 0 else { return }
        c.saveGState(); c.translateBy(x: offsetX, y: offsetY); c.scaleBy(x: scale, y: scale)
        let page = note.currentPage
        let paper = CGRect(x: 0, y: 0, width: page.width, height: page.height)
        UIColor.white.setFill(); c.fill(paper); c.clip(to: paper)
        c.setStrokeColor(UIColor(white: 0.92, alpha: 1).cgColor); c.setLineWidth(1)
        // Bound guide geometry even for a maximum-size imported page.
        let spacing = max(24.0, max(page.width, page.height) / 80.0)
        if page.paper == .ruled || page.paper == .grid {
            for y in stride(from: spacing, to: page.height, by: spacing) {
                c.move(to: CGPoint(x: 0, y: y)); c.addLine(to: CGPoint(x: page.width, y: y))
            }
        }
        if page.paper == .grid {
            for x in stride(from: spacing, to: page.width, by: spacing) {
                c.move(to: CGPoint(x: x, y: 0)); c.addLine(to: CGPoint(x: x, y: page.height))
            }
        }
        c.strokePath()
        if page.paper == .dots {
            c.setFillColor(UIColor(white: 0.92, alpha: 1).cgColor)
            for x in stride(from: spacing, to: page.width, by: spacing) {
                for y in stride(from: spacing, to: page.height, by: spacing) {
                    c.fillEllipse(in: CGRect(x: x - 0.8, y: y - 0.8, width: 1.6, height: 1.6))
                }
            }
        }
        let committedFrameDrawn = renderModel?.drawCommittedInk(c, note: note, view: self) ?? false
        if !committedFrameDrawn {
            if cachedVersion != note.changeVersion || cachedPage != page.id.value {
                cached = (0..<note.strokeCount).map { index in
                    let stroke = note.stroke(index: index), samples = stroke.samples, m = stroke.transform
                    let p = UIBezierPath(); p.lineCapStyle = .round; p.lineJoinStyle = .round
                    let start = CGPoint(x: samples.x(index: 0), y: samples.y(index: 0))
                    p.move(to: start)
                    var moved = false
                    for point in 1..<samples.size {
                        let next = CGPoint(x: samples.x(index: point), y: samples.y(index: point))
                        p.addLine(to: next); moved = moved || next != start
                    }
                    return CachedStroke(path: p, color: inkColor(BrushDisplayRules.shared.displayArgb(brush: stroke.brush)), width: stroke.brush.width,
                        transform: CGAffineTransform(a: m.a, b: m.b, c: m.c, d: m.d, tx: m.tx, ty: m.ty), dot: moved ? nil : start)
                }
                cachedVersion = note.changeVersion; cachedPage = page.id.value
                accessibilityValue = "\(note.strokeCount)笔"
            }
            for stroke in cached {
                c.saveGState(); c.concatenate(stroke.transform)
                if let dot = stroke.dot {
                    stroke.color.setFill()
                    c.fillEllipse(in: CGRect(x: dot.x - stroke.width / 2, y: dot.y - stroke.width / 2, width: stroke.width, height: stroke.width))
                } else { stroke.color.setStroke(); stroke.path.lineWidth = stroke.width; stroke.path.stroke() }
                c.restoreGState()
            }
        }
        if ownsInput, let path = path { inkColor(BrushDisplayRules.shared.displayArgb(brush: BrushSpec(kind: penKind, algorithmVersion: 1, width: penWidth, argb: penColor))).setStroke(); path.lineWidth = penWidth; path.stroke() }
        if let eraserPath = eraserPath {
            UIColor(white: 0.53, alpha: 0.4).setStroke()
            eraserPath.lineWidth = eraserRadius * 2; eraserPath.lineCapStyle = .round; eraserPath.lineJoinStyle = .round
            eraserPath.stroke()
        }
        if let lassoPath = lassoPath {
            UIColor(red: 0.21, green: 0.41, blue: 0.75, alpha: 1).setStroke()
            lassoPath.lineWidth = 1.5 / scale; lassoPath.setLineDash([6, 6], count: 2, phase: 0); lassoPath.stroke()
        }
        if let b = selectionBounds() {
            let dx = (lassoDragCurrent?.x ?? 0) - (lassoDragStart?.x ?? 0)
            let dy = (lassoDragCurrent?.y ?? 0) - (lassoDragStart?.y ?? 0)
            let border = UIBezierPath(rect: CGRect(x: b.minX + dx, y: b.minY + dy, width: b.width, height: b.height))
            UIColor(red: 0.21, green: 0.41, blue: 0.75, alpha: 1).setStroke()
            border.lineWidth = 1.5 / scale; border.setLineDash([8, 6], count: 2, phase: 0); border.stroke()
        }
        c.restoreGState()
    }
    private struct RealSample { let point: CGPoint; let pressure: Float; let time: Int64 }
    private enum RealBatch {
        case ready([RealSample])
        case refused(String)
    }
    /// UIKit's coalesced array includes a copy of the main touch. Copy values immediately;
    /// never append the main touch again, retain auxiliary UITouchs or ingest predictions.
    private func realSamples(_ touch: UITouch, event: UIEvent?, phase: PointerPhase) -> RealBatch {
        let auxiliary = event?.coalescedTouches(for: touch)
        let touches = auxiliary?.isEmpty == false ? auxiliary! : [touch]
        guard touches.count <= Int(note.inputBatchLimit) else { return .refused("输入批次超限") }
        if let refusal = note.prepareRealBatch(phase: phase, count: Int32(touches.count)) {
            return .refused(previewFailureMessage(refusal.failure))
        }
        let transform = (scale: scale, x: offsetX, y: offsetY)
        guard transform.scale.isFinite, transform.scale > 0 else { return .refused("画布当前无法接收输入") }
        var samples: [RealSample] = []; samples.reserveCapacity(touches.count)
        for item in touches {
            let raw = item.location(in: self)
            let point = CGPoint(x: (raw.x - transform.x) / transform.scale, y: (raw.y - transform.y) / transform.scale)
            let seconds = item.timestamp, micros = seconds * 1_000_000
            let force = item.force, maximum = item.maximumPossibleForce
            // Double(Int64.max) rounds UP to 2^63. Strict '<' prevents a trapping conversion.
            guard point.x.isFinite, point.y.isFinite, abs(point.x) <= 1e9, abs(point.y) <= 1e9,
                  seconds.isFinite, seconds >= 0, micros.isFinite, micros < Double(Int64.max),
                  force.isFinite, maximum.isFinite else { return .refused("输入数据无效") }
            let pressure: Float = maximum > 0 ? Float(max(0, min(1, force / maximum))) : 0.5
            samples.append(RealSample(point: point, pressure: pressure, time: Int64(micros)))
        }
        return .ready(samples)
    }
    /// Fill one bounded native buffer directly; no mapped scalar array survives beside Data.
    private func scalarData<T>(_ samples: [RealSample], value: (RealSample) -> T) -> Data {
        var data = Data(count: samples.count * MemoryLayout<T>.stride)
        data.withUnsafeMutableBytes { (destination: UnsafeMutableRawBufferPointer) in
            for (index, sample) in samples.enumerated() {
                var scalar = value(sample)
                withUnsafeBytes(of: &scalar) { source in
                    destination.baseAddress!.advanced(by: index * MemoryLayout<T>.stride)
                        .copyMemory(from: source.baseAddress!, byteCount: source.count)
                }
            }
        }
        return data
    }
    private func offerReal(_ samples: [RealSample], phase: PointerPhase) -> PreviewInputResult {
        // Four bounded native scalar buffers cross once; no per-point Kotlin callbacks or retained UITouchs.
        ApplePreviewInput.shared.offer(notebook: note, phase: phase, eventTimeMicros: samples.last?.time ?? 0,
            xs: scalarData(samples) { Double($0.point.x) }, ys: scalarData(samples) { Double($0.point.y) },
            pressures: scalarData(samples) { $0.pressure }, times: scalarData(samples) { $0.time })
    }
    private func preparedSamples(_ touch: UITouch, event: UIEvent?, phase: PointerPhase) -> [RealSample]? {
        switch realSamples(touch, event: event, phase: phase) {
        case .ready(let samples): return samples
        case .refused(let message): rejectInput(message); return nil
        }
    }
    private func rejectInput(_ message: String) {
        cancelInk()
        failed(message + "；本笔未提交")
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard scale > 0, !suppressSequence else { return }
        if active != nil && !ownsInput { cancelInk() }
        if active?.type == .pencil { return }
        let direct = fingers(event)
        if navigating || direct.count >= 2 || (tool == .pen && !fingerDrawing && !direct.isEmpty) {
            if !navigating { cancelInk(); navigating = true }
            navigate(direct, moving: false); return
        }
        if tool != .pen {
            guard toolTouch == nil else { return }
            guard !note.hasActiveStroke else { failed("其他画布正在输入，请完成后重试"); return }
            guard let touch = touches.first(where: { $0.type == .pencil || $0.type == .direct }) else { return }
            guard let points = pagePoints(touch, event: event) else { rejectToolGesture(); return }
            guard let p = points.first else { return }
            toolTouch = touch
            if tool == .eraser {
                eraserPoints = points
                eraserPath = UIBezierPath(); eraserPath?.move(to: p)
                for q in points.dropFirst() { eraserPath?.addLine(to: q) }
            } else if let b = selectionBounds(), p.x >= b.minX, p.x <= b.maxX, p.y >= b.minY, p.y <= b.maxY {
                lassoDragStart = p; lassoDragCurrent = p
            } else {
                lassoPolygon = points
                lassoPath = UIBezierPath(); lassoPath?.move(to: p)
                for q in points.dropFirst() { lassoPath?.addLine(to: q) }
            }
            setNeedsDisplay(); return
        }
        guard active == nil, let touch = touches.first(where: { $0.type == .pencil || ($0.type == .direct && fingerDrawing) }) else { return }
        guard !note.hasActiveStroke else { failed("其他画布正在输入，请完成后重试"); return }
        guard let samples = preparedSamples(touch, event: event, phase: .down), let first = samples.first else { return }
        let p = first.point
        guard (0...note.currentPage.width).contains(p.x), (0...note.currentPage.height).contains(p.y) else { return }
        let pen = note.edit(request: PreviewEditSelectPen(argb: penColor, width: penWidth, kind: penKind))
        guard pen.accepted else { failed(previewFailureMessage(pen.failure)); return }
        let result = offerReal(samples, phase: .down)
        guard result.admitted else { rejectInput(previewFailureMessage(result.failure)); return }
        inputOperation = note.activeInputOperationId
        inputWindow?.acceptedInput(from: self)
        active = touch; path = UIBezierPath(); path?.lineCapStyle = .round; path?.lineJoinStyle = .round
        path?.move(to: p); path?.addLine(to: CGPoint(x: p.x + 0.01, y: p.y)); setNeedsDisplay()
        for sample in samples.dropFirst() {
            path?.addLine(to: sample.point)
        }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if navigating { navigate(fingers(event), moving: true); return }
        if tool != .pen {
            guard !suppressSequence, let touch = toolTouch, touches.contains(touch) else { return }
            guard let points = pagePoints(touch, event: event) else { rejectToolGesture(); return }
            for p in points {
                guard appendToolPoint(p) else { return }
            }
            setNeedsDisplay(); return
        }
        guard !suppressSequence, let active = active, touches.contains(active), ownsInput else { return }
        guard let samples = preparedSamples(active, event: event, phase: .move) else { return }
        let result = offerReal(samples, phase: .move)
        guard result.admitted else { rejectInput(previewFailureMessage(result.failure)); return }
        for sample in samples {
            path?.addLine(to: sample.point)
        }
        setNeedsDisplay()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let remaining = fingers(event, excluding: touches)
        if navigating {
            if remaining.isEmpty { navigating = false; navTouches = [] } else { navigate(remaining, moving: false) }
            return
        }
        if tool != .pen {
            guard let touch = toolTouch, touches.contains(touch) else { return }
            // UIKit may deliver the whole final segment as coalesced touches; consume them before committing.
            guard let terminal = pagePoints(touch, event: event) else { rejectToolGesture(); return }
            for point in terminal.dropLast() {
                if !appendToolPoint(point) { return }
            }
            if let last = terminal.last, !appendToolPoint(last, lift: true) { return }
            toolTouch = nil
            if tool == .eraser, !eraserPoints.isEmpty {
                let before = note.changeVersion
                let path = eraserPoints.map { PointD(x: $0.x, y: $0.y) }
                let result = note.edit(request: PreviewEditEraseStrokes(path: path, radius: eraserRadius))
                if !result.accepted { failed(previewFailureMessage(result.failure)) }
                else if result.rootRevision != before { changed() }
            } else if let start = lassoDragStart, let current = lassoDragCurrent {
                let dx = current.x - start.x, dy = current.y - start.y
                if dx != 0 || dy != 0 {
                    let result = note.edit(request: PreviewEditTransformSelection(dx: dx, dy: dy, scaleFactor: 1.0, anchor: PointD(x: 0.0, y: 0.0)))
                    if !result.accepted { failed(previewFailureMessage(result.failure)) } else { changed() }
                }
            } else if lassoPolygon.count >= 3 {
                let polygon = lassoPolygon.map { PointD(x: $0.x, y: $0.y) }
                let result = note.edit(request: PreviewEditLassoSelect(polygon: polygon))
                if !result.accepted { failed(previewFailureMessage(result.failure)) }
            }
            clearToolGesture(); setNeedsDisplay(); return
        }
        if suppressSequence { if remaining.isEmpty { suppressSequence = false }; return }
        guard let active = active, touches.contains(active), ownsInput else { return }
        guard let samples = preparedSamples(active, event: event, phase: .up) else { return }
        let result = offerReal(samples, phase: .up)
        guard result.admitted else { rejectInput(previewFailureMessage(result.failure)); return }
        self.active = nil; inputOperation = nil; path = nil; suppressSequence = !remaining.isEmpty
        switch result.disposition {
        case .committed: changed()
        case .pendingCommit: failed("已完成笔迹尚未提交，已保留，请重试保存")
        default: failed(previewFailureMessage(result.failure))
        }
        setNeedsDisplay()
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if let toolTouch, !touches.contains(toolTouch) { return }
        cancelInk()
    }
    /** Page coordinates through the same viewport inverse as ink input, coalesced for density. */
    private func pagePoints(_ touch: UITouch, event: UIEvent?) -> [CGPoint]? {
        guard scale > 0 else { return [] }
        guard let touches = event?.coalescedTouches(for: touch), !touches.isEmpty else {
            return pagePoint(touch).map { [$0] } ?? []
        }
        guard touches.count <= maxToolPathPoints else { return nil }
        return touches.compactMap(pagePoint)
    }
    private func pagePoint(_ touch: UITouch) -> CGPoint? {
        guard scale > 0 else { return nil }
        let raw = touch.location(in: self)
        let x = (raw.x - offsetX) / scale, y = (raw.y - offsetY) / scale
        guard x.isFinite, y.isFinite, abs(x) <= 1e9, abs(y) <= 1e9 else { return nil }
        return CGPoint(x: x, y: y)
    }
    private func rejectToolGesture() {
        clearToolGesture()
        setNeedsDisplay()
        failed("工具轨迹超过4096点，本次操作未提交，请分段重试")
    }
    private func appendToolPoint(_ point: CGPoint, lift: Bool = false) -> Bool {
        if tool == .eraser {
            if lift && eraserPoints.last == point { return true }
            guard eraserPoints.count < maxToolPathPoints else { rejectToolGesture(); return false }
            eraserPoints.append(point); eraserPath?.addLine(to: point)
        } else if lassoDragStart != nil {
            lassoDragCurrent = point
        } else if let last = lassoPolygon.last, abs(point.x - last.x) + abs(point.y - last.y) > 1.0 {
            guard lassoPolygon.count < maxToolPathPoints else { rejectToolGesture(); return false }
            lassoPolygon.append(point); lassoPath?.addLine(to: point)
        }
        return true
    }
    private func clearToolGesture() {
        toolTouch = nil; eraserPoints = []; eraserPath = nil
        lassoPolygon = []; lassoPath = nil; lassoDragStart = nil; lassoDragCurrent = nil
    }
    /// Page-space union bounds of the current lasso selection; nil when nothing is selected.
    private func selectionBounds() -> CGRect? {
        let ids = Set(note.selectedStrokeIds.map { $0.value })
        if ids.isEmpty { return nil }
        var result: CGRect? = nil
        for i in 0..<note.strokeCount {
            let stroke = note.stroke(index: i)
            if ids.contains(stroke.id.value) {
                let b = stroke.bounds()
                let rect = CGRect(x: b.left, y: b.top, width: b.right - b.left, height: b.bottom - b.top)
                result = result == nil ? rect : result!.union(rect)
            }
        }
        return result
    }
    func cancelInk() {
        if ownsInput { note.cancelStroke() }
        clearToolGesture()
        active = nil; inputOperation = nil; path = nil; navigating = false; navTouches = []; suppressSequence = false; setNeedsDisplay()
    }
}
