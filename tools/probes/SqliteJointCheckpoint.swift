import CryptoKit
import Darwin
import Foundation
import NoteCanvasPrototype
import SQLite3

// Simulator executable for the fixed DEV-041 synthetic migration workload.
private enum Admission: Error { case conflict, repairRequired }

private func hash(_ bytes: Data) -> String {
    SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
}

private func immediate<T>(_ db: DB, _ write: () throws -> T) throws -> T {
    try db.exec("BEGIN IMMEDIATE")
    do {
        let result = try write()
        try db.exec("COMMIT")
        return result
    } catch {
        if sqlite3_get_autocommit(db.handle) == 0 { try db.exec("ROLLBACK") }
        throw error
    }
}

private struct Resource {
    let target: URL
    let payload: Data
    let sha: String

    init(folder: URL, source: URL) throws {
        payload = try Data(contentsOf: source)
        try require(!payload.isEmpty && payload.count <= 1024 * 1024)
        sha = hash(payload)
        target = folder.appendingPathComponent(sha)
    }

    func validate() throws {
        let attributes = try? FileManager.default.attributesOfItem(atPath: target.path)
        guard attributes?[.type] as? FileAttributeType == .typeRegular,
              (attributes?[.size] as? NSNumber)?.intValue == payload.count,
              let bytes = try? Data(contentsOf: target), hash(bytes) == sha
        else { throw Admission.repairRequired }
    }

    func publish(in folder: URL) throws {
        let files = FileManager.default
        if (try? files.attributesOfItem(atPath: target.path)) != nil {
            try validate()
            return
        }
        let stage = folder.appendingPathComponent("stage-" + UUID().uuidString)
        try require(files.createFile(atPath: stage.path, contents: nil))
        let handle = try FileHandle(forWritingTo: stage)
        do {
            try handle.write(contentsOf: payload)
            try handle.synchronize()
        } catch {
            try? handle.close()
            throw error
        }
        try handle.close()
        try require(Data(contentsOf: stage) == payload)
        do { try files.linkItem(at: stage, to: target) }
        catch let error as NSError where error.domain == NSCocoaErrorDomain
            && error.code == NSFileWriteFileExistsError {
            // A concurrent publisher may already own the target. Verify it below.
        }
        try validate()
        try files.removeItem(at: stage)
    }
}

private struct Checkpoint {
    let id: String
    let revision: Int64
    let source: Data
    let canonical: Data
    let canonicalHash: String
    let sourceHash: String

    init(_ file: URL) throws {
        let bytes = try Data(contentsOf: file)
        guard let text = String(data: bytes, encoding: .utf8)
        else { throw ProbeFailure.invariant }
        source = bytes
        let codec = PagedPreviewCodec.shared
        let root = try codec.decode(text: text)
        id = root.id.value
        revision = root.revision
        canonical = try Data(codec.encode(root: root).utf8)
        canonicalHash = hash(canonical)
        sourceHash = hash(bytes)
    }

    func taskIdentity() throws -> String {
        // Use the same array order and separators as the fixed host probe.
        let idJSON = try String(decoding: JSONEncoder().encode(id), as: UTF8.self)
        return "[\(idJSON), null, \(revision), \"\(canonicalHash)\", \"\(sourceHash)\"]"
    }
}

private func emitJoint(_ event: [String: Any], folder: URL) throws {
#if DEV041_APP
    var receipt = event
    receipt["bundle"] = Bundle.main.bundleIdentifier
    receipt["sandbox_home"] = NSHomeDirectory()
    let data = try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys])
    // A closed, atomic marker lets the driver correlate an intentional kill to this launch.
    try data.write(to: folder.appendingPathComponent("result.json"), options: .atomic)
#else
    try emit(event)
#endif
}

private func stop(at stage: String, folder: URL) throws -> Never {
    try emitJoint(["cut": stage, "pid": getpid()], folder: folder)
    kill(getpid(), SIGKILL)
    throw ProbeFailure.invariant
}

private func record(_ db: DB, _ operation: String, _ checkpoint: Checkpoint) throws {
    try db.exec("INSERT INTO document VALUES(?,?,?)", [checkpoint.id, String(checkpoint.revision)],
                blobs: [checkpoint.canonical])
    try db.exec("INSERT INTO legacy_source VALUES(?,?)", [checkpoint.id], blobs: [checkpoint.source])
    try db.exec("INSERT INTO operation VALUES(?,?,NULL,?,?,?)", [operation, checkpoint.id, String(checkpoint.revision)],
                blobs: [Data(SHA256.hash(data: checkpoint.canonical)), Data(SHA256.hash(data: checkpoint.source))])
}

private func openWriter(_ database: URL) throws -> DB {
    let db = try DB(database.path)
    try db.exec("PRAGMA foreign_keys=ON")
    try require(db.query("PRAGMA journal_mode") == "wal" && db.number("PRAGMA synchronous") == 2
                && db.number("PRAGMA foreign_keys") == 1)
    return db
}

private func importCheckpoint(_ db: DB, _ folder: URL, _ checkpoint: Checkpoint, _ resources: [Resource],
                              stage: String) throws -> String {
    let operation = "attach-legacy"
    let manifest = resources.map(\.sha).sorted().joined(separator: ",")
    let taskIdentity = try checkpoint.taskIdentity()
    func replay(_ db: DB) throws -> Bool {
        if let document = try db.query("SELECT document_id FROM operation WHERE id=?", [operation]) {
            let saved = try db.query("""
                SELECT coalesce(group_concat(sha256, ','), '') FROM
                    (SELECT sha256 FROM operation_resource WHERE operation_id=? ORDER BY sha256)
                """, [operation])
            guard document == checkpoint.id, saved == manifest,
                  try db.query("SELECT revision FROM operation WHERE id=?", [operation]) == String(checkpoint.revision),
                  try db.query("SELECT lower(hex(checkpoint_sha256)) FROM operation WHERE id=?", [operation]) == checkpoint.canonicalHash,
                  try db.query("SELECT lower(hex(source_sha256)) FROM operation WHERE id=?", [operation]) == checkpoint.sourceHash
            else { throw Admission.conflict }
            let savedCheckpoint = try db.query("SELECT CAST(checkpoint AS TEXT) FROM document WHERE id=?", [checkpoint.id])
            let savedSource = try db.query("SELECT CAST(original AS TEXT) FROM legacy_source WHERE document_id=?", [checkpoint.id])
            guard savedCheckpoint.map({ Data($0.utf8) }) == checkpoint.canonical,
                  savedSource.map({ Data($0.utf8) }) == checkpoint.source
            else { throw Admission.repairRequired }
            for resource in resources {
                guard try db.query("SELECT size FROM resource WHERE sha256=?", [resource.sha]) == String(resource.payload.count)
                else { throw Admission.repairRequired }
                try resource.validate()
            }
            return true
        }
        return false
    }
    func verifyPending(_ connection: DB) throws {
        let identity = try connection.query("SELECT identity FROM import_task WHERE operation_id=?", [operation])
        let pinned = try connection.query("""
            SELECT coalesce(group_concat(sha256 || ':' || size, ','), '') FROM
                (SELECT sha256,size FROM import_task_resource WHERE operation_id=? ORDER BY sha256)
            """, [operation])
        let expected = resources.map { "\($0.sha):\($0.payload.count)" }.joined(separator: ",")
        guard identity == taskIdentity, pinned == expected else { throw Admission.conflict }
    }

    func requestCancellation(_ connection: DB, attempt: String) throws -> String {
        try immediate(connection) {
            if try replay(connection) { return "ALREADY_COMMITTED" }
            try verifyPending(connection)
            guard try connection.query("SELECT active_attempt FROM import_task WHERE operation_id=?", [operation]) == attempt
            else { return "STALE_ATTEMPT" }
            if try connection.query("SELECT cancel_state FROM import_task WHERE operation_id=?", [operation]) == "cancelled" {
                return "CANCELLED"
            }
            try connection.exec("UPDATE import_task SET cancel_state='requested' WHERE operation_id=?", [operation])
            return "CANCEL_REQUESTED"
        }
    }

    func acknowledgeCancellation() throws -> Bool {
        let state = try db.query("SELECT cancel_state FROM import_task WHERE operation_id=?", [operation])
        if state == "requested" {
            try db.exec("UPDATE import_task SET cancel_state='cancelled' WHERE operation_id=?", [operation])
            return true
        }
        return state == "cancelled"
    }

    if stage == "cancel-request" || stage == "cancel-stale" {
        return try requestCancellation(db, attempt: stage == "cancel-stale" ? "stale" : "first")
    }
    let initialStatus: String? = try immediate(db) {
        if try replay(db) { return "REPLAYED" }
        if try db.query("SELECT identity FROM import_task WHERE operation_id=?", [operation]) != nil {
            try verifyPending(db)
        } else {
            try require(db.query("SELECT id FROM document WHERE id=?", [checkpoint.id]) == nil)
            try db.exec("INSERT INTO import_task(operation_id,document_id,identity) VALUES(?,?,?)", [operation, checkpoint.id, taskIdentity])
            for resource in resources {
                try db.exec("INSERT INTO import_task_resource VALUES(?,?,?)",
                            [operation, resource.sha, String(resource.payload.count)])
            }
        }
        return try acknowledgeCancellation() ? "CANCELLED" : nil
    }
    if let initialStatus { return initialStatus }
    for resource in resources {
        try resource.publish(in: folder)
        if stage == "resource-published" { try stop(at: stage, folder: folder) }
        if stage == "cancel-live" {
            // A separate SQLite connection records intent while this producer still owns its task.
            let client = try openWriter(folder.appendingPathComponent("checkpoint.sqlite"))
            defer { client.close() }
            try require(requestCancellation(client, attempt: "first") == "CANCEL_REQUESTED")
            try require(client.query("SELECT cancel_state FROM import_task WHERE operation_id=?", [operation]) == "requested")
            try verifyPending(client)
            break
        }
    }
    let finalStatus = try immediate(db) {
        if try replay(db) { return "REPLAYED" }
        try verifyPending(db)
        if try acknowledgeCancellation() { return "CANCELLED" }
        try record(db, operation, checkpoint)
        for resource in resources {
            try resource.validate()
            let size = String(resource.payload.count)
            try db.exec("INSERT INTO resource VALUES(?,?) ON CONFLICT(sha256) DO NOTHING", [resource.sha, size])
            try require(db.query("SELECT size FROM resource WHERE sha256=?", [resource.sha]) == size)
            try db.exec("INSERT INTO operation_resource VALUES(?,?)", [operation, resource.sha])
        }
        try db.exec("DELETE FROM import_task WHERE operation_id=?", [operation])
        if stage == "before-commit" { try stop(at: stage, folder: folder) }
        return "COMMITTED"
    }
    if finalStatus == "COMMITTED" && stage == "after-commit" { try stop(at: stage, folder: folder) }
    return finalStatus
}

#if !DEV041_APP
@main
#endif
struct SqliteJointCheckpoint {
#if !DEV041_APP
    static func main() {
        do { try runCLI() }
        catch { fputs("Synthetic joint checkpoint failed: \(error)\n", stderr); exit(2) }
    }

    private static func runCLI() throws {
        let args = CommandLine.arguments
        try require(args.count == 3)
        let stage = args[2]
        let folder = URL(fileURLWithPath: args[1]).standardizedFileURL
        try require(folder.deletingLastPathComponent().lastPathComponent == "build"
                    && folder.lastPathComponent.hasPrefix("dev041-apple-"))
        try emitJoint(run(folder: folder, stage: stage), folder: folder)
    }
#endif

#if DEV041_APP
    static func runApp() throws {
        let args = CommandLine.arguments
        try require(args.count == 4 && args[1] == "--dev041-stage")
        guard let bundle = Bundle.main.bundleIdentifier,
              bundle.hasPrefix("dev.notecore.probes.sqlitejointapple.") else { throw ProbeFailure.invariant }
        let home = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
        let fixture = args[3]
        try require(["dev041-apple-joint", "dev041-apple-cancel-stopped", "dev041-apple-cancel-live"].contains(fixture))
        let folder = home.appendingPathComponent("Library/Caches", isDirectory: true)
            .appendingPathComponent(fixture, isDirectory: true)
        try emitJoint(run(folder: folder, stage: args[2]), folder: folder)
    }
#endif

    private static func run(folder: URL, stage: String) throws -> [String: Any] {
        try require(["init", "resource-published", "before-commit", "after-commit", "replay", "conflict",
                     "cancel-request", "cancel-stale", "cancel-live"].contains(stage))
        try require(String(contentsOf: folder.appendingPathComponent(".fixture-scope"), encoding: .utf8) == "dev041-apple")
        let database = folder.appendingPathComponent("checkpoint.sqlite")
        try require(FileManager.default.fileExists(atPath: database.path) == (stage != "init"))
        let db = try openWriter(database)
        defer { db.close() }
        var result: [String: Any] = ["stage": stage, "pid": getpid(), "sqlite": String(cString: sqlite3_libversion())]
        if stage == "init" {
            let statements = try JSONDecoder().decode([String].self, from: Data(contentsOf: folder.appendingPathComponent("schema.json")))
            let checkpoint = try Checkpoint(folder.appendingPathComponent("existing.ncp"))
            try immediate(db) {
                for statement in statements { try db.exec(statement) }
                try db.exec("ALTER TABLE import_task ADD COLUMN active_attempt TEXT NOT NULL DEFAULT 'first'")
                try db.exec("ALTER TABLE import_task ADD COLUMN cancel_state TEXT NOT NULL DEFAULT 'active'")
                try db.exec("PRAGMA user_version=2")
                try record(db, "existing", checkpoint)
            }
            result["status"] = "SEEDED"
        } else {
            try require(db.number("PRAGMA user_version") == 2)
            let checkpoint = try Checkpoint(folder.appendingPathComponent("legacy.ncp"))
            let targets = folder.appendingPathComponent("resources")
            try FileManager.default.createDirectory(at: targets, withIntermediateDirectories: true)
            let files = stage == "conflict" ? ["resource-a.bin"] : ["resource-a.bin", "resource-b.bin"]
            let resources = try files.map { try Resource(folder: targets, source: folder.appendingPathComponent($0)) }
                .sorted { $0.sha < $1.sha }
            do { result["status"] = try importCheckpoint(db, folder, checkpoint, resources, stage: stage) }
            catch Admission.conflict { result["status"] = "OPERATION_CONFLICT" }
            catch Admission.repairRequired { result["status"] = "REPAIR_REQUIRED" }
        }
        return result
    }
}
