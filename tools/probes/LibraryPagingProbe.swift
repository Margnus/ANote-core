import Foundation
import SQLite3
import CryptoKit
import Darwin

// Only disposable read-only SQL fixtures. No NoteCore framework or App model is linked.
enum Failure: Error { case invariant(String), sqlite(Int32, String) }
func check(_ value: Bool, _ message: String) throws { if !value { throw Failure.invariant(message) } }
func sqlCheck(_ result: Int32, _ db: OpaquePointer?) throws {
    if result != SQLITE_OK { throw Failure.sqlite(result, String(cString: sqlite3_errmsg(db))) }
}
let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
func bytes(_ hex: String) -> Data {
    let chars = Array(hex.utf8)
    return Data(stride(from: 0, to: chars.count, by: 2).map {
        UInt8(String(bytes: chars[$0..<$0+2], encoding: .utf8)!, radix: 16)!
    })
}
do {
    try check(CommandLine.arguments.count == 2, "one fixture directory required")
    let folder = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
    try check(folder.path.contains("/library-paging-"), "isolated fixture only")
    let object = try JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent("cases.json"))) as! [String: Any]
    try check(object["schema"] as? String == "library-sql-replay-1", "fixture marker")
    let cases = object["cases"] as! [[String: Any]]
    var db: OpaquePointer?
    try sqlCheck(sqlite3_open_v2(folder.appendingPathComponent("fixture.sqlite").path, &db, SQLITE_OPEN_READONLY, nil), db)
    defer { sqlite3_close_v2(db) }
    try check(sqlite3_db_readonly(db, "main") == 1, "read-only connection")
    var plans = Set<String>(), queries = 0, queryRows = 0
    // Native authorizer is available here; Android public API has no equivalent hook.
    sqlite3_set_authorizer(db, { _, action, table, _, _, _ in
        if action == SQLITE_READ, let table {
            let name = String(cString: table)
            if name == "document_body" || name == "image_resource" { return SQLITE_DENY }
        }
        return SQLITE_OK
    }, nil)
    for (ordinal, item) in cases.enumerated() {
        let sql = item["sql"] as! String, kind = item["kind"] as! String
        try check(sql.hasPrefix("SELECT") || sql.hasPrefix("EXPLAIN QUERY PLAN"), "only reads")
        try check(!sql.contains("document_body") && !sql.contains("image_resource"), "metadata only")
        var statement: OpaquePointer?
        try sqlCheck(sqlite3_prepare_v2(db, sql, -1, &statement, nil), db)
        defer { sqlite3_finalize(statement) }
        for (index, p) in (item["parameters"] as! [[String: Any]]).enumerated() {
            let position = Int32(index + 1)
            switch p["type"] as! String {
            case "blob":
                let data = bytes(p["hex"] as! String)
                if data.isEmpty { try sqlCheck(sqlite3_bind_zeroblob(statement, position, 0), db) }
                else { try data.withUnsafeBytes { memory in
                    try sqlCheck(sqlite3_bind_blob(statement, position, memory.baseAddress, Int32(memory.count), transient), db)
                } }
            case "integer": try sqlCheck(sqlite3_bind_int64(statement, position, (p["value"] as! NSNumber).int64Value), db)
            case "text": try (p["value"] as! String).withCString { text in
                try sqlCheck(sqlite3_bind_text(statement, position, text, -1, transient), db)
            }
            default: throw Failure.invariant("binding type")
            }
        }
        var rows = 0, digest = SHA256()
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { break }
            try check(result == SQLITE_ROW, "step at \(ordinal): \(result)")
            rows += 1
            if kind == "plan" {
                let plan = String(cString: sqlite3_column_text(statement, 3))
                try check(plan.contains("USING COVERING INDEX page_") && !plan.contains("TEMP B-TREE"), "unadopted plan \(plan)")
                plans.insert(plan); continue
            }
            for col in 0..<sqlite3_column_count(statement) {
                let tag: String, data: Data
                switch sqlite3_column_type(statement, col) {
                case SQLITE_NULL: tag = "N"; data = Data()
                case SQLITE_INTEGER: tag = "I"; data = Data(String(sqlite3_column_int64(statement, col)).utf8)
                case SQLITE_TEXT:
                    tag = "T"; data = Data(bytes: sqlite3_column_text(statement, col), count: Int(sqlite3_column_bytes(statement, col)))
                case SQLITE_BLOB:
                    tag = "B"; let count = Int(sqlite3_column_bytes(statement, col))
                    data = count == 0 ? Data() : Data(bytes: sqlite3_column_blob(statement, col)!, count: count)
                default: throw Failure.invariant("unexpected SQLite cell type")
                }
                digest.update(data: Data("\(tag)\(data.count):".utf8)); digest.update(data: data); digest.update(data: Data([10]))
            }
        }
        try check(rows == item["rows"] as! Int, "row count at \(ordinal)")
        if kind == "query" {
            let hex = digest.finalize().map { String(format: "%02x", $0) }.joined()
            try check(hex == item["sha256"] as! String, "typed rows at \(ordinal)")
            queries += 1; queryRows += rows
        }
    }
    let result: [String: Any] = ["status": "passed", "sqlite_version": String(cString: sqlite3_libversion()),
        "cases": cases.count, "queries": queries, "query_rows": queryRows, "plans": plans.sorted(),
        "pid": getpid(), "read_only": true]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_PAGING ".utf8) + data + Data([10]))
} catch {
    fputs("NOTECORE_PAGING_FAILURE \(error)\n", stderr); exit(1)
}
