import Foundation
import Dispatch

private struct WriteInput { let text: String; let file: URL }
private func expect(_ value: @autoclosure () -> Bool) { precondition(value()) }
private func wait(_ signal: DispatchSemaphore) { expect(signal.wait(timeout: .now() + 3) == .success) }
private func fails(_ body: () throws -> Void) { do { try body(); fatalError("Invalid storage accepted") } catch { } }

@main struct StorageConformance {
    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("notecore-storage-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var passed: [String] = []
        let file = root.appendingPathComponent("note.ncp")
        try PreviewFileIO.writeUTF8("first", to: file)
        try PreviewFileIO.writeUTF8("second 标题", to: file)
        expect(try! PreviewFileIO.readUTF8(file) == "second 标题")
        passed.append("atomic_full_text_replacement")
        let invalid = root.appendingPathComponent("invalid.ncp")
        try Data([0xff]).write(to: invalid)
        fails { _ = try PreviewFileIO.readUTF8(invalid) }
        expect(try! Data(contentsOf: invalid) == Data([0xff]))
        passed.append("strict_utf8_preserves_invalid_original")
        let large = root.appendingPathComponent("large.ncp")
        expect(FileManager.default.createFile(atPath: large.path, contents: nil))
        let handle = try FileHandle(forWritingTo: large)
        try handle.truncate(atOffset: UInt64(PreviewFileIO.maxReadBytes + 1)); try handle.close()
        fails { _ = try PreviewFileIO.readUTF8(large) }
        expect((try! large.resourceValues(forKeys: [.fileSizeKey]).fileSize) == PreviewFileIO.maxReadBytes + 1)
        passed.append("oversize_preflight_preserves_file")
        let header = "NCPREVIEW\t1\nD\tnote\t标题\t1\t0\t0\n"
        try (Data(header.utf8) + Data([0xff])).write(to: file)
        expect(try! PreviewFileIO.readHeaderUTF8(file) == header)
        fails { _ = try PreviewFileIO.readUTF8(file) }
        try Data(repeating: 65, count: 4097).write(to: file)
        fails { _ = try PreviewFileIO.readHeaderUTF8(file) }
        passed.append("bounded_header_does_not_decode_body")
        let blocker = root.appendingPathComponent("blocked")
        try Data([1]).write(to: blocker)
        let target = blocker.appendingPathComponent("note.ncp")
        let owner = DispatchQueue(label: "NoteCore.storage-test.owner")
        let failure = DispatchSemaphore(value: 0), success = DispatchSemaphore(value: 0), drained = DispatchSemaphore(value: 0)
        var executor: AppleBoundedOwnerExecutor!
        var retained: WriteInput?, committed = false
        try owner.sync {
            executor = try AppleBoundedOwnerExecutor(ownerQueue: owner, maxEntries: 2, onOwnerProgress: {
                if (try! executor.status().isDrained) { drained.signal() }
            })
            retained = WriteInput(text: "Latest complete snapshot", file: target)
            _ = try executor.submit(input: retained!, chargedBytes: 4096, compute: { input in
                try PreviewFileIO.writeUTF8(input.text, to: input.file); return true
            }, receive: { input, result in
                guard case .outcomeUnknown = result else { fatalError("Blocked directory did not fail") }
                retained = input // Receiver retains exact full content before ACK.
                failure.signal(); return .acknowledged
            })
        }
        wait(failure)
        try FileManager.default.removeItem(at: blocker)
        try FileManager.default.createDirectory(at: blocker, withIntermediateDirectories: true)
        try owner.sync {
            expect(retained?.text == "Latest complete snapshot")
            _ = try executor.submit(input: retained!, chargedBytes: 4096, compute: { input in
                try PreviewFileIO.writeUTF8(input.text, to: input.file); return true
            }, receive: { input, result in
                guard case .succeeded = result else { fatalError("Repair retry failed") }
                expect(input.text == retained?.text); retained = nil; committed = true
                success.signal(); return .acknowledged
            })
            try executor.stopAccepting()
        }
        wait(success); wait(drained)
        owner.sync { expect(committed && retained == nil); expect(try! executor.status().isDrained); executor = nil }
        expect(try! PreviewFileIO.readUTF8(target) == "Latest complete snapshot")
        passed.append("actual_write_failure_owner_retention_repair_retry")
        let catalog = try PreviewFileIO.catalog(root)
        expect(catalog.count == 3)
        fails { _ = try PreviewFileIO.catalog(root.appendingPathComponent("absent")) }
        let capacity = root.appendingPathComponent("catalog-capacity")
        try FileManager.default.createDirectory(at: capacity, withIntermediateDirectories: true)
        for i in 0...2000 { expect(FileManager.default.createFile(atPath: capacity.appendingPathComponent("note-\(i).ncp").path, contents: nil)) }
        fails { _ = try PreviewFileIO.catalog(capacity) }
        passed.append("bounded_catalog_capacity_and_missing_directory")
        let report: [String: Any] = ["status": "passed", "platform": "macOS Foundation filesystem",
            "test_count": passed.count, "passed": passed, "ios_runtime_verified": false,
            "production_crash_recovery_verified": false, "device_performance_verified": false]
        print(String(data: try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]), encoding: .utf8)!)
    }
}
