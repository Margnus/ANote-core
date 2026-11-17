import Foundation
import SQLite3
import Darwin

// Test-only simulator executable; no App document or production persistence port.
enum ProbeFailure: Error { case sqlite(Int32), invariant }
let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
func require(_ condition: Bool) throws { if !condition { throw ProbeFailure.invariant } }
final class DB {
    var handle: OpaquePointer?
    init(_ path: String, readOnly: Bool = false) throws {
        let flags = readOnly ? SQLITE_OPEN_READONLY : SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE
        let result = sqlite3_open_v2(path, &handle, flags | SQLITE_OPEN_NOMUTEX, nil)
        if result != SQLITE_OK { close(); throw ProbeFailure.sqlite(result) }
        sqlite3_busy_timeout(handle, 5000)
        if !readOnly { try exec("PRAGMA journal_mode=WAL"); try exec("PRAGMA synchronous=FULL") }
    }
    deinit { close() }
    func close() { if let handle { sqlite3_close_v2(handle); self.handle = nil } }
    private func statement(_ sql: String, _ values: [String], blobs: [Data] = []) throws -> OpaquePointer {
        var statement: OpaquePointer?
        let result = sqlite3_prepare_v2(handle, sql, -1, &statement, nil)
        guard result == SQLITE_OK, let statement else { throw ProbeFailure.sqlite(result) }
        for (index, value) in values.enumerated() {
            let bound = value.withCString { sqlite3_bind_text(statement, Int32(index+1), $0, -1, transient) }
            if bound != SQLITE_OK { sqlite3_finalize(statement); throw ProbeFailure.sqlite(bound) }
        }
        for (index, value) in blobs.enumerated() {
            let bound = value.withUnsafeBytes { buffer in
                sqlite3_bind_blob(statement, Int32(values.count + index + 1), buffer.baseAddress,
                                  Int32(buffer.count), transient)
            }
            if bound != SQLITE_OK { sqlite3_finalize(statement); throw ProbeFailure.sqlite(bound) }
        }
        return statement
    }
    func exec(_ sql: String, _ values: [String] = [], blobs: [Data] = []) throws {
        let prepared = try statement(sql, values, blobs: blobs); defer { sqlite3_finalize(prepared) }
        let result = sqlite3_step(prepared)
        if result != SQLITE_DONE && result != SQLITE_ROW { throw ProbeFailure.sqlite(result) }
    }
    func query(_ sql: String, _ values: [String] = []) throws -> String? {
        let prepared = try statement(sql, values); defer { sqlite3_finalize(prepared) }
        let result = sqlite3_step(prepared)
        if result == SQLITE_DONE { return nil }
        guard result == SQLITE_ROW, let text = sqlite3_column_text(prepared,0) else { throw ProbeFailure.sqlite(result) }
        return String(cString: text)
    }
    func number(_ sql: String) throws -> Int { guard let value = try query(sql), let number = Int(value) else { throw ProbeFailure.invariant }; return number }
}
func emit(_ value: [String:Any]) throws {
    let data = try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_SQLITE ".utf8)+data+Data("\n".utf8)); fflush(stdout)
}
