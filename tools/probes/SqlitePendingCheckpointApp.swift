import CryptoKit
import Darwin
import NoteCanvasPrototype
import SQLite3
import UIKit

@main
final class SqlitePendingCheckpointApp: UIResponder, UIApplicationDelegate {}

@objc(Dev041PendingSceneDelegate)
final class Dev041PendingSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIViewController()
        self.window = window
        window.makeKeyAndVisible()
        DispatchQueue.main.async {
            do { try PendingMigration.run() }
            catch { NSLog("DEV-041 pending migration failed: \(error)"); exit(2) }
        }
    }
}

private enum PendingMigration {
    private static let migration = "recover-legacy"

    private static func digest(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    private static func hex(_ data: Data) -> String {
        data.map { String(format: "%02X", $0) }.joined()
    }

    static func run() throws {
        let args = CommandLine.arguments
        let stages = ["seed", "invalid", "capacity", "before-commit", "empty", "after-commit", "present", "replay", "duplicate"]
        try require(args.count == 3 && args[1] == "--dev041-pending" && stages.contains(args[2]))
        let stage = args[2]
        guard let bundle = Bundle.main.bundleIdentifier, bundle.hasPrefix("dev.notecore.probes.sqlitependingapple.") else {
            throw ProbeFailure.invariant
        }
        let home = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
        let folder = home.appendingPathComponent("Library/Caches/dev041-pending", isDirectory: true)
        let source = try Data(contentsOf: folder.appendingPathComponent("prepare.ncp"))
        let expected = try Data(contentsOf: folder.appendingPathComponent("confirmed-final.ncp"))
        let database = folder.appendingPathComponent("pending.sqlite")
        let seed = stage == "seed"
        try require(FileManager.default.fileExists(atPath: database.path) != seed)
        let db = try DB(database.path)
        defer { db.close() }
        try db.exec("PRAGMA foreign_keys=ON")

        func receipt(_ status: String) throws {
            let event: [String: Any] = [
                "stage": stage, "status": status, "pid": getpid(), "bundle": bundle, "sandbox_home": home.path,
                "sqlite": String(cString: sqlite3_libversion()),
            ]
            let data = try JSONSerialization.data(withJSONObject: event, options: [.sortedKeys])
            try data.write(to: folder.appendingPathComponent("result.json"), options: .atomic)
        }

        if seed {
            let bytes = try Data(contentsOf: folder.appendingPathComponent("schema.json"))
            guard let schema = try JSONSerialization.jsonObject(with: bytes) as? [String] else {
                throw ProbeFailure.invariant
            }
            try db.exec("BEGIN IMMEDIATE")
            for statement in schema { try db.exec(statement) }
            try db.exec("COMMIT")
            try receipt("SEEDED")
            return
        }

        let input = stage == "invalid" ? Data(source.dropLast()) : source
        guard let text = String(data: input, encoding: .utf8) else { throw ProbeFailure.invariant }
        let read = PreviewRecoveryCodec.shared.read(text: text)
        if stage == "invalid" {
            try require(read.snapshot == nil && read.failure == .invalidOrUnsupportedFormat)
            try receipt("INVALID_SOURCE")
            return
        }
        guard let checkpoint = read.snapshot else { throw ProbeFailure.invariant }
        try require(checkpoint.pending != nil)
        let count = try db.number("SELECT count(*) FROM document")
        if stage == "empty" || stage == "present" {
            try require(count == (stage == "empty" ? 0 : 1))
            try receipt(stage == "empty" ? "EMPTY" : "PRESENT")
            return
        }

        let inputBytes: Int64 = stage == "capacity" ? 2247 : 2248
        let budgetResult = DocumentBudget.companion.validated(maxPages: 128, maxObjects: 5000,
            liveBytes: 8 * 1024 * 1024, journalEntries: 4096, journalBytes: 32 * 1024 * 1024,
            identityBytes: 4 * 1024 * 1024, historyEntries: 128, historyBytes: 8 * 1024 * 1024, inputBytes: inputBytes)
        guard let budget = budgetResult.budget else { throw ProbeFailure.invariant }
        let opened = PagedPreviewNotebook.companion.openRecovery(snapshot: checkpoint,
            sessionNonce: "sqlite-import", budget: budget, ownerCheck: {})
        if stage == "capacity" {
            try require(opened.notebook == nil && opened.failure == .inputCapacity)
            try receipt("INPUT_CAPACITY")
            return
        }
        guard let notebook = opened.notebook else { throw ProbeFailure.invariant }
        try require(notebook.captureRecovery().sameContent(other: checkpoint))
        let strokes = notebook.strokeCount
        try require(notebook.retryPendingStroke() && notebook.retryPendingStroke()
            && notebook.strokeCount == strokes + 1 && notebook.changeVersion == checkpoint.version + 1
            && notebook.pendingStroke() == nil && notebook.inputReservedByteCharge == 0)
        let capture = notebook.capture()
        let canonical = try Data(capture.encode().utf8)
        try require(capture.documentId == checkpoint.documentId && canonical == expected)
        let id = capture.documentId
        let revision = String(capture.version)
        let operation = stage == "duplicate" ? "another-import" : migration
        try db.exec("BEGIN IMMEDIATE")
        try require(db.query("PRAGMA journal_mode") == "wal" && db.number("PRAGMA synchronous") == 2
            && db.number("PRAGMA foreign_keys") == 1)
        if try db.query("SELECT id FROM document WHERE id=?", [id]) != nil {
            let prior = try db.query("SELECT checkpoint_sha256 IS NOT NULL FROM operation WHERE id=?", [operation])
            if prior != nil {
                let verified = try db.query("""
                    SELECT 1 FROM operation o JOIN document d ON o.document_id=d.id
                    JOIN legacy_source l ON l.document_id=d.id
                    WHERE o.id=? AND o.document_id=? AND o.base_revision IS NULL
                        AND o.revision=? AND d.revision=o.revision AND hex(d.checkpoint)=? AND hex(l.original)=?
                        AND hex(o.checkpoint_sha256)=? AND hex(o.source_sha256)=?
                    """, [operation, id, revision, hex(canonical), hex(source), hex(digest(canonical)), hex(digest(source))])
                try require(verified == "1")
            }
            try db.exec("COMMIT")
            try receipt(prior != nil ? "REPLAYED" : "STALE_BASE")
            return
        }
        try require(stage == "before-commit" || stage == "after-commit")
        try db.exec("INSERT INTO document VALUES(?,?,?)", [id, revision], blobs: [canonical])
        try db.exec("INSERT INTO legacy_source VALUES(?,?)", [id], blobs: [source])
        try db.exec("INSERT INTO operation VALUES(?,?,NULL,?,?,?)", [operation, id, revision],
                    blobs: [digest(canonical), digest(source)])
        if stage == "after-commit" { try db.exec("COMMIT") }
        try receipt("PROCESS_CUT")
        kill(getpid(), SIGKILL)
        throw ProbeFailure.invariant
    }
}
