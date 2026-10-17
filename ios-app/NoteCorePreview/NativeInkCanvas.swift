import SwiftUI
import UIKit
import NoteCanvasPrototype

struct NativeInkCanvas: UIViewRepresentable {
    let note: PagedPreviewNotebook
    let renderModel: PreviewModel
    let color: Int32
    let width: Double
    let finger: Bool
    let revision: Int64
    let viewportRequest: Int
    let viewportRatio: Double
    let changed: () -> Void
    let failed: (String) -> Void
    func makeUIView(context: Context) -> InkUIView {
        let view = InkUIView(note: note); view.renderModel = renderModel
        view.lastViewportRequest = viewportRequest; return view
    }
    func updateUIView(_ view: InkUIView, context: Context) {
        if (!note.hasActiveStroke && view.hasInk) || view.fingerDrawing != finger || view.penColor != color || view.penWidth != width { view.cancelInk() }
        view.refreshPage()
        view.penColor = color; view.penWidth = width; view.fingerDrawing = finger
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

final class InkUIView: UIView {
    let note: PagedPreviewNotebook
    weak var renderModel: PreviewModel?
    let surfaceIdentity = UUID()
    var changed: () -> Void = { }
    var failed: (String) -> Void = { _ in }
    var penColor: Int32 = Int32(bitPattern: 0xff253247)
    var penWidth: Double = 2.5
    var fingerDrawing = true
    private var active: UITouch?
    var hasInk: Bool { active != nil }
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
                    return CachedStroke(path: p, color: inkColor(stroke.brush.argb), width: stroke.brush.width,
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
        if let path = path { inkColor(penColor).setStroke(); path.lineWidth = penWidth; path.stroke() }
        c.restoreGState()
    }
    private func sample(_ touch: UITouch) -> (CGPoint, Float, Int64) {
        let raw = touch.location(in: self)
        let point = CGPoint(x: (raw.x - offsetX) / scale, y: (raw.y - offsetY) / scale)
        let pressure: Float = touch.maximumPossibleForce > 0 ? Float(max(0, min(1, touch.force / touch.maximumPossibleForce))) : 0.5
        return (point, pressure, Int64(touch.timestamp * 1_000_000))
    }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard scale > 0, !suppressSequence else { return }
        if active?.type == .pencil { return }
        let direct = fingers(event)
        if navigating || direct.count >= 2 || (!fingerDrawing && !direct.isEmpty) {
            if !navigating { cancelInk(); navigating = true }
            navigate(direct, moving: false); return
        }
        guard active == nil, let touch = touches.first(where: { $0.type == .pencil || ($0.type == .direct && fingerDrawing) }) else { return }
        let (p, pressure, time) = sample(touch)
        guard (0...note.currentPage.width).contains(p.x), (0...note.currentPage.height).contains(p.y) else { return }
        note.selectPen(argb: penColor, width: penWidth)
        guard note.beginStroke(x: p.x, y: p.y, pressure: pressure, timeMicros: time) else { failed(note.lastError); return }
        active = touch; path = UIBezierPath(); path?.lineCapStyle = .round; path?.lineJoinStyle = .round
        path?.move(to: p); path?.addLine(to: CGPoint(x: p.x + 0.01, y: p.y)); setNeedsDisplay()
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        if navigating { navigate(fingers(event), moving: true); return }
        guard !suppressSequence, let active = active, touches.contains(active), note.hasActiveStroke else { return }
        for touch in event?.coalescedTouches(for: active) ?? [active] {
            let (p, pressure, time) = sample(touch)
            guard note.appendStroke(x: p.x, y: p.y, pressure: pressure, timeMicros: time) else { cancelInk(); failed(note.lastError); return }
            path?.addLine(to: p)
        }
        setNeedsDisplay()
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        let remaining = fingers(event, excluding: touches)
        if navigating {
            if remaining.isEmpty { navigating = false; navTouches = [] } else { navigate(remaining, moving: false) }
            return
        }
        if suppressSequence { if remaining.isEmpty { suppressSequence = false }; return }
        guard let active = active, touches.contains(active), note.hasActiveStroke else { return }
        let (p, pressure, time) = sample(active)
        let ok = note.endStroke(x: p.x, y: p.y, pressure: pressure, timeMicros: time)
        self.active = nil; path = nil; suppressSequence = !remaining.isEmpty
        if ok { changed() } else { failed(note.lastError) }; setNeedsDisplay()
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelInk() }
    func cancelInk() { note.cancelStroke(); active = nil; path = nil; navigating = false; navTouches = []; suppressSequence = false; setNeedsDisplay() }
}
