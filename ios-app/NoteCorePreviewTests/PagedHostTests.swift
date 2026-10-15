import XCTest
import UIKit
import NoteCanvasPrototype
@testable import NoteCorePreview

/// Runs in the real iOS app host. API/file/render tests, not UI taps or real Pencil evidence.
final class PagedHostTests: XCTestCase {
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
        let legacy = PreviewNotebook(documentId: "legacy-corner", title: "corner")
        XCTAssertTrue(legacy.beginStroke(x: 900, y: 1300, pressure: 0.3, timeMicros: 1))
        XCTAssertTrue(legacy.endStroke(x: 999, y: 1399, pressure: 0.8, timeMicros: 2))
        let original = legacy.capture().encode(), file = dir.appendingPathComponent("legacy-corner.ncp")
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
            journalBytes: 8*1024*1024, identityBytes: 2*1024*1024, historyEntries: 128, historyBytes: 8*1024*1024)
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
}
