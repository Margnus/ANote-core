import XCTest
import CoreGraphics
import NoteCanvasPrototype
@testable import NoteCorePreview

// Actual app-host Kotlin/Native render -> existing Swift worker -> CGImage responsibility.
final class BrushRenderHostTests: XCTestCase {
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
            permitDelivery: { deliveries += 1; return deliveries > 1 }))
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
            permitDelivery: { deliveries += 1; return deliveries > 1 }))
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
