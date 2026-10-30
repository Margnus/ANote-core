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
        XCTAssertTrue(canvas.offer(phase: .up, xs: [100], ys: [100], pressures: [0.5], times: [2]).admitted)
        var bytes = [UInt8](repeating: 0, count: 595 * 842 * 4)
        try bytes.withUnsafeMutableBytes { buffer in
            let context = try XCTUnwrap(CGContext(data: buffer.baseAddress, width: 595, height: 842,
                bitsPerComponent: 8, bytesPerRow: 595 * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            UIGraphicsPushContext(context); canvas.draw(canvas.bounds); UIGraphicsPopContext()
        }
        let offset = (100 * 595 + 100) * 4
        XCTAssertEqual(Array(bytes[offset..<offset + 4]), [0x12, 0x56, 0x34, 255])
    }

    @MainActor func testResizeAndDetachCancelUnfinishedInputWithoutChangingRoot() {
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
    }
}
