import XCTest
import UIKit
import NoteCanvasPrototype
@testable import NoteCorePreview

/// Runs in the real iOS app host. API/file/render tests, not UI taps or real Pencil evidence.
final class PagedHostTests: XCTestCase {
    @MainActor func testPendingRecoveryOriginalModelFailureRetryNewOwnerAndCanonicalReopen() async throws {
        let dir = directory(), moved = directory()
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: moved) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let base = "NCPREVIEW\t2\nD\tpending-swift\tpending\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        let ink = "NCPREVIEW\t2\nD\tpending-swift\t\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nS\treused-ink-2\tlayer\t1\tMONOLINE\t1\t-14339513\t2.5\t3\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.25\t2.5\t0.125\t7\nP\t3.0\t4.0\t0.625\t9\nP\t8.0\t6.0\t1.0\t10\nEND\n"
        let operation = "reused-op-1".utf16.map { String(format: "%04x", $0) }.joined()
        let encoded = "NCRECOVERY\t1\nD\tpending-swift\tpending\t1\t0\t0\n\(base.utf16.count)\t\(ink.utf16.count)\t\(operation)\n" + base + ink + "RECOVERY-END\n"
        let checkpoint = try PreviewRecoveryCodec.shared.decode(text: encoded)
        let file = dir.appendingPathComponent("pending-swift.ncp")
        try PreviewFileIO.writeUTF8(encoded, to: file)
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 4096, journalBytes: 32 * 1024 * 1024, identityBytes: 4 * 1024 * 1024,
            historyEntries: 128, historyBytes: 0, inputBytes: 2 * 1024 * 1024)
        try FileManager.default.removeItem(at: dir); try Data("blocked".utf8).write(to: dir)
        let model = PreviewModel(directory: dir, documentBudget: budget)
        // Same adoption boundary used by open, with a validated but not-yet-persisted checkpoint.
        try model.adopt(checkpoint.root, persisted: false, recovery: checkpoint)
        let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.captureRecovery().sameContent(other: checkpoint)); XCTAssertEqual(note.totalStrokeCount, 0)
        XCTAssertTrue(model.save()); model.close(); XCTAssertTrue(model.current === note)
        try await eventually("pending checkpoint failed IO retains full content") { model.ownerRetirementStatus.retryRequired == 1 }
        XCTAssertTrue(note.hasPendingStroke); XCTAssertTrue(note.captureRecovery().sameContent(other: checkpoint))
        try FileManager.default.removeItem(at: dir); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertTrue(model.retryRetainedWrites())
        try await eventually("pending checkpoint writes through original worker") { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertEqual(try PreviewFileIO.readUTF8(file), try checkpoint.encode())
        model.close(); XCTAssertNil(model.current); XCTAssertFalse(note.retryPendingStroke())
        let reopened = PreviewModel(directory: dir); reopened.open("pending-swift")
        try await eventually("fresh default owner retains pending without automatic commit") { reopened.current?.hasPendingStroke == true }
        let restored = try XCTUnwrap(reopened.current)
        XCTAssertTrue(restored.pendingStroke()!.isEqual(checkpoint.pending)); XCTAssertTrue(restored.retryPendingStroke())
        XCTAssertTrue(restored.retryPendingStroke()); XCTAssertEqual(restored.totalStrokeCount, 1); XCTAssertEqual(restored.changeVersion, 1)
        let held = directory()
        defer { try? FileManager.default.removeItem(at: held) }
        try FileManager.default.moveItem(at: dir, to: held); try Data("blocked".utf8).write(to: dir)
        XCTAssertTrue(reopened.save())
        try await eventually("actual failed IO remains in original ledger") { reopened.ownerRetirementStatus.retryRequired == 1 }
        XCTAssertEqual(try PreviewFileIO.readUTF8(held.appendingPathComponent("pending-swift.ncp")), try checkpoint.encode())
        XCTAssertTrue(restored.totalStrokeCount == 1 && !restored.hasPendingStroke)
        try FileManager.default.removeItem(at: dir); try FileManager.default.moveItem(at: held, to: dir)
        XCTAssertTrue(reopened.retryRetainedWrites())
        try await eventually("repair writes canonical replacement") { reopened.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertTrue(try PreviewFileIO.readUTF8(file).hasPrefix("NCPREVIEW\t2\n"))
        reopened.close(); XCTAssertNil(reopened.current)
        let final = PreviewModel(directory: dir); final.open("pending-swift")
        try await eventually("normal new owner reopens one committed stroke") { final.current?.totalStrokeCount == 1 }
        XCTAssertFalse(final.current!.hasPendingStroke)
        final.close()
        let corrupt = String(encoded.dropLast()); try PreviewFileIO.writeUTF8(corrupt, to: file)
        let refused = PreviewModel(directory: dir); refused.open("pending-swift")
        try await eventually("truncated recovery refuses entire opening") { refused.status.contains("读取失败") }
        XCTAssertNil(refused.current); XCTAssertEqual(try PreviewFileIO.readUTF8(file), corrupt)
    }
    @MainActor func testValidatedOpeningReturnsTypedFailureAndUsesOriginalNotebook() throws {
        let root = DocumentRoot.companion.empty(id: SceneId(value: "validated-swift"), title: "unchanged",
            firstPage: PageDescriptor(id: PageId(value: "page"), layerId: LayerId(value: "layer"),
                width: 595, height: 842, paper: .blank, contentRevision: 0))
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8 * 1024 * 1024,
            journalEntries: 128, journalBytes: 8 * 1024 * 1024, identityBytes: 2 * 1024 * 1024,
            historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: 2 * 1024 * 1024)
        let refused = PagedPreviewNotebook.companion.open(initial: root, sessionNonce: "bad nonce", budget: budget,
            ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(refused.notebook); XCTAssertEqual(refused.failure, .invalidSessionNonce)
        let opening = PagedPreviewNotebook.companion.open(initial: root, sessionNonce: "swift-valid", budget: budget,
            ownerCheck: { precondition(Thread.isMainThread) })
        XCTAssertNil(opening.failure)
        let note = try XCTUnwrap(opening.notebook)
        XCTAssertTrue(note.snapshot() === root)
        draw(note); XCTAssertEqual(note.totalStrokeCount, 1)
        XCTAssertEqual(root.scene.objectCount, 0); XCTAssertTrue(note.retire())
    }
    @MainActor func testFullSaveLedgerRejectsRetirementUntilRetainedWritesAreExplicitlyRetried() async throws {
        let dir = directory(); try Data("blocked".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir)
        var accepted: [String: String] = [:]
        for _ in 0..<8 {
            model.create(); let note = try XCTUnwrap(model.current)
            accepted[note.documentId] = try note.capture().encode()
            model.close(); XCTAssertNil(model.current)
        }
        model.create(); let blocked = try XCTUnwrap(model.current)
        accepted[blocked.documentId] = try blocked.capture().encode()
        XCTAssertEqual(model.beginOwnerRetirement(), .saveRejected)
        XCTAssertTrue(model.current === blocked); XCTAssertEqual(model.ownerRetirementStatus.phase, .active)
        XCTAssertEqual(model.ownerRetirementStatus.pendingWrites, 8)
        try await eventually("all accepted failed snapshots remain retryable") { model.ownerRetirementStatus.retryRequired == 8 }
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertTrue(model.retryRetainedWrites())
        try await eventually("explicit retry frees old document slots") { model.ownerRetirementStatus.pendingWrites == 0 }
        XCTAssertTrue(model.current === blocked)
        var finished = false
        XCTAssertEqual(model.beginOwnerRetirement { state in
            XCTAssertTrue(state.workerStopped); XCTAssertEqual(state.pendingWrites, 0); finished = true
        }, .started)
        try await eventually("ninth document saves before owner retirement") { finished }
        for (id, expected) in accepted {
            XCTAssertEqual(try String(contentsOf: dir.appendingPathComponent(id + ".ncp"), encoding: .utf8), expected)
        }
    }
    @MainActor func testOwnerRetirementWritesAcceptedRootThenStopsAndReleasesOwner() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        var model: PreviewModel? = PreviewModel(directory: dir)
        model?.create()
        let note = try XCTUnwrap(model?.current); draw(note); model?.changed()
        let expected = try note.capture().encode(), file = dir.appendingPathComponent(note.documentId + ".ncp")
        weak var observer = model
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertEqual(status.phase, .retired); XCTAssertTrue(status.workerStopped)
            XCTAssertEqual(status.pendingWrites, 0); XCTAssertEqual(status.renderBytes, 0)
            finished = true
        }, .started)
        XCTAssertNil(model?.current)
        model?.create(); model?.open(note.documentId); model?.prepareExport()
        XCTAssertNil(model?.current); XCTAssertFalse(model?.transferBusy ?? true)
        XCTAssertEqual(model?.beginOwnerRetirement(), .alreadyRetiring)
        model = nil
        try await eventually("owner retirement completed") { finished && observer == nil }
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), expected)
    }
    @MainActor func testOwnerRetirementRetainsFailedWritesUntilExplicitRepairAndRetry() async throws {
        let dir = directory(); try Data("blocked".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        var model: PreviewModel? = PreviewModel(directory: dir); model?.create()
        let note = try XCTUnwrap(model?.current); draw(note); model?.changed()
        let expected = try note.capture().encode(), file = dir.appendingPathComponent(note.documentId + ".ncp")
        weak var observer = model
        var finished = false
        XCTAssertEqual(model?.beginOwnerRetirement { status in
            XCTAssertTrue(status.workerStopped); XCTAssertEqual(status.pendingWrites, 0); finished = true
        }, .started)
        model = nil
        try await eventually("failed retirement still holds original write") { observer?.ownerRetirementStatus.retryRequired == 1 }
        XCTAssertFalse(finished); XCTAssertNotNil(observer)
        XCTAssertEqual(observer?.ownerRetirementStatus.phase, .retiring)
        XCTAssertFalse(observer?.ownerRetirementStatus.workerStopped ?? true)
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertEqual(observer?.retryRetainedWrites(), true)
        try await eventually("repair releases the original owner") { finished && observer == nil }
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), expected)
    }
    @MainActor func testOwnerRetirementRefusesActiveInputAndOutstandingExportWithoutCancellingThem() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current)
        XCTAssertTrue(note.beginStroke(x: 1, y: 2, pressure: 0.5, timeMicros: 1))
        XCTAssertEqual(model.beginOwnerRetirement(), .inputPending)
        XCTAssertTrue(note.hasActiveStroke); XCTAssertEqual(model.ownerRetirementStatus.phase, .active)
        note.cancelStroke() // Explicit caller cancellation, not a side effect of retirement refusal.
        model.prepareExport()
        XCTAssertEqual(model.beginOwnerRetirement(), .transferPending)
        try await eventually("export is held by original consumer") { !model.transferBusy && model.exportDocument != nil }
        let bytes = model.exportDocument?.data
        XCTAssertEqual(model.beginOwnerRetirement(), .transferPending)
        XCTAssertEqual(model.exportDocument?.data, bytes)
        model.exportDocument = nil
        var finished = false
        XCTAssertEqual(model.beginOwnerRetirement { _ in finished = true }, .started)
        try await eventually("idle terminal owner stops") { finished }
        XCTAssertEqual(model.beginOwnerRetirement(), .retired)
        XCTAssertFalse(model.retryRetainedWrites())
    }
    @MainActor func testSavedIdentitySkipsUnchangedIOButChangedFailureRemainsRetryable() async throws {
        let dir = directory(), moved = directory()
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: moved) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current)
        draw(note); model.changed()
        let file = dir.appendingPathComponent(note.documentId + ".ncp")
        let original = try note.capture().encode()
        let savedIdentity = note.snapshot()
        defer { withExtendedLifetime(savedIdentity) {} }
        try await eventually("initial saved marker") { model.status.hasPrefix("已保存") && (try? String(contentsOf: file, encoding: .utf8)) == original }
        try FileManager.default.moveItem(at: dir, to: moved)
        try Data("blocked directory".utf8).write(to: dir)
        XCTAssertTrue(model.save()); XCTAssertTrue(model.status.hasPrefix("已保存"))
        XCTAssertTrue(note.rename(value: "Changed while storage unavailable")); model.changed()
        try await eventually("changed root did not inherit saved marker") { model.status.contains("保存失败") }
        let changed = try note.capture().encode()
        XCTAssertNotEqual(changed, original)
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.moveItem(at: moved, to: dir)
        XCTAssertTrue(model.save())
        try await eventually("repair saves retained current root") { model.status.hasPrefix("已保存") && (try? String(contentsOf: file, encoding: .utf8)) == changed }
        model.close(); XCTAssertNil(model.current)
        let reopened = PreviewModel(directory: dir); reopened.open(note.documentId)
        try await eventually("reopen adopts saved revision") { reopened.current != nil }
        XCTAssertTrue(reopened.save())
        try await eventually("reopened save completes even if weak identity expires") { reopened.status.hasPrefix("已保存") }
        reopened.close()
    }
    @MainActor func testPendingExportKeepsFirstCapturedContentUntilConsumerClearsIt() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current); draw(note)
        let original = try note.capture().encode()
        model.prepareExport()
        XCTAssertTrue(note.rename(value: "Later export"))
        model.prepareExport() // A second request cannot replace the accepted capture.
        try await eventually("first export ready") { !model.transferBusy && model.exportDocument != nil }
        let first = try XCTUnwrap(model.exportDocument?.data)
        XCTAssertEqual(String(data: first, encoding: .utf8), original)
        model.prepareExport(); XCTAssertEqual(model.exportDocument?.data, first)
        model.exportDocument = nil // Same ownership release as exporter cancellation/completion.
        model.prepareExport()
        let later = try note.capture().encode()
        try await eventually("next export admitted after clear") { !model.transferBusy && model.exportDocument != nil }
        XCTAssertEqual(String(data: try XCTUnwrap(model.exportDocument?.data), encoding: .utf8), later)
        model.exportDocument = nil; XCTAssertTrue(model.save())
        try await eventually("post-export root saved") { model.status.hasPrefix("已保存") }
        model.close()
    }
    @MainActor func testNativeScalarBatchBridgeValidatesLengthsAndCopiesAcceptedData() throws {
        func data<T>(_ values: [T]) -> NSData {
            values.withUnsafeBufferPointer { NSData(bytes: $0.baseAddress, length: $0.count * MemoryLayout<T>.stride) }
        }
        let note = try PagedPreviewNotebook.companion.create(documentId: "native-batch", title: "batch", sessionNonce: "native-batch")
        func offer(_ phase: PointerPhase, _ time: Int64, _ x: NSData, _ y: NSData, _ p: NSData, _ t: NSData) -> PreviewInputResult {
            ApplePreviewInput.shared.offer(notebook: note, phase: phase, eventTimeMicros: time, xs: x as Data, ys: y as Data, pressures: p as Data, times: t as Data)
        }
        let empty = NSData()
        let oversized = offer(.down, 0, Data(count: (Int(note.inputBatchLimit) + 1) * 8) as NSData, empty, empty, empty)
        XCTAssertFalse(oversized.admitted); XCTAssertEqual(oversized.failure?.kind, .inputBatchCapacity)
        let badLength = offer(.down, 1, Data([0xff]) as NSData, empty, empty, empty)
        XCTAssertFalse(badLength.admitted); XCTAssertEqual(badLength.acceptedSamples, 0)
        let mutableX = data([1.0, 2.0]).mutableCopy() as! NSMutableData
        let down = offer(.down, 2, mutableX, data([3.0, 4.0]), data([Float(0.2), Float(0.3)]), data([Int64(1), Int64(2)]))
        XCTAssertEqual(down.disposition, .accepted); XCTAssertEqual(down.acceptedSamples, 2)
        mutableX.resetBytes(in: NSRange(location: 0, length: mutableX.length))
        let badTail = offer(.move, 4, data([3.0, Double.nan]), data([5.0, 6.0]), data([Float(0.4), Float(0.5)]), data([Int64(3), Int64(4)]))
        XCTAssertFalse(badTail.admitted); XCTAssertEqual(badTail.activeSamples, 2); XCTAssertEqual(badTail.acceptedSamples, 0)
        let good = offer(.move, 4, data([3.0, 4.0]), data([5.0, 6.0]), data([Float(0.4), Float(0.5)]), data([Int64(3), Int64(4)]))
        XCTAssertTrue(good.admitted)
        let up = offer(.up, 4, empty, empty, empty, empty)
        XCTAssertEqual(up.disposition, .committed); XCTAssertEqual(up.acceptedSamples, 0)
        XCTAssertEqual(note.pointCount(index: 0), 4); XCTAssertEqual(note.pointX(index: 0, point: 0), 1)
        XCTAssertEqual(note.pointY(index: 0, point: 0), 3); XCTAssertEqual(note.pointPressure(index: 0, point: 0), Float(0.2))
        XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: 3), 4)
    }
    @MainActor func testTypedEditModelRejectsInvalidParametersWithoutLosingActiveInk() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current), before = note.snapshot()
        XCTAssertTrue(note.beginStroke(x: 1, y: 2, pressure: 0.5, timeMicros: 1))
        model.applyEdit(PreviewEditSetPaper(width: .nan, height: 500, paper: .grid))
        XCTAssertEqual(note.lastFailure?.kind, PreviewFailureKind.invalidArgument)
        XCTAssertTrue(note.hasActiveStroke); XCTAssertTrue(note.snapshot() === before)
        model.applyEdit(PreviewEditUndo.shared)
        XCTAssertEqual(note.lastFailure?.kind, PreviewFailureKind.historyEmpty); XCTAssertTrue(note.hasActiveStroke)
        let invalidId = note.edit(request: PreviewEditSelectPage(pageId: ""))
        XCTAssertFalse(invalidId.accepted); XCTAssertEqual(invalidId.failure?.kind, PreviewFailureKind.invalidArgument)
        XCTAssertTrue(note.endStroke(x: 3, y: 4, pressure: 0.6, timeMicros: 2)); XCTAssertEqual(note.pointCount(index: 0), 2)
        model.applyEdit(PreviewEditRename(title: "合法😀")); model.applyEdit(PreviewEditInsertPage.shared)
        XCTAssertEqual(note.pageCount, 2); XCTAssertNil(note.lastFailure)
        let file = dir.appendingPathComponent(note.documentId + ".ncp"), expected = try note.capture().encode()
        try await eventually("typed edits save through original ledger") { (try? String(contentsOf: file, encoding: .utf8)) == expected }
        model.close(); XCTAssertNil(model.current)
        let reopened = try PagedPreviewCodec.shared.decode(text: PreviewFileIO.readUTF8(file))
        XCTAssertEqual(reopened.title, "合法😀"); XCTAssertEqual(reopened.pageCount, 2); XCTAssertEqual(reopened.scene.objectCount, 1)
    }
    @MainActor func testAuthorityConstructionFailureIsRecoverableInSwift() throws {
        // Swift strings cannot inject malformed UTF-16. Exercise the same annotated
        // constructor's IllegalArgumentException using a representable over-budget root.
        let text = "NCPREVIEW\t2\nD\ttitle-host\t合法😀\t2\t0\t0\nG\tfirst\tlayer-a\t400\t400\tBLANK\t0\nG\tsecond\tlayer-b\t400\t400\tBLANK\t0\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let budget = DocumentBudget(maxPages: 1, maxObjects: 5000, liveBytes: 8*1024*1024, journalEntries: 128,
            journalBytes: 8*1024*1024, identityBytes: 2*1024*1024, historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024)
        XCTAssertThrowsError(try MemoryDocumentAuthority(initial: root, budget: budget, ownerCheck: {}))
        let valid = try MemoryDocumentAuthority(initial: root, budget: DocumentBudget(maxPages: 128, maxObjects: 5000,
            liveBytes: 8*1024*1024, journalEntries: 128, journalBytes: 8*1024*1024, identityBytes: 2*1024*1024,
            historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024), ownerCheck: {})
        XCTAssertEqual(valid.snapshot().title, "合法😀")
    }
    func testSharedBrushContoursMatchAnalyticCoverageOffscreen() throws {
        struct Fixture: Decodable {
            struct Case: Decodable { let name: String; let alpha: Double; let contours: [[[Double]]]; let oracle_mask: [String] }
            let width: Int; let height: Int; let fill_rule: String; let cases: [Case]
        }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "brush-paths", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        XCTAssertEqual(fixture.fill_rule, "nonzero-once"); XCTAssertEqual(fixture.cases.count, 6)
        var testedPixels = 0
        for c in fixture.cases {
            let path = CGMutablePath()
            for contour in c.contours {
                for (index, point) in contour.enumerated() {
                    let p = CGPoint(x: point[0], y: point[1])
                    if index == 0 { path.move(to: p) } else { path.addLine(to: p) }
                }
                path.closeSubpath()
            }
            for antialias in [false, true] {
                var pixels = [UInt8](repeating: 0, count: fixture.width * fixture.height * 4)
                try pixels.withUnsafeMutableBytes { bytes in
                    let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: fixture.width, height: fixture.height,
                        bitsPerComponent: 8, bytesPerRow: fixture.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
                    context.setAllowsAntialiasing(antialias); context.setShouldAntialias(antialias)
                    // Fixture uses top-down scene pixels; raw CGBitmapContext rows
                    // are vertically reversed relative to its default user space.
                    context.translateBy(x: 0, y: CGFloat(fixture.height))
                    context.scaleBy(x: 1, y: -1)
                    context.setFillColor(red: 1, green: 1, blue: 1, alpha: c.alpha)
                    context.addPath(path); context.fillPath(using: .winding) // ONE compound fill.
                }
                for y in 0..<fixture.height {
                    let row = Array(c.oracle_mask[y])
                    for x in 0..<fixture.width where row[x] != "?" {
                        let actual = Int(pixels[(y * fixture.width + x) * 4 + 3])
                        let expected = row[x] == "#" ? Int((c.alpha * 255).rounded()) : 0
                        XCTAssertLessThanOrEqual(abs(actual - expected), 1, "\(c.name) AA=\(antialias) x=\(x) y=\(y) expected=\(expected) actual=\(actual)")
                        testedPixels += 1
                    }
                }
            }
        }
        XCTAssertGreaterThan(testedPixels, 20000)
    }
    private func directory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("NoteCoreHostTests-" + UUID().uuidString, isDirectory: true)
    }
    @MainActor private func eventually(_ message: String, _ predicate: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(8)
        while !predicate() && Date() < deadline { try await Task.sleep(nanoseconds: 20_000_000) }
        XCTAssertTrue(predicate(), message)
    }
    private func draw(_ note: PagedPreviewNotebook, x: Double = 100) {
        XCTAssertTrue(note.beginStroke(x: x, y: 200, pressure: 0.3, timeMicros: 1))
        XCTAssertTrue(note.endStroke(x: x + 50, y: 250, pressure: 0.8, timeMicros: 2))
    }
    private func androidFixture() throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: "android-paged-v2", withExtension: "ncp"))
    }
    @MainActor func testActualAndroidFileImportsFreshIdsAndExportsAllPages() async throws {
        let sourceURL = try androidFixture(), original = try Data(contentsOf: sourceURL)
        let source = try PagedPreviewCodec.shared.decode(text: String(decoding: original, as: UTF8.self))
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.importCopy(sourceURL)
        try await eventually("Android root imported") { !model.transferBusy }
        let note = try XCTUnwrap(model.current), copy = note.snapshot()
        XCTAssertEqual(copy.pageCount, 3); XCTAssertEqual(copy.scene.objectCount, 3)
        XCTAssertNotEqual(source.id.value, copy.id.value)
        XCTAssertTrue(Set(source.pages().map { $0.id.value }).isDisjoint(with: copy.pages().map { $0.id.value }))
        XCTAssertTrue(Set(source.pages().map { $0.layerId.value }).isDisjoint(with: copy.pages().map { $0.layerId.value }))
        XCTAssertTrue(Set(source.scene.objects().map { $0.id.value }).isDisjoint(with: copy.scene.objects().map { $0.id.value }))
        for index in 0..<source.pageCount {
            let a = source.page(index: index), b = copy.page(index: index)
            XCTAssertEqual(a.width, b.width); XCTAssertEqual(a.height, b.height); XCTAssertEqual(a.paper, b.paper)
            let old = source.pageObjects(id: a.id), new = copy.pageObjects(id: b.id)
            XCTAssertEqual(old.count, new.count)
            for (before, after) in zip(old, new) {
                XCTAssertEqual(before.brush, after.brush); XCTAssertEqual(before.transform, after.transform)
                XCTAssertEqual(before.samples, after.samples)
            }
        }
        model.editPage(content: false) { $0.selectPage(id: $0.page(index: 2).id) }
        model.prepareExport(); try await eventually("whole root export ready") { !model.transferBusy }
        let exported = try XCTUnwrap(model.exportDocument)
        XCTAssertEqual(String(decoding: exported.data, as: UTF8.self), try note.capture().encode())
        XCTAssertEqual(try Data(contentsOf: sourceURL), original)
        model.close()
    }
    @MainActor func testUnsupportedBrushAndCorruptUTF8DoNotCreateCopyOrChangeSource() async throws {
        let dir = directory(), inputs = directory()
        try FileManager.default.createDirectory(at: inputs, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: inputs) }
        let supported = try String(contentsOf: androidFixture(), encoding: .utf8)
        let unsupported = supported.replacingOccurrences(of: "MONOLINE", with: "PRESSURE")
        let url = inputs.appendingPathComponent("unsupported.ncp"); try Data(unsupported.utf8).write(to: url)
        let model = PreviewModel(directory: dir); model.importCopy(url)
        try await eventually("unsupported brush returns failure") { !model.transferBusy }
        XCTAssertNil(model.current); XCTAssertTrue(model.status.hasPrefix("导入失败"))
        XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), unsupported)
        XCTAssertTrue(try PreviewFileIO.catalog(dir).isEmpty)
        let corrupt = inputs.appendingPathComponent("bad-utf.ncp"); try Data([0xff]).write(to: corrupt)
        model.importCopy(corrupt); try await eventually("bad UTF returns failure") { !model.transferBusy }
        XCTAssertNil(model.current); XCTAssertEqual(try Data(contentsOf: corrupt), Data([0xff]))
        XCTAssertTrue(try PreviewFileIO.catalog(dir).isEmpty)
    }
    @MainActor func testLateImportCannotReplaceNewActiveOwner() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir)
        model.importCopy(try androidFixture()); model.create()
        let active = try XCTUnwrap(model.current)
        try await eventually("import finishes alongside new note") { !model.transferBusy }
        XCTAssertTrue(model.current === active); XCTAssertEqual(active.pageCount, 1)
        try await eventually("both documents saved") { (try? PreviewFileIO.catalog(dir).count) == 2 }
        model.close()
    }
    @MainActor func testLateOpenCannotReplaceNewActiveOwner() async throws {
        let dir = directory(); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let root = try PagedPreviewCodec.shared.decode(text: String(contentsOf: androidFixture(), encoding: .utf8))
        try PreviewFileIO.writeUTF8(PagedPreviewCodec.shared.encode(root: root), to: dir.appendingPathComponent(root.id.value + ".ncp"))
        let model = PreviewModel(directory: dir); model.open(root.id.value); model.create()
        let active = try XCTUnwrap(model.current)
        try await eventually("new active note saved after old read result") { model.status.hasPrefix("已保存") }
        XCTAssertTrue(model.current === active); XCTAssertNotEqual(active.documentId, root.id.value)
        model.close()
    }
    @MainActor func testPagedSaveCloseAndNewOwnerReadAllPages() async throws {
        let dir = directory(); defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current), id = note.documentId
        draw(note); model.changed(); let first = note.currentPageId
        model.editPage { $0.insertPage() }; XCTAssertEqual(note.pageCount, 2)
        XCTAssertTrue(note.selectPen(argb: Int32(bitPattern: 0xff3568be), width: 5))
        draw(note, x: 200); model.changed()
        let beforeSelection = note.snapshot()
        model.editPage(content: false) { $0.selectPage(id: first) }
        XCTAssertTrue(note.snapshot() === beforeSelection)
        model.editPage { $0.doCopyCurrentPage() }; XCTAssertEqual(note.totalStrokeCount, 3)
        model.editPage { $0.deleteCurrentPage() }; XCTAssertEqual(note.totalStrokeCount, 2)
        model.editPage { $0.undo() }; XCTAssertEqual(note.totalStrokeCount, 3)
        let encoded = try note.capture().encode(), file = dir.appendingPathComponent(id + ".ncp")
        try await eventually("full root written") { (try? String(contentsOf: file, encoding: .utf8)) == encoded && model.status.hasPrefix("已保存") }
        model.close(); XCTAssertNil(model.current)
        XCTAssertFalse(note.beginStroke(x: 1, y: 2, pressure: 0.5, timeMicros: 3))
        let reopened = PreviewModel(directory: dir); reopened.open(id)
        try await eventually("new owner receives disk root") { reopened.current != nil }
        let restored = try XCTUnwrap(reopened.current)
        XCTAssertEqual(try restored.capture().encode(), encoded)
        XCTAssertEqual(restored.pageCount, 3); XCTAssertEqual(restored.totalStrokeCount, 3)
        XCTAssertFalse(restored.canUndo)
        for index in 0..<restored.pageCount {
            reopened.editPage(content: false) { $0.selectPage(id: $0.page(index: index).id) }
            XCTAssertEqual(restored.strokeCount, 1)
        }
        reopened.close()
    }
    @MainActor func testLegacyNoEditPreservesBytesThenEditMigratesOriginalExtent() async throws {
        let dir = directory(); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let original = "NCPREVIEW\t1\nD\tlegacy-corner\tcorner\t2\t1\t1\nS\tink-1\t-1\t2.5\t2\nP\t900\t1300\t0.3\t1\nP\t999\t1399\t0.8\t2\nEND\n"
        let file = dir.appendingPathComponent("legacy-corner.ncp")
        try PreviewFileIO.writeUTF8(original, to: file)
        let model = PreviewModel(directory: dir); model.open("legacy-corner")
        try await eventually("v1 opens") { model.current != nil }
        XCTAssertEqual(model.current?.currentPage.width, 1000)
        XCTAssertEqual(model.current?.currentPage.height, 1400)
        XCTAssertTrue(model.save()); model.flushForBackground(); model.close()
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), original)
        model.open("legacy-corner"); try await eventually("v1 reopens") { model.current != nil }
        model.editPage { $0.insertPage() }
        let expected = try XCTUnwrap(model.current).capture().encode()
        try await eventually("first edit writes v2") { (try? String(contentsOf: file, encoding: .utf8)) == expected }
        let root = try PagedPreviewCodec.shared.decode(text: expected)
        XCTAssertEqual(root.pageCount, 2)
        XCTAssertEqual(root.scene.objects().first?.samples.y(index: 1), 1399)
        model.close()
    }
    @MainActor func testFailedSaveRetainsRootAcrossCloseAndRepair() async throws {
        let dir = directory(); try Data("blocked directory".utf8).write(to: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        let model = PreviewModel(directory: dir); model.create()
        let note = try XCTUnwrap(model.current); draw(note); model.changed()
        let expected = try note.capture().encode(), id = note.documentId
        try await eventually("write failure visible") { model.status.contains("保存失败") }
        model.close(); XCTAssertNil(model.current)
        try await eventually("failure still retained") { model.notes.contains { $0.id == id && $0.title.contains("未保存") } }
        model.open(id); try await eventually("latest retained root reopens") { model.current != nil }
        XCTAssertEqual(try model.current?.capture().encode(), expected)
        try FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        XCTAssertTrue(model.save())
        let file = dir.appendingPathComponent(id + ".ncp")
        try await eventually("repaired retry writes exact root") { (try? String(contentsOf: file, encoding: .utf8)) == expected && model.status.hasPrefix("已保存") }
        model.close()
    }
    @MainActor func testNativeRendererChangesPageCacheAndAppliesFullAffineDotWidth() throws {
        let text = "NCPREVIEW\t2\nD\trender\tRender\t2\t1\t1\nG\tfirst\tlayer-a\t400\t400\tBLANK\t1\nG\tsecond\tlayer-b\t400\t400\tBLANK\t0\nS\tdot\tlayer-a\t1\tMONOLINE\t1\t-65536\t10\t1\t4\t0\t0\t2\t0\t0\nP\t50\t100\t0.5\t1\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let budget = DocumentBudget(maxPages: 128, maxObjects: 5000, liveBytes: 8*1024*1024, journalEntries: 128,
            journalBytes: 8*1024*1024, identityBytes: 2*1024*1024, historyEntries: 128, historyBytes: 8*1024*1024, inputBytes: 2*1024*1024)
        let note = try PagedPreviewNotebook(initial: root, sessionNonce: "render-session", budget: budget, ownerCheck: { precondition(Thread.isMainThread) })
        let view = InkUIView(note: note); view.frame = CGRect(x: 0, y: 0, width: 400, height: 400); view.layoutIfNeeded()
        func image() -> CGImage {
            let format = UIGraphicsImageRendererFormat(); format.scale = 1
            return UIGraphicsImageRenderer(size: view.bounds.size, format: format).image { _ in view.draw(view.bounds) }.cgImage!
        }
        func pixel(_ image: CGImage, _ x: Int, _ y: Int) -> [UInt8] {
            var bytes = [UInt8](repeating: 0, count: 400*400*4)
            bytes.withUnsafeMutableBytes { data in
                let context = CGContext(data: data.baseAddress, width: 400, height: 400, bitsPerComponent: 8, bytesPerRow: 1600,
                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
                context.draw(image, in: CGRect(x: 0, y: 0, width: 400, height: 400))
            }
            return Array(bytes[(y*400+x)*4..<(y*400+x)*4+4])
        }
        let first = image()
        let red = pixel(first, 215, 200); XCTAssertGreaterThan(red[0], 240); XCTAssertLessThan(red[1], 20)
        let white = pixel(first, 200, 215); XCTAssertGreaterThan(white[1], 240) // Ellipse is only 20 high, 40 wide.
        XCTAssertTrue(note.selectPage(id: note.page(index: 1).id)); view.refreshPage()
        let empty = pixel(image(), 200, 200); XCTAssertGreaterThan(empty[1], 240)
        XCTAssertEqual(view.accessibilityValue, "0笔")
        XCTAssertEqual(note.changeVersion, 1) // Same root revision, different page projection.
    }

    private func candidateBrushPlan(kind: BrushKind = .highlighter, width: Double = 2,
                                    transform: Affine2D? = nil) throws -> BrushPathPlan {
        let text = "NCPREVIEW\t2\nD\tbrush\tBrush\t1\t1\t1\nG\tp\tl\t128\t128\tBLANK\t1\nS\ts\tl\t1\tMONOLINE\t1\t-256\t2\t3\t1\t0\t0\t1\t0\t0\nP\t0\t0\t0\t1\nP\t4\t0\t1\t2\nP\t0\t0\t0\t3\nEND\n"
        let root = try PagedPreviewCodec.shared.decode(text: text)
        let samples = try XCTUnwrap(root.scene.objects().first).samples
        let geometryResult = BrushGeometryBuilder.shared.build(samples: samples,
            brush: BrushSpec(kind: kind, algorithmVersion: 2, width: width, argb: Int32(bitPattern: 0xffffff00)),
            budget: BrushGeometryBudget(maxSamples: 20000, maxVertices: 400000, logicalBytes: 8*1024*1024))
        let geometry = try XCTUnwrap(geometryResult as? BrushGeometryResultReady).geometry
        let m = transform ?? Affine2D(a: 16, b: 0, c: 0, d: 16, tx: 24, ty: 64)
        let result = BrushPathPlanner.shared.prepare(geometry: geometry, sceneToTarget: m, precision: .float32, maxVertices: 400000)
        return try XCTUnwrap(result as? BrushPathPlanResultReady).plan
    }
    private func candidateBrushPixels(plan: BrushPathPlan, times: Int = 1) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 128*128*4)
        bytes.withUnsafeMutableBytes { data in
            let context = CGContext(data: data.baseAddress, width: 128, height: 128, bitsPerComponent: 8, bytesPerRow: 512,
                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            let renderer = NativeBrushRenderer(plan: plan)
            for _ in 0..<times { renderer.draw(in: context) }
        }
        return bytes
    }
    @MainActor func testCandidateHighlighterSingleFillAndSeparateStrokeAlpha() throws {
        let plan = try candidateBrushPlan()
        let once = candidateBrushPixels(plan: plan), twice = candidateBrushPixels(plan: plan, times: 2)
        XCTAssertEqual(once[(64*128+56)*4+3], 96)
        XCTAssertEqual(twice[(64*128+56)*4+3], 156)
        XCTAssertEqual(once[(100*128+56)*4+3], 0)
    }
    @MainActor func testCandidatePressureEndpointWidthsUseCommonGeometry() throws {
        let plan = try candidateBrushPlan(kind: .pressure, width: 8,
            transform: Affine2D(a: 8, b: 0, c: 0, d: 8, tx: 24, ty: 64))
        let pixels = candidateBrushPixels(plan: plan)
        XCTAssertEqual(pixels[(75*128+24)*4+3], 0)
        XCTAssertEqual(pixels[(88*128+56)*4+3], 255)
    }
    @MainActor func testCandidateReflectedAffineRemainsFilled() throws {
        let plan = try candidateBrushPlan(transform: Affine2D(a: -16, b: 4, c: 2, d: 16, tx: 88, ty: 56))
        let pixels = candidateBrushPixels(plan: plan)
        XCTAssertEqual(pixels[(64*128+56)*4+3], 96)
        XCTAssertEqual(pixels[(100*128+56)*4+3], 0)
    }
    @MainActor func testCandidateRendererPreservesCallerCurrentPath() throws {
        let renderer = NativeBrushRenderer(plan: try candidateBrushPlan())
        let context = try XCTUnwrap(CGContext(data: nil, width: 128, height: 128, bitsPerComponent: 8, bytesPerRow: 512,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.move(to: CGPoint(x: 1, y: 2)); context.addLine(to: CGPoint(x: 3, y: 4))
        let before = context.path?.boundingBoxOfPath
        renderer.draw(in: context)
        XCTAssertEqual(context.path?.boundingBoxOfPath, before)
    }
    @MainActor private func inputView() throws -> InkUIView {
        let note = try PagedPreviewNotebook.companion.create(documentId: "input-" + UUID().uuidString,
            title: "Synthetic callbacks", sessionNonce: UUID().uuidString)
        XCTAssertTrue(note.setPaper(width: 200, height: 200, paper: .blank))
        let view = InkUIView(note: note)
        view.frame = CGRect(x: 0, y: 0, width: 200, height: 200); view.layoutIfNeeded()
        return view
    }
    @MainActor func testCoalescedDownMoveUpRetainsEveryRealSampleAndNoPredictions() throws {
        let view = try inputView(), note = view.note
        let main = SyntheticInkTouch(30, 0.003)
        let event = SyntheticInkEvent()
        event.real = [SyntheticInkTouch(10, 0.001), SyntheticInkTouch(20, 0.002), SyntheticInkTouch(30, 0.003)]
        view.touchesBegan([main], with: event)
        XCTAssertTrue(note.hasActiveStroke); XCTAssertEqual(note.strokeCount, 0)
        // UIKit main touchs are mutable across events; accepted auxiliary values must be copied.
        event.real?[0].position.x = 199
        main.position.x = 50; main.seconds = 0.005
        event.real = [SyntheticInkTouch(40, 0.004), SyntheticInkTouch(50, 0.005)]
        view.touchesMoved([main], with: event)
        main.position.x = 70; main.seconds = 0.007
        event.real = [SyntheticInkTouch(60, 0.006), SyntheticInkTouch(70, 0.007)]
        var changes = 0; view.changed = { changes += 1 }
        view.touchesEnded([main], with: event)
        XCTAssertEqual(changes, 1); XCTAssertEqual(note.strokeCount, 1); XCTAssertEqual(note.pointCount(index: 0), 7)
        for index in 0..<7 {
            XCTAssertEqual(note.pointX(index: 0, point: Int32(index)), Double((index + 1) * 10), accuracy: 0.0001)
            XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: Int32(index)), Int64((index + 1) * 1000))
        }
        XCTAssertEqual(event.predictionReads, 0); XCTAssertFalse(view.hasInk)
    }
    @MainActor func testNilAndEmptyCoalescedFallbackKeepsEqualTimestampPoints() throws {
        let view = try inputView(), note = view.note
        let main = SyntheticInkTouch(10, 0.001), event = SyntheticInkEvent()
        view.touchesBegan([main], with: event) // nil => original main
        event.real = []; main.position.x = 20
        view.touchesMoved([main], with: event) // empty => original main, same timestamp/different point
        main.position.x = 30; main.seconds = 0.002
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.pointCount(index: 0), 3)
        XCTAssertEqual(note.pointX(index: 0, point: 1), 20, accuracy: 0.0001)
        XCTAssertEqual(note.stroke(index: 0).samples.timeMicros(index: 0), note.stroke(index: 0).samples.timeMicros(index: 1))
        XCTAssertTrue(note.undo()); XCTAssertEqual(note.strokeCount, 0)
        XCTAssertTrue(note.redo()); XCTAssertEqual(note.pointCount(index: 0), 3)
    }
    @MainActor func testInvalidTerminalValuesRejectWholeUnfinishedStrokeAndRecover() throws {
        let view = try inputView(), note = view.note
        var failures = 0; view.failed = { _ in failures += 1 }
        let invalid = [Double.nan, Double.infinity, -1, Double(Int64.max) / 1_000_000]
        for value in invalid {
            let main = SyntheticInkTouch(10, 0.001), event = SyntheticInkEvent()
            view.touchesBegan([main], with: nil)
            event.real = [SyntheticInkTouch(20, 0.002), SyntheticInkTouch(30, value)]
            view.touchesEnded([main], with: event)
            XCTAssertFalse(note.hasActiveStroke); XCTAssertFalse(view.hasInk); XCTAssertEqual(note.strokeCount, 0)
        }
        let badPosition = SyntheticInkTouch(30, 0.003); badPosition.position.x = .nan
        let badForce = SyntheticInkTouch(30, 0.003); badForce.forceValue = .nan
        let badMaximum = SyntheticInkTouch(30, 0.003); badMaximum.maximumValue = .nan
        let outOfRange = SyntheticInkTouch(1e10, 0.003)
        let badOrder = SyntheticInkTouch(30, 0.0005)
        for bad in [badPosition, badForce, badMaximum, outOfRange, badOrder] {
            let main = SyntheticInkTouch(10, 0.001), event = SyntheticInkEvent()
            view.touchesBegan([main], with: nil)
            event.real = [SyntheticInkTouch(20, 0.002), bad]
            view.touchesEnded([main], with: event)
            XCTAssertFalse(note.hasActiveStroke); XCTAssertFalse(view.hasInk); XCTAssertEqual(note.strokeCount, 0)
        }
        XCTAssertEqual(failures, invalid.count + 5)
        let main = SyntheticInkTouch(40, 0.004)
        view.touchesBegan([main], with: nil); main.seconds = 0.005
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.strokeCount, 1); XCTAssertEqual(note.pointCount(index: 0), 2)
    }
    @MainActor func testCoordinateSnapshotAndResizeCancelRejectsLateUp() throws {
        let view = try inputView(), note = view.note
        view.viewport.gesture(oldX: 100, oldY: 100, newX: 100, newY: 100, ratio: 2)
        let main = SyntheticInkTouch(60, 0.001)
        view.touchesBegan([main], with: nil)
        main.position.x = 80; main.seconds = 0.002
        view.touchesEnded([main], with: nil)
        XCTAssertEqual(note.pointX(index: 0, point: 0), 80, accuracy: 0.0001)
        XCTAssertEqual(note.pointX(index: 0, point: 1), 90, accuracy: 0.0001)
        let next = SyntheticInkTouch(100, 0.003)
        view.touchesBegan([next], with: nil); XCTAssertTrue(note.hasActiveStroke)
        view.frame.size.width = 240; view.setNeedsLayout(); view.layoutIfNeeded()
        XCTAssertFalse(note.hasActiveStroke)
        next.seconds = 0.004; view.touchesEnded([next], with: nil)
        XCTAssertEqual(note.strokeCount, 1)
    }

}


/// Synthetic UIKit API callbacks only: no injected OS event or real Pencil/latency claim.
private final class SyntheticInkTouch: UITouch {
    var position: CGPoint
    var seconds: TimeInterval
    init(_ x: Double, _ seconds: TimeInterval) { self.position = CGPoint(x: x, y: 100); self.seconds = seconds; super.init() }
    override var timestamp: TimeInterval { seconds }
    override var type: UITouch.TouchType { .pencil }
    var forceValue: CGFloat = 0.5
    var maximumValue: CGFloat = 1
    override var force: CGFloat { forceValue }
    override var maximumPossibleForce: CGFloat { maximumValue }
    override func location(in view: UIView?) -> CGPoint { position }
}
private final class SyntheticInkEvent: UIEvent {
    var real: [SyntheticInkTouch]?
    var predictionReads = 0
    override func coalescedTouches(for touch: UITouch) -> [UITouch]? { real }
    override func predictedTouches(for touch: UITouch) -> [UITouch]? {
        predictionReads += 1; return [SyntheticInkTouch(199, 1)]
    }
}
