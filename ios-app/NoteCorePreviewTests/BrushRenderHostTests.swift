import XCTest
import CoreGraphics
import CoreText
import CryptoKit
import ImageIO
import UIKit
import NoteCanvasPrototype
@testable import NoteCorePreview

// Actual app-host Kotlin/Native render -> existing Swift worker -> CGImage responsibility.
final class BrushRenderHostTests: XCTestCase {
    @MainActor func testOriginalProviderInputHistoryAndSaveFlightRetainIndependentResponsibilities() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("notecore-owner-overlap-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let lock = NSLock(), entered = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        var armWrite = false
        defer { release.signal() } // Cleanup only; successful assertions below require explicit real IO completion.
        let budget = try DocumentBudget(maxPages: 128, maxObjects: 2, liveBytes: 8*1024*1024,
            journalEntries: 4096, journalBytes: 32*1024*1024, identityBytes: 4*1024*1024,
            historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024)
        let model = PreviewModel(directory: directory, documentBudget: budget, observeWriteBoundary: { boundary in
            guard boundary == .beforeAtomicWrite else { return }
            let pause = lock.withLock { let value = armWrite; armWrite = false; return value }
            if pause { entered.signal(); release.wait() }
        })
        model.create(); let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.setPaper(width: 200, height: 200, paper: .blank))
        for x in [40.0, 80.0] {
            XCTAssertTrue(note.beginStroke(x: x, y: 40, pressure: 0.25, timeMicros: 1))
            XCTAssertTrue(note.endStroke(x: x+5, y: 45, pressure: 0.75, timeMicros: 2))
        }
        model.changed()
        let file = directory.appendingPathComponent(note.documentId + ".ncp")
        try await eventually { model.ownerRetirementStatus.pendingWrites == 0 && model.status.hasPrefix("已保存") }
        let view = InkUIView(note: note); view.renderModel = model; view.contentScaleFactor = 1
        view.frame = CGRect(x: 0, y: 0, width: 200, height: 200); view.layoutIfNeeded()
        _ = viewImage(view); try await eventually { model.inkFramePublications > 0 }
        var provider: CGDataProvider?
        try model.retainInkSourceForTesting { provider = $0 }
        XCTAssertNotNil(provider); let nativeBytes = model.inkChargedBytes; XCTAssertGreaterThan(nativeBytes, 0)
        // Inspect the original ledger only; never create a test replacement authority or ledger.
        let ledger = try XCTUnwrap(Mirror(reflecting: model).children.first { $0.label == "ledger" }?.value as? PreviewSaveLedger)
        XCTAssertTrue(note.rename(value: "Old write still owns this root"))
        let old = note.captureRecovery(), oldBytes = try old.encode()
        lock.withLock { armWrite = true }
        XCTAssertTrue(model.save())
        try await eventually { entered.wait(timeout: .now()) == .success }
        XCTAssertTrue(ledger.states().count == 1 && ledger.states()[0].inFlight)
        XCTAssertEqual(ledger.chargedBytes, old.retainedByteCharge)
        let full = note.snapshot()
        XCTAssertFalse(note.beginStroke(x: 100, y: 100, pressure: 0.5, timeMicros: 3))
        XCTAssertTrue(note.snapshot() === full); XCTAssertFalse(note.hasActiveStroke); XCTAssertFalse(note.hasPendingStroke)
        XCTAssertTrue(note.undo()); XCTAssertTrue(note.undo()) // Undo rename and the second stroke; history remains charged.
        XCTAssertEqual(note.totalStrokeCount, 1)
        XCTAssertGreaterThan(note.authorityStatus().historyByteCharge, 0)
        let middle = note.captureRecovery(); XCTAssertTrue(model.save())
        XCTAssertEqual(ledger.chargedBytes, old.retainedByteCharge + middle.retainedByteCharge)
        XCTAssertTrue(note.beginStroke(x: 120, y: 140, pressure: 0.125, timeMicros: 7))
        let input = note.activeInputOperationId, inputBytes = note.inputReservedByteCharge
        XCTAssertGreaterThan(inputBytes, 0); XCTAssertNotNil(input)
        let activeRoot = note.snapshot(), historyBytes = note.authorityStatus().historyByteCharge
        let invalid = note.edit(request: PreviewEditSetPaper(width: .nan, height: 200, paper: .blank))
        XCTAssertFalse(invalid.accepted); XCTAssertEqual(invalid.failure?.kind, .invalidArgument)
        XCTAssertTrue(note.snapshot() === activeRoot)
        XCTAssertEqual(model.beginOwnerRetirement(), .inputPending)
        XCTAssertEqual(note.activeInputOperationId, input); XCTAssertEqual(note.inputReservedByteCharge, inputBytes)
        XCTAssertEqual(ledger.chargedBytes, old.retainedByteCharge + middle.retainedByteCharge)
        XCTAssertEqual(model.inkChargedBytes, nativeBytes); XCTAssertNotNil(provider)
        XCTAssertTrue(note.endStroke(x: 150, y: 160, pressure: 0.875, timeMicros: 9))
        XCTAssertEqual(note.inputReservedByteCharge, 0); XCTAssertEqual(note.totalStrokeCount, 2)
        let latest = note.captureRecovery(), latestBytes = try latest.encode()
        XCTAssertNotEqual(oldBytes, latestBytes); XCTAssertTrue(model.save())
        XCTAssertEqual(ledger.chargedBytes, old.retainedByteCharge + latest.retainedByteCharge)
        var finished = false
        XCTAssertEqual(model.beginOwnerRetirement { state in
            XCTAssertEqual(state.renderBytes, 0); XCTAssertEqual(state.pendingWrites, 0); XCTAssertTrue(state.workerStopped)
            finished = true
        }, .started)
        XCTAssertNil(model.current); XCTAssertEqual(note.authorityStatus().historyByteCharge, 0)
        XCTAssertGreaterThan(ledger.chargedBytes, 0); XCTAssertFalse(finished)
        NativeInkCanvas.dismantleUIView(view, coordinator: ())
        XCTAssertGreaterThan(model.inkChargedBytes, 0)
        release.signal() // Let the original worker really write old, ACK it, then write latest.
        try await eventually { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertEqual(ledger.chargedBytes, 0)
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), latestBytes)
        XCTAssertEqual(model.ownerRetirementStatus.phase, .retiring); XCTAssertFalse(finished)
        XCTAssertFalse(model.ownerRetirementStatus.workerStopped); XCTAssertGreaterThan(model.inkChargedBytes, 0)
        provider = nil // The real provider callback, not elapsed time, permits native settlement.
        try await eventually { finished }
        XCTAssertEqual(model.inkChargedBytes, 0); XCTAssertTrue(model.ownerRetirementStatus.workerStopped)
        let reopened = try PreviewRecoveryCodec.shared.decode(text: String(contentsOf: file, encoding: .utf8))
        XCTAssertTrue(reopened.sameContent(other: latest)); XCTAssertNil(reopened.pending)
        let trace: [String: Any] = ["trace_id": "c1-original-owner-overlap-v1", "max_objects": 2,
            "held_native_declared_bytes": nativeBytes, "active_input_declared_bytes": inputBytes,
            "history_declared_bytes_at_overlap": historyBytes,
            "old_flight_declared_bytes": old.retainedByteCharge, "new_latest_declared_bytes": middle.retainedByteCharge,
            "capacity_refused_without_mutation": true, "active_input_survived_retirement_refusal": true,
            "latest_file_preserved_after_old_ack": true, "provider_release_required_for_worker_stop": true,
            "final_strokes": 2, "ledger_drained": ledger.isDrained, "native_drained": model.inkChargedBytes == 0,
            "worker_stopped": model.ownerRetirementStatus.workerStopped, "physical_heap_measured": false,
            "unified_peak_admission_verified": false]
        let attachment = XCTAttachment(data: try JSONSerialization.data(withJSONObject: trace, options: [.sortedKeys]), uniformTypeIdentifier: "public.json")
        attachment.name = "notecore-original-owner-overlap.json"; attachment.lifetime = .keepAlways; add(attachment)
        if finished && testRun?.failureCount == 0 { try FileManager.default.removeItem(at: directory) }
    }
    @MainActor func testOwnerReferenceDropKeepsProviderUntilOriginalRetirement() async throws {
        var fixture: (PreviewModel, InkUIView)? = try preview()
        var model: PreviewModel? = fixture?.0
        var view: InkUIView? = fixture?.1; fixture = nil
        weak var observer = model
        let identity = ObjectIdentifier(try XCTUnwrap(model))
        _ = viewImage(try XCTUnwrap(view))
        try await eventually { (model?.inkFramePublications ?? 0) > 0 && model?.ownerRetirementStatus.pendingWrites == 0 }
        var heldProvider: CGDataProvider?
        try model?.retainInkSourceForTesting { heldProvider = $0 }
        XCTAssertNotNil(heldProvider)
        model = nil
        // Yield beyond delivery/ACK, without interpreting elapsed time as a release receipt.
        await Task.yield()
        model = try XCTUnwrap(observer)
        XCTAssertEqual(ObjectIdentifier(model!), identity)
        XCTAssertGreaterThan(model?.ownerRetirementStatus.renderBytes ?? 0, 0)
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertTrue(status.workerStopped); XCTAssertEqual(status.renderBytes, 0); finished = true
        }, .started)
        NativeInkCanvas.dismantleUIView(try XCTUnwrap(view), coordinator: ())
        view = nil; model = nil
        XCTAssertNotNil(observer); XCTAssertFalse(finished)
        XCTAssertGreaterThan(observer?.ownerRetirementStatus.renderBytes ?? 0, 0)
        heldProvider = nil
        try await eventually { finished && observer == nil }
    }
    @MainActor func testRetiringOwnerKeepsUnknownUntilReadOnlyReleaseReconciliation() async throws {
        var releaseCalls = 0
        var fixture: (PreviewModel, InkUIView)? = try preview(release: { after in
            releaseCalls += 1
            if after { throw AppleBrushImageFailureExperiment.copy }
        })
        var model: PreviewModel? = fixture?.0
        var view: InkUIView? = fixture?.1; fixture = nil
        weak var observer = model
        _ = viewImage(try XCTUnwrap(view))
        try await eventually { (model?.inkFramePublications ?? 0) > 0 }
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertEqual(status.renderBytes, 0); XCTAssertTrue(status.workerStopped); finished = true
        }, .started)
        view = nil; model = nil
        let attempted = releaseCalls
        XCTAssertGreaterThan(attempted, 0); XCTAssertFalse(finished); XCTAssertNotNil(observer)
        var reconciled = false
        try await eventually { reconciled = observer?.reconcileInkRelease() ?? false; return reconciled }
        XCTAssertEqual(releaseCalls, attempted) // Reconciliation cannot repeat destructive cleanup.
        try await eventually { finished && observer == nil }
    }
    @MainActor func testRetiringOwnerSurvivesExternalProviderUntilRealReleaseAndWorkerStop() async throws {
        var fixture: (PreviewModel, InkUIView)? = try preview()
        var model: PreviewModel? = fixture?.0
        var view: InkUIView? = fixture?.1; fixture = nil
        weak var observer = model
        _ = viewImage(try XCTUnwrap(view))
        try await eventually { (model?.inkFramePublications ?? 0) > 0 }
        var heldProvider: CGDataProvider?
        try model?.retainInkSourceForTesting { heldProvider = $0 }
        XCTAssertNotNil(heldProvider)
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertEqual(status.phase, .retired); XCTAssertTrue(status.workerStopped)
            XCTAssertEqual(status.renderBytes, 0); finished = true
        }, .started)
        NativeInkCanvas.dismantleUIView(try XCTUnwrap(view), coordinator: ())
        view = nil; model = nil
        XCTAssertNotNil(observer); XCTAssertFalse(finished)
        XCTAssertGreaterThan(observer?.ownerRetirementStatus.renderBytes ?? 0, 0)
        heldProvider = nil // The real CG provider callback must settle the original permit.
        try await eventually { finished && observer == nil }
    }
    @MainActor func testRetiringOwnerAcknowledgesLateWorkerAndFinishesQueuedSaveBeforeRelease() async throws {
        let started = DispatchSemaphore(value: 0), release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        var fixture: (PreviewModel, InkUIView)? = try preview(before: {
            started.signal()
            guard release.wait(timeout: .now()+8) == .success else { throw AppleBrushImageFailureExperiment.copy }
        })
        var model: PreviewModel? = fixture?.0
        var view: InkUIView? = fixture?.1; fixture = nil
        weak var observer = model
        _ = viewImage(try XCTUnwrap(view))
        try await eventually { started.wait(timeout: .now()) == .success }
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertTrue(status.workerStopped); XCTAssertEqual(status.pendingWrites, 0)
            XCTAssertEqual(status.renderBytes, 0); finished = true
        }, .started)
        view = nil; model = nil
        XCTAssertNotNil(observer); XCTAssertFalse(finished)
        release.signal()
        try await eventually { finished && observer == nil }
    }
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
            XCTAssertTrue(note.selectPen(argb: Int32(bitPattern: color), width: 10, kind: .monoline))
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
        XCTAssertTrue(note.selectPen(argb: Int32(bitPattern: 0x8000ff00), width: 10, kind: .monoline))
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
        XCTAssertTrue(note.selectPen(argb: -16777216, width: 2, kind: .monoline))
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
    @MainActor func testSweptWorkerDiscardsObsoleteRasterAndWaitsForProviderRelease() async throws {
        let points = (0...256).map { i in
            "P\t\(4.0 + Double(min(i, 256 - i)) / 8.0)\t8\t1\t\(i)\n"
        }.joined()
        let text = "NCPREVIEW\t2\nD\tswept-image\tSwept\t1\t1\t1\nG\tfirst\tink\t32\t16\tBLANK\t1\n" +
            "S\tretrace\tink\t1\tHIGHLIGHTER\t1\t-1\t8\t257\t1\t0\t0\t1\t0\t0\n" + points + "END\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let stroke = try XCTUnwrap(root.scene.objects().first)
        let initial = FrameToken(sceneId: root.scene.id, sceneRevision: root.scene.revision,
            viewportVersion: 1, sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 1)
        let current = FrameToken(sceneId: root.scene.id, sceneRevision: root.scene.revision,
            viewportVersion: 2, sessionGeneration: 1, surfaceGeneration: 1, requestSequence: 2)
        let adapter = try AppleBrushRenderExperiment(frame: initial, layer: stroke.layerId)
        defer { try? adapter.close() }
        var permit = false
        let old = try XCTUnwrap(adapter.submitSwept(stroke: stroke, frame: initial, originX: 0, originY: 0,
            width: 32, height: 16, pixelSize: 1, nativeAllowance: 4096, permitDelivery: { permit }))
        try await ready(adapter)
        XCTAssertEqual(try adapter.drain(), [])
        XCTAssertEqual(adapter.chargedBytes, old.job.peakBytes)
        try adapter.expect(current, layer: stroke.layerId)
        permit = true
        XCTAssertTrue(try adapter.retry(old))
        XCTAssertEqual(try adapter.drain(), ["DISCARDED"])
        XCTAssertEqual(adapter.chargedBytes, 0)
        XCTAssertNil(adapter.owner.borrowNative(job: old.job))

        let active = try XCTUnwrap(adapter.submitSwept(stroke: stroke, frame: current, originX: 0, originY: 0,
            width: 32, height: 16, pixelSize: 1, nativeAllowance: 4096))
        try await ready(adapter)
        XCTAssertEqual(try adapter.drain(), ["PUBLISHED"])
        var ownerNotified = false
        guard case .ready(let holder) = try AppleBrushImageExperiment.create(adapter, active, trackedSource: true,
            onSourceRelease: { XCTAssertTrue(Thread.isMainThread); ownerNotified = true })
        else { return XCTFail("Swept provider missing") }
        nonisolated(unsafe) var retained: CGDataProvider?
        try holder.retainSourceForTesting { retained = $0 }
        XCTAssertNotNil(retained)
        let target = try XCTUnwrap(CGContext(data: nil, width: 32, height: 16, bitsPerComponent: 8,
            bytesPerRow: 128, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
        XCTAssertTrue(try holder.drawUIKit(target, in: CGRect(x: 0, y: 0, width: 32, height: 16)))
        let bytes = try XCTUnwrap(target.data).assumingMemoryBound(to: UInt8.self)
        XCTAssertEqual(bytes[(8 * 32 + 16) * 4 + 3], 127)
        XCTAssertEqual(holder.rowStorageUpperBytes, 2048)

        try await stop(adapter)
        XCTAssertFalse(try holder.close())
        XCTAssertEqual(adapter.chargedBytes, 6144)
        XCTAssertFalse(adapter.isDrained)
        XCTAssertFalse(try holder.drawUIKit(target, in: .zero))
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async { retained = nil; continuation.resume() }
        }
        try await eventually { holder.released && ownerNotified }
        XCTAssertTrue(adapter.isDrained)
        XCTAssertEqual(adapter.chargedBytes, 0)
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

// Isolated fixed-grid composition. These stamps describe the actual inputs used by this backend.
private enum MixedTileDependency: Equatable {
    struct Glyph: Equatable {
        let font: String
        let id: CGGlyph
        let position: CGPoint
    }
    case missing(source: String)
    case image(source: String, width: Int, height: Int, mirrored: Bool)
    case text([Glyph])
}

private struct MixedTilePlan {
    let slot: NSString
    let dependency: MixedTileDependency
    let draw: (CGContext) -> Void
}

private final class MixedTileReleaseReceipt {
    private let lock = NSLock()
    private var freed = false
    var confirmed: Bool { lock.withLock { freed } }
    func release(_ data: UnsafeRawPointer) {
        UnsafeMutableRawPointer(mutating: data).deallocate()
        lock.withLock { precondition(!freed); freed = true }
    }
}

private final class MixedNativeTile: NSObject {
    static let width = 96, height = 120, stride = width * 4
    static let bytes = Int64(stride * height)
    static let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    static let layout = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
    let dependency: MixedTileDependency
    let receipt = MixedTileReleaseReceipt()
    var image: CGImage?

    init(plan: MixedTilePlan) throws {
        dependency = plan.dependency
        let bytes = Int(Self.bytes)
        let data = UnsafeMutableRawPointer.allocate(byteCount: bytes, alignment: 16)
        guard let context = CGContext(data: data, width: Self.width, height: Self.height,
            bitsPerComponent: 8, bytesPerRow: Self.stride, space: Self.colorSpace, bitmapInfo: Self.layout) else {
            data.deallocate()
            throw CocoaError(.coderInvalidValue)
        }
        let rect = CGRect(x: 0, y: 0, width: Self.width, height: Self.height)
        context.setFillColor(CGColor(gray: 1, alpha: 1))
        context.fill(rect)
        plan.draw(context)
        let info = Unmanaged.passRetained(receipt).toOpaque()
        guard let provider = CGDataProvider(dataInfo: info, data: data, size: bytes, releaseData: { info, data, _ in
            Unmanaged<MixedTileReleaseReceipt>.fromOpaque(info!).takeRetainedValue().release(data)
        }) else {
            Unmanaged<MixedTileReleaseReceipt>.fromOpaque(info).release()
            data.deallocate()
            throw CocoaError(.coderInvalidValue)
        }
        image = try XCTUnwrap(CGImage(width: Self.width, height: Self.height, bitsPerComponent: 8,
            bitsPerPixel: 32, bytesPerRow: Self.stride, space: Self.colorSpace,
            bitmapInfo: CGBitmapInfo(rawValue: Self.layout), provider: provider, decode: nil,
            shouldInterpolate: false, intent: .defaultIntent))
    }

    func pixels() throws -> Data {
        let data = try XCTUnwrap(image?.dataProvider?.data) as Data
        XCTAssertEqual(Int64(data.count), Self.bytes)
        return data
    }
}

extension BrushRenderHostTests {
    @MainActor func testNativeMixedTileDependenciesAndProviderRetirement() async throws {
        let rect = CGRect(x: 0, y: 0, width: MixedNativeTile.width, height: MixedNativeTile.height)
        let slotImage: NSString = "image", slotText: NSString = "text"
        let source = try XCTUnwrap(CGContext(data: nil, width: 80, height: 120, bitsPerComponent: 8,
            bytesPerRow: 320, space: MixedNativeTile.colorSpace, bitmapInfo: MixedNativeTile.layout))
        source.setFillColor(red: 0.8, green: 0.2, blue: 0, alpha: 1)
        source.fill(CGRect(x: 0, y: 0, width: 80, height: 120))
        source.setFillColor(red: 0, green: 0.2, blue: 0.8, alpha: 1)
        source.fill(CGRect(x: 0, y: 0, width: 25, height: 120))
        let encoded = NSMutableData()
        let encoder = try XCTUnwrap(CGImageDestinationCreateWithData(encoded, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(encoder, try XCTUnwrap(source.makeImage()), nil)
        XCTAssertTrue(CGImageDestinationFinalize(encoder))
        let sourceHash = SHA256.hash(data: encoded as Data).map { String(format: "%02x", $0) }.joined()
        let reader = try XCTUnwrap(CGImageSourceCreateWithData(encoded, nil))
        let full = try XCTUnwrap(CGImageSourceCreateImageAtIndex(reader, 0,
            [kCGImageSourceShouldCacheImmediately as String: true] as CFDictionary))
        let thumbnail = try XCTUnwrap(CGImageSourceCreateThumbnailAtIndex(reader, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways as String: true,
            kCGImageSourceShouldCacheImmediately as String: true,
            kCGImageSourceThumbnailMaxPixelSize as String: 60,
        ] as CFDictionary))
        XCTAssertEqual(full.width, thumbnail.width * 2)
        XCTAssertEqual(full.height, thumbnail.height * 2)

        let missing = MixedTilePlan(slot: slotImage, dependency: .missing(source: sourceHash)) { context in
            context.setFillColor(CGColor(gray: 0.6, alpha: 1))
            context.fill(rect)
        }
        func imagePlan(_ image: CGImage, mirrored: Bool) -> MixedTilePlan {
            MixedTilePlan(slot: slotImage, dependency: .image(source: sourceHash,
                width: image.width, height: image.height, mirrored: mirrored)) { context in
                context.interpolationQuality = .none
                if mirrored { context.translateBy(x: rect.width, y: 0); context.scaleBy(x: -1, y: 1) }
                context.draw(image, in: rect)
            }
        }
        let high = imagePlan(full, mirrored: false), low = imagePlan(thumbnail, mirrored: true)
        let primary = CTFontCreateWithName("Helvetica" as CFString, 26, nil)
        let logicalText = "骨直令"
        let characters = Array(logicalText.utf16)
        var primaryGlyphs = Array(repeating: CGGlyph(0), count: characters.count)
        XCTAssertFalse(CTFontGetGlyphsForCharacters(primary, characters, &primaryGlyphs, characters.count))
        func textPlan(_ actualName: String) throws -> MixedTilePlan {
            let fallback = CTFontDescriptorCreateWithNameAndSize(actualName as CFString, 26)
            let cascade = CTFontDescriptorCreateWithAttributes([
                kCTFontCascadeListAttribute as String: [fallback],
            ] as CFDictionary)
            let font = CTFontCreateCopyWithAttributes(primary, 26, nil, cascade)
            XCTAssertEqual(CTFontCopyPostScriptName(font) as String, CTFontCopyPostScriptName(primary) as String)
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: logicalText, attributes: [
                NSAttributedString.Key(kCTFontAttributeName as String): font,
            ]))
            let runs = CTLineGetGlyphRuns(line) as! [CTRun]
            var stamp = [MixedTileDependency.Glyph]()
            for run in runs {
                let attributes = CTRunGetAttributes(run) as NSDictionary
                let resolved = attributes[kCTFontAttributeName] as! CTFont
                let name = CTFontCopyPostScriptName(resolved) as String
                XCTAssertEqual(name, actualName)
                let count = CTRunGetGlyphCount(run)
                var glyphs = Array(repeating: CGGlyph(0), count: count)
                var positions = Array(repeating: CGPoint.zero, count: count)
                CTRunGetGlyphs(run, CFRange(location: 0, length: 0), &glyphs)
                CTRunGetPositions(run, CFRange(location: 0, length: 0), &positions)
                stamp += zip(glyphs, positions).map { .init(font: name, id: $0, position: $1) }
            }
            XCTAssertEqual(stamp.count, characters.count)
            XCTAssertTrue(stamp.allSatisfy { $0.id != 0 })
            return MixedTilePlan(slot: slotText, dependency: .text(stamp)) { context in
                context.textPosition = CGPoint(x: 5, y: 52)
                CTLineDraw(line, context)
            }
        }
        let firstFont = "HiraginoSans-W3", secondFont = "PingFangSC-Regular"
        let firstText = try textPlan(firstFont), secondText = try textPlan(secondFont)

        let size = MixedNativeTile.bytes
        let pool = ResourceLeasePool<NSString, MixedNativeTile>(residentBudget: 2 * size, totalBudget: 4 * size,
            maxResources: 8, maxLeases: 2, releaseLane: .owner)
        let scene = SceneId(value: "native-mixed-tile")
        func frame(_ request: Int64) -> FrameToken {
            FrameToken(sceneId: scene, sceneRevision: 1, viewportVersion: 1,
                sessionGeneration: 1, surfaceGeneration: 1, requestSequence: request)
        }
        let gate = FramePublicationGate(initial: frame(1))
        var peak: Int64 = 0
        var released = 0
        var hitsByRequest = [[String]]()
        typealias Ticket = ResourceReleaseTicket<MixedNativeTile>
        func reserve() throws -> ResourceReservation {
            let token = try XCTUnwrap((pool.reserve(bytes: size) as? ReservationResultReserved)?.reservation)
            peak = max(peak, pool.chargedBytes)
            if pool.chargedBytes == pool.totalBudget {
                let refused = try XCTUnwrap(pool.reserve(bytes: 1) as? ReservationResultRejected)
                XCTAssertEqual(refused.reason, .totalLimit)
            }
            return token
        }
        func settle(_ ticket: Ticket) {
            guard ticket.value.receipt.confirmed else { return XCTFail("Provider bytes are still retained") }
            XCTAssertEqual(pool.completeRelease(ticket: ticket, outcome: .released), .updated)
            released += 1
        }
        func releaseReady() {
            while let item = pool.nextRelease(destination: .owner) as? ResourceReleaseDispatchReady<MixedNativeTile> {
                item.ticket.value.image = nil
                settle(item.ticket)
            }
        }
        func step(_ sequence: Int64, _ image: MixedTilePlan, _ text: MixedTilePlan, hits expected: [String]) throws {
            let request = frame(sequence)
            XCTAssertTrue(gate.expect(token: request))
            var hits = [String]()
            for plan in [image, text] {
                let reader = (pool.borrow(key: plan.slot) as? ResourceBorrowBorrowed<MixedNativeTile>)?.lease
                let cold = try MixedNativeTile(plan: plan).pixels()
                if let reader, reader.value.dependency == plan.dependency {
                    hits.append(plan.slot as String)
                    XCTAssertEqual(try reader.value.pixels(), cold)
                } else {
                    if let reader { XCTAssertNotEqual(try reader.value.pixels(), cold, "request \(sequence) \(plan.slot)") }
                    let charge = try reserve()
                    let fresh = try MixedNativeTile(plan: plan)
                    XCTAssertEqual(try fresh.pixels(), cold)
                    XCTAssertTrue(gate.canPublish(token: request))
                    XCTAssertTrue(pool.publish(reservation: charge, key: plan.slot, value: fresh) is ResourcePublicationResident)
                    if let reader {
                        XCTAssertFalse(reader.value.receipt.confirmed)
                        XCTAssertEqual(pool.retiredBytes, size)
                        XCTAssertTrue(pool.nextRelease(destination: .owner) is ResourceReleaseDispatchIdle)
                    }
                }
                if let reader { XCTAssertEqual(pool.returnLease(lease: reader), .returned) }
                releaseReady()
            }
            XCTAssertEqual(hits, expected)
            hitsByRequest.append(hits)
        }
        try step(1, missing, firstText, hits: [])
        try step(2, missing, firstText, hits: ["image", "text"])
        try step(3, high, firstText, hits: ["text"])

        let worker = try AppleBoundedOwnerExecutor(maxEntries: 1, maxBytes: size, automaticPump: false)
        defer { try? worker.stopAccepting() }
        let obsolete = try reserve()
        var resultRetired = false
        let admission = try worker.submit(input: frame(3), chargedBytes: size, compute: { _ in
            precondition(!Thread.isMainThread)
            return try MixedNativeTile(plan: high)
        }, receive: { request, result in
            XCTAssertTrue(Thread.isMainThread)
            if case .succeeded(let tile) = result {
                XCTAssertFalse(gate.canPublish(token: request))
                resultRetired = pool.publishRetired(reservation: obsolete, value: tile) is ResourcePublicationRetired
            }
            return .acknowledged
        })
        guard case .accepted = admission else { return XCTFail("worker admission") }
        try worker.stopAccepting()
        try await eventually { (try? worker.status().workerStopped) == true }
        XCTAssertEqual(try worker.status().entries.map { $0.stage }, [.ready])
        XCTAssertEqual(try worker.status().chargedBytes, size)
        try step(4, low, firstText, hits: ["text"])
        XCTAssertEqual(try worker.drainResults().acknowledged, 1)
        XCTAssertTrue(resultRetired)
        XCTAssertTrue(try worker.status().isDrained)
        XCTAssertEqual(pool.retiredBytes, size)
        releaseReady()

        try step(5, low, secondText, hits: ["image"])
        try step(6, low, secondText, hits: ["image", "text"])

        let lease = try XCTUnwrap((pool.borrow(key: slotImage) as? ResourceBorrowBorrowed<MixedNativeTile>)?.lease)
        nonisolated(unsafe) var heldProvider = lease.value.image?.dataProvider
        XCTAssertNotNil(heldProvider)
        XCTAssertEqual(pool.returnLease(lease: lease), .returned)
        XCTAssertTrue(pool.evict(key: slotImage))
        let pending = try XCTUnwrap((pool.nextRelease(destination: .owner) as? ResourceReleaseDispatchReady<MixedNativeTile>)?.ticket)
        pending.value.image = nil
        gate.close()
        pool.close()
        releaseReady()
        XCTAssertFalse(pending.value.receipt.confirmed)
        XCTAssertFalse(pool.isDrained)
        XCTAssertEqual(pool.chargedBytes, size)
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async { heldProvider = nil; continuation.resume() }
        }
        try await eventually { pending.value.receipt.confirmed }
        settle(pending)
        XCTAssertTrue(pool.isDrained)
        XCTAssertEqual(peak, 4 * size)
        XCTAssertEqual(released, 6)
        let evidence: [String: Any] = [
            "trace_id": "c1-apple-mixed-tile-v1", "source_sha256": sourceHash,
            "decoded_dimensions": [full.width, full.height, thumbnail.width, thumbnail.height],
            "font_faces": [firstFont, secondFont], "hits": hitsByRequest, "cold_tile_comparisons": 12,
            "scene_revision": 1, "tile_bytes": size, "peak_charged_tile_bytes": peak,
            "released_tiles": released, "late_worker_result_retired": resultRetired,
            "provider_held_bytes_after_close": size, "provider_confirmed_after_foreign_release": true,
        ]
        let artifact = XCTAttachment(data: try JSONSerialization.data(withJSONObject: evidence, options: [.sortedKeys]),
            uniformTypeIdentifier: "public.json")
        artifact.name = "c1-apple-mixed-tile.json"
        artifact.lifetime = .keepAlways
        add(artifact)
    }
}
