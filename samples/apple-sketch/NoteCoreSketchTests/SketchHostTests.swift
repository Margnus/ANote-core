import XCTest
import UIKit
import NoteCanvasPrototype
@testable import NoteCoreSketch

final class SketchHostTests: XCTestCase {
    @MainActor private func session(background: @escaping (CGContext, CGSize) -> Void = { _, _ in }) -> SketchSession {
        SketchSession(tools: [SketchTool(title: "注入笔", argb: Int32(bitPattern: 0xff125634), width: 12),
                              SketchTool(title: "第二笔", argb: Int32(bitPattern: 0xff2563eb), width: 8)], background: background)
    }

    @MainActor func testInjectedToolsAndOriginalReadOnlyGuardKeepActivePrefix() throws {
        let host = session(), note = host.notebook
        XCTAssertEqual(host.tools.count, 2); XCTAssertTrue(host.selectTool(1))
        XCTAssertTrue(note.setReadOnly(value: true)); XCTAssertFalse(host.selectTool(0))
        let root = note.snapshot()
        XCTAssertFalse(note.edit(request: PreviewEditRename(title: "bypass")).accepted)
        XCTAssertTrue(note.snapshot() === root)
        XCTAssertTrue(note.setReadOnly(value: false))
        let canvas = SketchCanvas(session: host)
        XCTAssertTrue(canvas.offer(phase: .down, xs: [10], ys: [20], pressures: [0.5], times: [1]).admitted)
        let charge = note.inputReservedByteCharge
        XCTAssertFalse(host.selectTool(0)); XCTAssertFalse(note.setReadOnly(value: true))
        XCTAssertEqual(note.inputReservedByteCharge, charge); XCTAssertTrue(note.hasActiveStroke)
        XCTAssertEqual(canvas.offer(phase: .up, xs: [20], ys: [30], pressures: [0.7], times: [2]).disposition, .committed)
        XCTAssertEqual(note.strokeColor(index: 0), host.tools[1].argb)
        XCTAssertEqual(note.strokeWidth(index: 0), host.tools[1].width)
    }

    @MainActor func testRejectedRealMoveDisplaysTypedFailureAndKeepsPrefix() {
        let host = session(), note = host.notebook, canvas = SketchCanvas(session: host)
        canvas.frame = CGRect(x: 0, y: 0, width: 595, height: 842)
        canvas.layoutIfNeeded()
        let touch = StatusProbeTouch()
        var message = ""
        canvas.changed = { message = $0 }
        canvas.touchesBegan([touch], with: nil)
        let operation = note.activeInputOperationId
        XCTAssertNotNil(operation)
        let charge = note.inputReservedByteCharge
        let root = note.snapshot()
        touch.seconds = 0.5 // Older than the accepted DOWN. The core rejects the entire batch.
        canvas.touchesMoved([touch], with: nil)
        XCTAssertEqual(note.lastFailure?.kind, .invalidInput)
        XCTAssertEqual(message, "输入数据无效；原前缀保留，可取消未完成笔")
        XCTAssertEqual(note.activeInputOperationId, operation)
        XCTAssertEqual(note.inputReservedByteCharge, charge)
        XCTAssertTrue(note.snapshot() === root)
        canvas.cancelUnfinished()
        XCTAssertFalse(note.hasActiveStroke)
    }

    @MainActor func testOriginalBatchBridgePreservesAllRealPointsAndHistory() {
        let host = session(), canvas = SketchCanvas(session: host), note = host.notebook
        XCTAssertTrue(canvas.offer(phase: .down, xs: [1, 2], ys: [3, 4], pressures: [0.2, 0.4], times: [7, 8]).admitted)
        XCTAssertEqual(canvas.offer(phase: .up, xs: [5, 6], ys: [7, 8], pressures: [0.6, 0.8], times: [9, 10]).disposition, .committed)
        XCTAssertEqual(note.pointCount(index: 0), 4)
        for point in Int32(0)..<Int32(4) {
            XCTAssertEqual(note.pointX(index: 0, point: point), [1.0, 2, 5, 6][Int(point)])
            XCTAssertEqual(note.pointY(index: 0, point: point), [3.0, 4, 7, 8][Int(point)])
            XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: point), Int64(point + 7))
        }
        let samples = note.stroke(index: 0).samples
        XCTAssertTrue(note.edit(request: PreviewEditUndo.shared).accepted); XCTAssertEqual(note.strokeCount, 0)
        XCTAssertTrue(note.edit(request: PreviewEditRedo.shared).accepted); XCTAssertEqual(note.strokeCount, 1)
        XCTAssertTrue(note.stroke(index: 0).samples === samples)
    }

    @MainActor func testBorrowedBackgroundRestoresTransformClipAndCurrentPath() throws {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        UIGraphicsImageRenderer(size: CGSize(width: 120, height: 120), format: format).image { renderer in
            let context = renderer.cgContext
            context.move(to: CGPoint(x: 3, y: 4)); context.addLine(to: CGPoint(x: 10, y: 12))
            let path = context.path!.boundingBox, transform = context.ctm, clip = context.boundingBoxOfClipPath
            SketchCanvas.borrowBackground(context, size: CGSize(width: 120, height: 120)) { context, _ in
                context.translateBy(x: 500, y: 500); context.clip(to: CGRect(x: 0, y: 0, width: 1, height: 1))
                context.move(to: CGPoint(x: 99, y: 99)); context.addLine(to: CGPoint(x: 100, y: 100))
            }
            XCTAssertEqual(context.ctm, transform); XCTAssertEqual(context.boundingBoxOfClipPath, clip)
            XCTAssertEqual(context.path!.boundingBox, path)
        }
    }

    @MainActor func testActualViewDrawKeepsInkVisibleAfterInjectedBackgroundMutation() throws {
        let host = session { context, size in
            context.setFillColor(UIColor.white.cgColor); context.fill(CGRect(origin: .zero, size: size))
            context.translateBy(x: 1000, y: 1000); context.clip(to: CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        let canvas = SketchCanvas(session: host)
        canvas.frame = CGRect(x: 0, y: 0, width: 595, height: 842); canvas.layoutIfNeeded()
        XCTAssertTrue(canvas.offer(phase: .down, xs: [100], ys: [100], pressures: [0.5], times: [1]).admitted)
        XCTAssertEqual(canvas.offer(phase: .up, xs: [100], ys: [100], pressures: [0.5], times: [2]).disposition, .committed)
        XCTAssertEqual(host.notebook.strokeCount, 1)
        XCTAssertEqual(host.notebook.pointX(index: 0, point: 0), 100)
        XCTAssertEqual(host.notebook.pointY(index: 0, point: 0), 100)
        var bytes = [UInt8](repeating: 0, count: 595 * 842 * 4)
        try bytes.withUnsafeMutableBytes { buffer in
            let context = try XCTUnwrap(CGContext(data: buffer.baseAddress, width: 595, height: 842,
                bitsPerComponent: 8, bytesPerRow: 595 * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            // UIView.draw receives a top-down UIKit context. A raw bitmap context is
            // bottom-up: establish the caller transform before sampling row y=100.
            context.translateBy(x: 0, y: 842); context.scaleBy(x: 1, y: -1)
            UIGraphicsPushContext(context); canvas.draw(canvas.bounds); UIGraphicsPopContext()
        }
        let offset = (100 * 595 + 100) * 4
        var colored = 0, minX = 595, minY = 842, maxX = -1, maxY = -1
        for y in 0..<842 { for x in 0..<595 {
            let start = (y * 595 + x) * 4
            if Array(bytes[start..<start + 4]) == [0x12, 0x56, 0x34, 255] {
                colored += 1; minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y)
            }
        } }
        let trace: [String: Any] = ["center_rgba": Array(bytes[offset..<offset + 4]), "colored_count": colored,
            "colored_bounds": [minX, minY, maxX, maxY], "stroke_count": host.notebook.strokeCount,
            "point_count": host.notebook.pointCount(index: 0), "point_x": host.notebook.pointX(index: 0, point: 0),
            "point_y": host.notebook.pointY(index: 0, point: 0), "width": host.notebook.strokeWidth(index: 0),
            "bounds": [canvas.bounds.width, canvas.bounds.height]]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: trace, options: [.sortedKeys]), uniformTypeIdentifier: "public.json")
        attachment.name = "pixel-coordinate-and-background-isolation"; attachment.lifetime = .keepAlways; add(attachment)
        XCTAssertEqual(Array(bytes[offset..<offset + 4]), [0x12, 0x56, 0x34, 255])
        let mirroredOffset = ((842 - 1 - 100) * 595 + 100) * 4
        XCTAssertEqual(Array(bytes[mirroredOffset..<mirroredOffset + 4]), [255, 255, 255, 255])
    }

    @MainActor func testResizeAndDetachCancelUnfinishedInputWithoutChangingRoot() throws {
        let host = session(), canvas = SketchCanvas(session: host), note = host.notebook
        canvas.frame = CGRect(x: 0, y: 0, width: 595, height: 842); canvas.layoutIfNeeded()
        let root = note.snapshot()
        XCTAssertTrue(canvas.offer(phase: .down, xs: [1], ys: [2], pressures: [0.5], times: [1]).admitted)
        canvas.frame.size = CGSize(width: 300, height: 600); canvas.setNeedsLayout(); canvas.layoutIfNeeded()
        XCTAssertFalse(note.hasActiveStroke); XCTAssertEqual(note.inputReservedByteCharge, 0)
        XCTAssertTrue(note.snapshot() === root)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 595, height: 842))
        let controller = UIViewController(); window.rootViewController = controller; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        controller.view.addSubview(canvas)
        XCTAssertTrue(canvas.offer(phase: .down, xs: [1], ys: [2], pressures: [0.5], times: [2]).admitted)
        XCTAssertTrue(note.hasActiveStroke); XCTAssertNotNil(canvas.window)
        canvas.removeFromSuperview()
        XCTAssertFalse(note.hasActiveStroke); XCTAssertEqual(note.inputReservedByteCharge, 0)
        XCTAssertTrue(note.snapshot() === root)

        // Exercise the actual App-host Scene delegate with its original controller/Notebook.
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let delegate = try XCTUnwrap(scene.delegate as? SketchSceneDelegate)
        let sceneController = try XCTUnwrap(delegate.window?.rootViewController as? SketchController)
        let owned = sceneController.session.notebook, committed = owned.snapshot()
        XCTAssertTrue(sceneController.canvas.offer(phase: .down, xs: [50], ys: [60], pressures: [0.5], times: [1]).admitted)
        XCTAssertTrue(owned.hasActiveStroke); XCTAssertGreaterThan(owned.inputReservedByteCharge, 0)
        delegate.sceneWillResignActive(scene)
        XCTAssertFalse(owned.hasActiveStroke); XCTAssertEqual(owned.inputReservedByteCharge, 0)
        XCTAssertTrue(owned.snapshot() === committed)
        XCTAssertTrue(sceneController.canvas.offer(phase: .down, xs: [70], ys: [80], pressures: [0.5], times: [2]).admitted)
        delegate.sceneDidDisconnect(scene)
        XCTAssertFalse(owned.hasActiveStroke); XCTAssertEqual(owned.inputReservedByteCharge, 0)
        XCTAssertTrue(owned.snapshot() === committed)
    }
}


private final class StatusProbeTouch: UITouch {
    var seconds = 1.0
    override var timestamp: TimeInterval { seconds }
    override var force: CGFloat { 0.5 }
    override var maximumPossibleForce: CGFloat { 1 }
    override func location(in view: UIView?) -> CGPoint { CGPoint(x: 10, y: 20) }
}
