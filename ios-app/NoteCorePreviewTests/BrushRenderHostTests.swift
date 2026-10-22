import XCTest
import CoreGraphics
import UIKit
import NoteCanvasPrototype
@testable import NoteCorePreview

// Actual app-host Kotlin/Native render -> existing Swift worker -> CGImage responsibility.
final class BrushRenderHostTests: XCTestCase {
    @MainActor private func preview(before: @escaping () throws -> Void = {},
        release: @escaping (Bool) throws -> Void = { _ in }
    ) throws -> (PreviewModel, InkUIView) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("notecore-ink-host-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let model = PreviewModel(directory: directory, beforeInkCompute: before, inkReleaseBoundary: release)
        model.create()
        let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.setPaper(width: 200, height: 200, paper: .blank))
        for color: UInt32 in [0x80ff0000, 0x800000ff] {
            note.selectPen(argb: Int32(bitPattern: color), width: 10)
            XCTAssertTrue(note.beginStroke(x: 50, y: 40, pressure: 1, timeMicros: 0))
            XCTAssertTrue(note.endStroke(x: 50, y: 40, pressure: 1, timeMicros: 1))
        }
        model.changed()
        let view = InkUIView(note: note); view.renderModel = model; view.contentScaleFactor = 1
        view.frame = CGRect(x: 0, y: 0, width: 200, height: 200); view.layoutIfNeeded()
        return (model, view)
    }
    @MainActor private func viewImage(_ view: InkUIView) -> CGImage {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.preferredRange = .standard
        return UIGraphicsImageRenderer(size: view.bounds.size, format: format).image { _ in view.draw(view.bounds) }.cgImage!
    }
    private func pixel(_ image: CGImage, _ x: Int, _ y: Int) throws -> [Int] {
        let context = try XCTUnwrap(CGContext(data: nil, width: image.width, height: image.height,
            bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        let bytes = try XCTUnwrap(context.data).assumingMemoryBound(to: UInt8.self)
        return (0..<4).map { Int(bytes[(y * image.width + x) * 4 + $0]) }
    }
    @MainActor private func eventually(_ condition: () -> Bool) async throws {
        let until = Date().addingTimeInterval(10)
        var success = condition()
        while !success && Date() < until {
            try await Task.sleep(nanoseconds: 10_000_000); success = condition()
        }
        XCTAssertTrue(success)
    }
    @MainActor func testPreviewUIViewConsumesSharedFrameThenDetachKeepsStorageUsable() async throws {
        let (model, view) = try preview()
        let note = view.note, id = note.documentId
        let before = viewImage(view)
        XCTAssertEqual(model.inkRejectedTargets, 0)
        try await eventually { model.inkFramePublications > 0 }
        let after = viewImage(view)
        XCTAssertGreaterThan(model.inkFrameDraws, 0)
        let expected = try pixel(before, 50, 40), actual = try pixel(after, 50, 40)
        for i in 0..<4 { XCTAssertLessThanOrEqual(abs(expected[i] - actual[i]), 2) }
        XCTAssertLessThan(actual[1], 100); XCTAssertGreaterThan(actual[2], 170)
        XCTAssertEqual(try pixel(after, 50, 160), [255,255,255,255]) // Detect upside-down image.
        note.selectPen(argb: Int32(bitPattern: 0x8000ff00), width: 10)
        XCTAssertTrue(note.beginStroke(x: 50, y: 40, pressure: 1, timeMicros: 2))
        XCTAssertTrue(note.endStroke(x: 50, y: 40, pressure: 1, timeMicros: 3))
        model.changed()
        let previous = model.inkFramePublications
        let pending = try pixel(viewImage(view), 50, 40) // Complete canonical fallback while pending.
        try await eventually { model.inkFramePublications > previous }
        let committed = try pixel(viewImage(view), 50, 40)
        for i in 0..<4 { XCTAssertLessThanOrEqual(abs(pending[i] - committed[i]), 2) }
        XCTAssertGreaterThan(committed[1], actual[1]) // New green object appears exactly once.
        NativeInkCanvas.dismantleUIView(view, coordinator: ())
        try await eventually { !model.inkOwnerRetained }; XCTAssertEqual(model.inkChargedBytes, 0)
        note.selectPen(argb: -16777216, width: 2)
        XCTAssertTrue(note.beginStroke(x: 100, y: 80, pressure: 1, timeMicros: 2))
        XCTAssertTrue(note.endStroke(x: 100, y: 80, pressure: 1, timeMicros: 3))
        model.changed(); model.close(); XCTAssertNil(model.current)
        model.open(id); try await eventually { model.current != nil }
        XCTAssertEqual(model.current?.totalStrokeCount, 4) // Render close must not stop shared file worker.
        model.close()
    }
    @MainActor func testPreviewViewDisappearsWhileRenderRunsAndModelAcknowledgesAfterClose() async throws {
        let started = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        var captured: (PreviewModel, InkUIView)? = try preview(before: {
            started.signal(); guard release.wait(timeout: .now()+8) == .success else { throw AppleBrushImageFailureExperiment.copy }
        })
        let model = try XCTUnwrap(captured).0
        weak var goneView = captured?.1
        var view: InkUIView? = captured?.1; captured = nil
        _ = viewImage(try XCTUnwrap(view))
        try await eventually { started.wait(timeout: .now()) == .success }
        let held = model.inkChargedBytes; XCTAssertGreaterThan(held, 0)
        NativeInkCanvas.dismantleUIView(try XCTUnwrap(view), coordinator: ())
        view = nil; XCTAssertNil(goneView); model.close(); XCTAssertNil(model.current)
        XCTAssertTrue(model.inkOwnerRetained); XCTAssertEqual(model.inkChargedBytes, held)
        release.signal()
        try await eventually { !model.inkOwnerRetained }
        XCTAssertEqual(model.inkChargedBytes, 0)
    }
    @MainActor func testPreviewUnknownReleaseStaysWithModelUntilReadOnlyReconciliation() async throws {
        var calls = 0
        let (model, view) = try preview(release: { after in
            calls += 1; if after { throw AppleBrushImageFailureExperiment.copy }
        })
        _ = viewImage(view); try await eventually { model.inkFramePublications > 0 }
        _ = viewImage(view)
        NativeInkCanvas.dismantleUIView(view, coordinator: ())
        let held = model.inkChargedBytes, attempted = calls
        XCTAssertGreaterThan(held, 0); XCTAssertTrue(model.inkOwnerRetained)
        model.close(); XCTAssertEqual(calls, attempted); XCTAssertEqual(model.inkChargedBytes, held)
        XCTAssertTrue(model.reconcileInkRelease()); XCTAssertEqual(calls, attempted)
        XCTAssertEqual(model.inkChargedBytes, 0); XCTAssertFalse(model.inkOwnerRetained)
    }
    @MainActor func testPreviewPageViewportAndSizeChangeUseCompleteFallbackBeforeNewFrame() async throws {
        let (model, view) = try preview()
        let note = view.note
        _ = viewImage(view); try await eventually { model.inkFramePublications > 0 }
        _ = viewImage(view); let drawn = model.inkFrameDraws
        XCTAssertTrue(note.insertPage()); model.changed(); view.refreshPage()
        XCTAssertEqual(try pixel(viewImage(view), 50, 40), [255,255,255,255])
        XCTAssertEqual(model.inkFrameDraws, drawn) // Old-page image cannot draw before new ACK.
        let first = model.inkFramePublications
        try await eventually { model.inkFramePublications > first }
        _ = viewImage(view)
        view.viewport.gesture(oldX: 100, oldY: 100, newX: 90, newY: 90, ratio: 1.5)
        let beforeZoom = model.inkFrameDraws
        _ = viewImage(view); XCTAssertEqual(model.inkFrameDraws, beforeZoom)
        view.frame = CGRect(x: 0, y: 0, width: 180, height: 250); view.layoutIfNeeded()
        _ = viewImage(view)
        model.flushForBackground()
        try await eventually { !model.inkOwnerRetained }
        XCTAssertEqual(model.inkChargedBytes, 0)
        model.resumeFromBackground(); _ = viewImage(view)
        let resume = model.inkFramePublications
        try await eventually { model.inkFramePublications > resume }
        _ = viewImage(view); model.close()
        try await eventually { !model.inkOwnerRetained }
    }
    @MainActor func testProviderRetentionDelaysReleaseAndForeignCallbackSettlesOnlyOnOwner() async throws {
        let (frame, stroke) = try fixture()
        let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId)
        let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame))
        try await ready(a); _ = try a.drain()
        var ownerNotified = false
        guard case .ready(let holder) = try AppleBrushImageExperiment.create(a, item, trackedSource: true,
            onSourceRelease: { XCTAssertTrue(Thread.isMainThread); ownerNotified = true }) else { return XCTFail("provider holder") }
        nonisolated(unsafe) var retained: CGDataProvider?
        try holder.retainSourceForTesting { retained = $0 }
        XCTAssertNotNil(retained)
        let context = try bitmap()
        XCTAssertTrue(try holder.drawUIKit(context, in: CGRect(x: 0, y: 0, width: 64, height: 32)))
        let bytes = try XCTUnwrap(context.data).assumingMemoryBound(to: UInt8.self)
        XCTAssertTrue((0..<2048).contains { bytes[$0 * 4 + 3] > 0 })
        try await stop(a)
        XCTAssertFalse(try holder.close()); XCTAssertFalse(holder.released)
        XCTAssertEqual(a.chargedBytes, 24576); XCTAssertFalse(a.isDrained)
        XCTAssertFalse(try holder.drawUIKit(context, in: .zero))
        XCTAssertFalse(try holder.close()) // No destructive retry and no timer-based release.
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async { retained = nil; continuation.resume() }
        }
        try await eventually { holder.released && ownerNotified }
        XCTAssertTrue(a.isDrained); XCTAssertEqual(a.chargedBytes, 0)
    }
    @MainActor func testPreviewRetainedProviderSurvivesCloseAndBlocksNewPoolUntilCallback() async throws {
        let (model, oldView) = try preview()
        _ = viewImage(oldView); try await eventually { model.inkFramePublications > 0 }
        _ = viewImage(oldView)
        nonisolated(unsafe) var retained: CGDataProvider?
        try model.retainInkSourceForTesting { retained = $0 }
        XCTAssertNotNil(retained)
        NativeInkCanvas.dismantleUIView(oldView, coordinator: ())
        model.close()
        XCTAssertTrue(model.inkOwnerRetained); let held = model.inkChargedBytes; XCTAssertGreaterThan(held, 0)
        model.create()
        let next = InkUIView(note: try XCTUnwrap(model.current)); next.renderModel = model; next.contentScaleFactor = 1
        next.frame = oldView.frame; next.layoutIfNeeded()
        let publications = model.inkFramePublications
        _ = viewImage(next)
        XCTAssertEqual(model.inkFramePublications, publications); XCTAssertEqual(model.inkChargedBytes, held)
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async { retained = nil; continuation.resume() }
        }
        try await eventually { model.inkChargedBytes == 0 }
        _ = viewImage(next)
        try await eventually { model.inkFramePublications > publications }
        _ = viewImage(next); model.close()
        try await eventually { !model.inkOwnerRetained }
        XCTAssertEqual(model.inkChargedBytes, 0)
    }
    @MainActor func testProviderUnknownReconciliationRequiresRealReleaseReceipt() async throws {
        let (frame, stroke) = try fixture()
        let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId)
        let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame))
        try await ready(a); _ = try a.drain()
        guard case .ready(let holder) = try AppleBrushImageExperiment.create(a, item, trackedSource: true,
            releaseBoundary: { if $0 { throw AppleBrushImageFailureExperiment.copy } }) else { return XCTFail("provider holder") }
        var retained: CGDataProvider?
        try holder.retainSourceForTesting { retained = $0 }
        try await stop(a)
        XCTAssertFalse(try holder.close()); XCTAssertFalse(try holder.reconcileReleased())
        XCTAssertEqual(a.chargedBytes, 24576)
        retained = nil
        XCTAssertNil(retained)
        // Actual provider callback alone must not override UNKNOWN.
        await Task.yield()
        XCTAssertFalse(holder.released); XCTAssertEqual(a.chargedBytes, 24576)
        XCTAssertTrue(try holder.reconcileReleased()); XCTAssertTrue(a.isDrained)
        XCTAssertEqual(a.chargedBytes, 0)
    }
    private func fixture() throws -> (FrameToken, StrokeObject) {
        let text = "NCPREVIEW\t2\nD\timage-page\tImage\t1\t1\t1\nG\tfirst\tink\t400\t400\tBLANK\t1\nS\tnative-image\tink\t1\tMONOLINE\t1\t-869046631\t7\t4\t1\t0\t0\t1\t0\t0\nP\t4\t10\t1\t0\nP\t16\t21\t0.25\t1\nP\t28\t11\t1\t2\nP\t4\t10\t1\t3\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let s = try XCTUnwrap(root.scene.objects().first)
        let stroke = StrokeObject(id: s.id, objectRevision: s.objectRevision, layerId: s.layerId,
            brush: BrushSpec(kind: .highlighter, algorithmVersion: 1, width: 7, argb: Int32(bitPattern: 0xcc336699)),
            samples: s.samples, transform: s.transform)
        return (FrameToken(sceneId: root.scene.id, sceneRevision: root.scene.revision, viewportVersion: 1,
            sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 1), stroke)
    }
    @MainActor private func ready(_ a: AppleBrushRenderExperiment) async throws {
        let until = Date().addingTimeInterval(8)
        while Date() < until {
            if try a.executor.status().entries.first?.stage == .ready { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("Existing Swift worker did not produce a result")
    }
    @MainActor private func stopped(_ a: AppleBrushRenderExperiment) async throws {
        let until = Date().addingTimeInterval(8)
        while !(try a.executor.status().workerStopped) && Date() < until {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertTrue(try a.executor.status().workerStopped)
    }
    @MainActor private func stop(_ a: AppleBrushRenderExperiment) async throws {
        try a.close(); try await stopped(a)
    }
    private func bitmap() throws -> CGContext {
        try XCTUnwrap(CGContext(data: nil, width: 64, height: 32, bitsPerComponent: 8, bytesPerRow: 256,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
    }
    @MainActor func testMultiTileFrameCopiesEntireNativeImageAndClosesAfterHeldReader() async throws {
        let text = "NCPREVIEW\t2\nD\timage-page\tFrame\t1\t2\t1\nG\tfirst\tink\t400\t400\tBLANK\t1\n" +
            "S\tframe-red\tink\t1\tMONOLINE\t1\t-2130771968\t10\t2\t1\t0\t0\t1\t0\t0\nP\t0\t128\t1\t0\nP\t200\t128\t1\t1\n" +
            "S\tframe-blue\tink\t1\tMONOLINE\t1\t-2147483393\t10\t2\t1\t0\t0\t1\t0\t0\nP\t0\t128\t1\t0\nP\t200\t128\t1\t1\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let layer = try XCTUnwrap(root.scene.objects().first).layerId
        let frame = FrameToken(sceneId: root.scene.id, sceneRevision: root.scene.revision, viewportVersion: 1,
            sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 1)
        let a = try AppleBrushRenderExperiment(frame: frame, layer: layer, totalBytes: 2097152, residentBytes: 1048576)
        let item = try XCTUnwrap(a.submitPage(scene: root.scene, frame: frame, layer: layer,
            originX: 0, originY: 0, width: 130, height: 130, pixelSize: 1,
            nativeAllowance: 135200, workingBytes: 1048576))
        try await ready(a); XCTAssertEqual(a.chargedBytes, item.job.peakBytes)
        XCTAssertEqual(try a.drain(), ["PUBLISHED"])
        guard case .ready(let image) = try AppleBrushImageExperiment.create(a, item) else { return XCTFail("Frame image missing") }
        let target = try XCTUnwrap(CGContext(data: nil, width: 130, height: 130, bitsPerComponent: 8, bytesPerRow: 520,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
        XCTAssertEqual(image.rowStorageUpperBytes, 135200)
        XCTAssertTrue(try image.draw(target, in: CGRect(x: 0, y: 0, width: 130, height: 130)))
        let bytes = try XCTUnwrap(target.data).assumingMemoryBound(to: UInt8.self)
        for y in 127...129 { for x in 127...129 {
            let p = (y * 130 + x) * 4
            XCTAssertEqual(Int(bytes[p]), 64); XCTAssertEqual(Int(bytes[p+1]), 0)
            XCTAssertEqual(Int(bytes[p+2]), 128); XCTAssertEqual(Int(bytes[p+3]), 192)
        } }
        try await stop(a); XCTAssertFalse(a.isDrained); XCTAssertEqual(a.chargedBytes, 202800)
        XCTAssertTrue(try image.close()); XCTAssertTrue(a.isDrained); XCTAssertEqual(a.chargedBytes, 0)
    }
    @MainActor func testCompleteCanonicalPageCompositesBeforeAckAndDrainsHeldImage() async throws {
        let text = "NCPREVIEW\t2\nD\timage-page\tPage\t1\t2\t1\nG\tfirst\tink\t400\t400\tBLANK\t1\n" +
            "S\tpage-red\tink\t1\tMONOLINE\t1\t-2130771968\t10\t1\t1\t0\t0\t1\t0\t0\nP\t8\t12\t1\t0\n" +
            "S\tpage-blue\tink\t1\tMONOLINE\t1\t-2147483393\t10\t1\t1\t0\t0\t1\t0\t0\nP\t8\t12\t1\t0\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let layer = try XCTUnwrap(root.scene.objects().first).layerId
        let frame = FrameToken(sceneId: root.scene.id, sceneRevision: root.scene.revision, viewportVersion: 1,
            sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 1)
        let a = try AppleBrushRenderExperiment(frame: frame, layer: layer)
        var deliveries = 0
        let item = try XCTUnwrap(a.submitPage(scene: root.scene, frame: frame, layer: layer,
            permitDelivery: { deliveries += 1; XCTAssertThrowsError(try a.settleAfterDrain()); return deliveries > 1 }))
        try await ready(a); XCTAssertEqual(try a.drain(), []); XCTAssertEqual(a.chargedBytes, item.job.peakBytes)
        XCTAssertTrue(try a.retry(item)); XCTAssertEqual(try a.drain(), ["PUBLISHED"])
        guard case .ready(let image) = try AppleBrushImageExperiment.create(a, item) else { return XCTFail("Page image missing") }
        let target = try bitmap()
        XCTAssertTrue(try image.draw(target, in: CGRect(x: 0, y: 0, width: 64, height: 32)))
        let bytes = try XCTUnwrap(target.data).assumingMemoryBound(to: UInt8.self)
        // Image payload row order is preserved in the controlled bitmap target.
        let center = (8 * 64 + 15) * 4
        XCTAssertEqual(Int(bytes[center]), 64); XCTAssertEqual(Int(bytes[center + 1]), 0)
        XCTAssertEqual(Int(bytes[center + 2]), 128); XCTAssertEqual(Int(bytes[center + 3]), 192)
        try await stop(a); XCTAssertFalse(a.isDrained); XCTAssertEqual(a.chargedBytes, 24576)
        XCTAssertFalse(try image.draw(target, in: .zero)); XCTAssertTrue(try image.close())
        XCTAssertTrue(a.isDrained); XCTAssertEqual(a.chargedBytes, 0)
    }
    @MainActor func testSwiftWorkerRendersActualPixelsAndCloseKeepsNativeBudget() async throws {
        let (frame, stroke) = try fixture()
        let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId, totalBytes: 100000)
        let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame))
        try await ready(a); XCTAssertEqual(a.chargedBytes, item.job.peakBytes)
        XCTAssertEqual(try a.drain(), ["PUBLISHED"]); XCTAssertEqual(a.chargedBytes, 24576)
        // Invalid second execution fails inside Kotlin and becomes UNKNOWN rather than crossing
        // the unannotated ObjC boundary as a host-terminating Kotlin exception.
        XCTAssertTrue(item.job.execute { KotlinBoolean(bool: false) }.outcomeUnknown)
        guard case .ready(let image) = try AppleBrushImageExperiment.create(a, item) else { return XCTFail("CGImage missing") }
        guard case .rejected = try AppleBrushImageExperiment.create(a, item) else { return XCTFail("Duplicate native permit") }
        let target = try bitmap(); XCTAssertTrue(try image.draw(target, in: CGRect(x: 0, y: 0, width: 64, height: 32)))
        let bytes = try XCTUnwrap(target.data).assumingMemoryBound(to: UInt8.self)
        var full = 0, partial = 0
        for i in 0..<2048 {
            let alpha = Int(bytes[i * 4 + 3]); if alpha == 51 { full += 1 }; if alpha > 0 && alpha < 51 { partial += 1 }
            for (channel, color) in [51, 102, 153].enumerated() {
                XCTAssertLessThanOrEqual(abs(Int(bytes[i * 4 + channel]) - (color * alpha + 127) / 255), 1)
            }
        }
        XCTAssertGreaterThan(full, 0); XCTAssertGreaterThan(partial, 0)
        XCTAssertNil(try a.submit(stroke: stroke, frame: frame)) // held 24576 blocks another 90320
        try await stop(a); XCTAssertFalse(a.isDrained); XCTAssertEqual(a.chargedBytes, 24576)
        XCTAssertFalse(try image.draw(target, in: .zero)); XCTAssertTrue(try image.close()); XCTAssertTrue(try image.close())
        XCTAssertTrue(a.isDrained); XCTAssertEqual(a.chargedBytes, 0)
    }
    @MainActor func testExactResultRedeliveryAndFrameChangeBeforeAck() async throws {
        let (frame, stroke) = try fixture(); let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId)
        var computes = 0, deliveries = 0
        let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame, beforeCompute: { computes += 1 },
            permitDelivery: { deliveries += 1; XCTAssertThrowsError(try a.settleAfterDrain()); return deliveries > 1 }))
        try await ready(a); XCTAssertEqual(try a.drain(), []); XCTAssertEqual(a.chargedBytes, item.job.peakBytes)
        let moved = FrameToken(sceneId: frame.sceneId, sceneRevision: frame.sceneRevision, viewportVersion: 2,
            sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 2)
        try a.expect(moved, layer: stroke.layerId); XCTAssertTrue(try a.retry(item))
        XCTAssertEqual(try a.drain(), ["DISCARDED"]); XCTAssertEqual(computes, 1); XCTAssertEqual(deliveries, 2)
        XCTAssertEqual(a.chargedBytes, 0); try await stop(a); XCTAssertTrue(a.isDrained)
    }
    @MainActor func testCancelledQueuedJobAndUnknownComputeRequireAckAndReconciliation() async throws {
        let (frame, stroke) = try fixture(); let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId)
        let release = DispatchSemaphore(value: 0)
        let unknown = try XCTUnwrap(a.submit(stroke: stroke, frame: frame, beforeCompute: {
            _ = release.wait(timeout: .now() + 5); throw AppleBrushImageFailureExperiment.copy
        }))
        let cancelled = try XCTUnwrap(a.submit(stroke: stroke, frame: frame)); try a.cancel(cancelled)
        try a.close(); XCTAssertEqual(a.chargedBytes, unknown.job.peakBytes + cancelled.job.peakBytes)
        release.signal(); try await stopped(a)
        XCTAssertEqual(try a.drain(), ["QUARANTINED", "FAILED"])
        XCTAssertEqual(a.chargedBytes, unknown.job.peakBytes); XCTAssertFalse(a.isDrained)
        XCTAssertTrue(try a.reconcileNoOutput(unknown)); XCTAssertFalse(try a.reconcileNoOutput(unknown)); XCTAssertTrue(a.isDrained)
    }
    @MainActor func testCopyFailureCleanupUnknownReturnsHolderAndNoDestructiveRetry() async throws {
        let (frame, stroke) = try fixture(); let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId)
        let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame)); try await ready(a); _ = try a.drain()
        guard case .rejected = try AppleBrushImageExperiment.create(a, item, afterCopy: { throw AppleBrushImageFailureExperiment.copy }) else {
            return XCTFail("Known cleanup should reject without leaking native permit")
        }
        var calls = 0
        guard case .cleanupRequired(let holder) = try AppleBrushImageExperiment.create(a, item,
            afterCopy: { throw AppleBrushImageFailureExperiment.copy }, releaseBoundary: { after in
                calls += 1; if after { throw AppleBrushImageFailureExperiment.copy }
            }) else { return XCTFail("Must return cleanup responsibility") }
        try await stop(a); XCTAssertEqual(a.chargedBytes, 24576); XCTAssertFalse(a.isDrained)
        XCTAssertFalse(try holder.close()); XCTAssertEqual(calls, 2)
        XCTAssertTrue(try holder.reconcileReleased()); XCTAssertTrue(a.isDrained); XCTAssertEqual(a.chargedBytes, 0)
    }
    @MainActor func testOldImageRejectsDrawAndInsufficientCopyBudgetKeepsPermitFree() async throws {
        let (frame, stroke) = try fixture(); let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId)
        let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame)); try await ready(a); _ = try a.drain()
        guard case .ready(let holder) = try AppleBrushImageExperiment.create(a, item) else { return XCTFail("holder") }
        let moved = FrameToken(sceneId: frame.sceneId, sceneRevision: frame.sceneRevision, viewportVersion: 2,
            sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 2)
        try a.expect(moved, layer: stroke.layerId)
        XCTAssertFalse(try holder.draw(try bitmap(), in: .zero)); XCTAssertEqual(a.chargedBytes, 24576)
        XCTAssertTrue(try holder.close()); XCTAssertEqual(a.chargedBytes, 0)
        let small = try XCTUnwrap(a.submit(stroke: stroke, frame: moved, nativeAllowance: 8192))
        try await ready(a); _ = try a.drain()
        guard case .rejected(let reason) = try AppleBrushImageExperiment.create(a, small) else { return XCTFail("budget") }
        XCTAssertEqual(reason, "native-copy-budget")
        let reader = try XCTUnwrap(a.owner.borrowNative(job: small.job))
        XCTAssertTrue(a.owner.release(reader: reader, unknown: false))
        try await stop(a); XCTAssertTrue(a.isDrained)
    }
    @MainActor func testForeignThreadCannotReleaseHolderAndThrowingReceiveRedelivers() async throws {
        let (frame, stroke) = try fixture(); let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId)
        var delivery = 0, computes = 0
        let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame, beforeCompute: { computes += 1 }, permitDelivery: {
            delivery += 1; if delivery == 1 { throw AppleBrushImageFailureExperiment.copy }; return true
        }))
        try await ready(a); XCTAssertEqual(try a.drain(), []); XCTAssertEqual(a.chargedBytes, item.job.peakBytes)
        XCTAssertTrue(try a.retry(item)); XCTAssertEqual(try a.drain(), ["PUBLISHED"])
        XCTAssertEqual(computes, 1); XCTAssertEqual(delivery, 2)
        guard case .ready(let holder) = try AppleBrushImageExperiment.create(a, item) else { return XCTFail("holder") }
        // Intentional owner-violation probe: close checks the executor before touching image state.
        nonisolated(unsafe) let foreignHolder = holder
        let wrongOwner = await withCheckedContinuation { continuation in
            DispatchQueue.global().async {
                do { _ = try foreignHolder.close(); continuation.resume(returning: false) }
                catch AppleExecutorError.wrongOwner { continuation.resume(returning: true) }
                catch { continuation.resume(returning: false) }
            }
        }
        XCTAssertTrue(wrongOwner); XCTAssertEqual(a.chargedBytes, 24576)
        try await stop(a); XCTAssertTrue(try holder.close()); XCTAssertTrue(a.isDrained)
    }

    @MainActor func testFixedScrollZoomQueueAndHeldReaderTrace() async throws {
        let (frame, stroke) = try fixture()
        let a = try AppleBrushRenderExperiment(frame: frame, layer: stroke.layerId, totalBytes: 210000, maxJobs: 2)
        let traceBegin = DispatchTime.now().uptimeNanoseconds
        let gate = DispatchSemaphore(value: 0), lock = NSLock()
        var starts: [UInt64] = [], admissions: [(UInt64, UInt64)] = [], events: [[String: Any]] = [], copies: [[String: Any]] = []
        var images: [AppleBrushImageExperiment] = []
        defer { gate.signal(); try? a.close(); for image in images { _ = try? image.close() } }
        func event(_ name: String) throws {
            events.append(["event": name, "elapsed_ns": DispatchTime.now().uptimeNanoseconds - traceBegin, "charged": a.chargedBytes, "retired": a.owner.retiredBytes,
                "executor": try a.executor.status().chargedBytes])
        }
        func submit(_ frame: FrameToken, x: Double, scale: Double, blocked: Bool = false) throws -> AppleBrushDispatchExperiment {
            let begin = DispatchTime.now().uptimeNanoseconds
            let item = try XCTUnwrap(a.submit(stroke: stroke, frame: frame, originX: x, pixelSize: scale, beforeCompute: {
                let start = DispatchTime.now().uptimeNanoseconds
                lock.lock(); starts.append(start); lock.unlock()
                if blocked { precondition(gate.wait(timeout: .now() + 5) == .success) }
            }))
            admissions.append((begin, DispatchTime.now().uptimeNanoseconds)); return item
        }
        func copy(_ item: AppleBrushDispatchExperiment) throws {
            let begin = DispatchTime.now().uptimeNanoseconds
            guard case .ready(let image) = try AppleBrushImageExperiment.create(a, item) else { return XCTFail("trace image") }
            copies.append(["elapsed_ns": DispatchTime.now().uptimeNanoseconds - begin,
                "row_storage_upper_bytes": image.rowStorageUpperBytes]); images.append(image)
        }
        let first = try submit(frame, x: 0.25, scale: 0.5)
        try await ready(a); XCTAssertEqual(try a.drain(), ["PUBLISHED"]); try copy(first); try event("first-held")
        let scroll = FrameToken(sceneId: frame.sceneId, sceneRevision: frame.sceneRevision, viewportVersion: 2,
            sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 2)
        try a.expect(scroll, layer: stroke.layerId); try event("scroll-old-retired")
        XCTAssertFalse(try images[0].draw(try bitmap(), in: .zero))
        _ = try submit(scroll, x: 4.25, scale: 0.5, blocked: true)
        let untilRunning = Date().addingTimeInterval(8)
        while try a.executor.status().entries.first?.stage != .running && Date() < untilRunning {
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertEqual(try a.executor.status().entries.first?.stage, .running)
        let cancelled = try submit(scroll, x: 4.25, scale: 0.5)
        XCTAssertEqual(try a.executor.status().entries.last?.stage, .queued)
        XCTAssertNil(try a.submit(stroke: stroke, frame: scroll, originX: 4.25))
        try event("two-jobs-rejected-third"); try a.cancel(cancelled); try event("queued-cancelled")
        gate.signal()
        let untilReady = Date().addingTimeInterval(8)
        while try a.executor.status().entries.contains(where: { $0.stage != .ready }) && Date() < untilReady {
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTAssertTrue(try a.executor.status().entries.allSatisfy { $0.stage == .ready }); try event("ready-before-ack")
        let zoom = FrameToken(sceneId: frame.sceneId, sceneRevision: frame.sceneRevision, viewportVersion: 3,
            sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 3)
        try a.expect(zoom, layer: stroke.layerId); XCTAssertEqual(try a.drain(), ["DISCARDED", "FAILED"])
        try event("late-and-cancelled-ack")
        let last = try submit(zoom, x: 4.25, scale: 0.25)
        try await ready(a); XCTAssertEqual(try a.drain(), ["PUBLISHED"]); try copy(last); try event("zoom-image-held")
        try await stop(a); try event("closed-two-readers"); XCTAssertFalse(a.isDrained)
        XCTAssertTrue(try images[1].close()); try event("new-reader-released")
        XCTAssertTrue(try images[0].close()); try event("old-reader-released"); XCTAssertTrue(a.isDrained)
        // All worker closures finished; read the diagnostic under the same lock.
        let workerStarts: [UInt64] = { lock.lock(); defer { lock.unlock() }; return starts }()
        XCTAssertEqual(workerStarts.count, 4)
        let queueWaits = zip(workerStarts, admissions).map { start, call -> [String: UInt64] in
            ["submit_elapsed_ns": call.1 - call.0, "request_to_worker_ns": start - call.0,
             "queue_handoff_wait_lower_ns": start > call.1 ? start - call.1 : 0,
             "queue_handoff_wait_upper_ns": start - call.0]
        }
        let trace: [String: Any] = ["trace_id": "dev034-fixed-viewport-v1", "events": events,
            "queue_wait_ns": queueWaits, "copies": copies, "total_budget": 210000, "resident_budget": 32768,
            "max_jobs": 2, "input_points": 4, "viewports": [[0.25, 8, 0.5], [4.25, 8, 0.5], [4.25, 8, 0.25]],
            "timing_scope": "One iOS Simulator run; Admission-to-beforeCompute wait bounded by submit call timestamps; includes controlled worker gate and handoff overhead; copy is create API elapsed, not heap/GPU",
            "closed_drained": a.isDrained]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: trace, options: [.sortedKeys]),
            uniformTypeIdentifier: "public.json")
        attachment.name = "notecore-apple-render-trace.json"; attachment.lifetime = .keepAlways; add(attachment)
    }

}
