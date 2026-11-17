import Foundation
import SQLite3
import Darwin

func crash(_ stage: String) throws -> Never {
    try emit(["crash_point":stage]); kill(getpid(),SIGKILL); exit(99)
}
func apply(_ db: DB, _ op: String, _ base: Int, _ payload: String, crashAt: String = "") throws -> String {
    try db.exec("BEGIN IMMEDIATE")
    do {
        if let previous = try db.query("SELECT payload FROM operations WHERE op_id=?",[op]) {
            let revision = try db.query("SELECT revision FROM operations WHERE op_id=?",[op])
            try require(previous==payload && revision==String(base+1))
            try db.exec("COMMIT"); return "replayed"
        }
        try require(db.number("SELECT revision FROM root WHERE id=1")==base)
        try db.exec("INSERT INTO operations(op_id,revision,payload) VALUES(?,?,?)",[op,String(base+1),payload])
        try db.exec("UPDATE root SET revision=?,payload=? WHERE id=1",[String(base+1),payload])
        if crashAt == "uncommitted" { try crash(crashAt) }
        try db.exec("COMMIT")
    } catch { if sqlite3_get_autocommit(db.handle)==0 { try db.exec("ROLLBACK") }; throw error }
    if crashAt == "committed_no_receipt" { try crash(crashAt) }
    return "committed"
}
func runCrashProbe() throws {
    try require(CommandLine.arguments.count==3)
    let directory = URL(fileURLWithPath:CommandLine.arguments[1]).standardizedFileURL
    try require(directory.path.contains("/build/sqlite-capability-"))
    try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
    let file = directory.appendingPathComponent("probe.db")
    let stage = CommandLine.arguments[2]
    if stage == "unknown_schema" || stage == "corrupt_readonly" {
        let copy = directory.appendingPathComponent(stage+".db")
        try FileManager.default.copyItem(at:file,to:copy)
        if stage == "unknown_schema" { let db = try DB(copy.path); try db.exec("PRAGMA user_version=99"); db.close() }
        else { var data = try Data(contentsOf:copy); data.replaceSubrange(0..<32,with:repeatElement(UInt8(0x7f),count:32)); try data.write(to:copy) }
        let before = try Data(contentsOf:copy)
        var refused = false
        do { let db = try DB(copy.path,readOnly:true); defer { db.close() }
            if try db.number("PRAGMA user_version") != 1 { refused = true }
            else { _ = try db.query("PRAGMA quick_check") }
        } catch ProbeFailure.sqlite { refused = true }
        let after = try Data(contentsOf:copy)
        try require(refused && before==after)
        try emit(["stage":stage,"refused_without_modification":true]); return
    }
    let db = try DB(file.path); defer { db.close() }
    try require(db.query("PRAGMA journal_mode")=="wal" && db.number("PRAGMA synchronous")==2)
    var result: [String:Any] = ["stage":stage]
    if stage == "init" {
        try db.exec("CREATE TABLE root(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,payload TEXT NOT NULL)")
        try db.exec("CREATE TABLE operations(op_id TEXT PRIMARY KEY,revision INTEGER UNIQUE NOT NULL,payload TEXT NOT NULL)")
        try db.exec("INSERT INTO root VALUES(1,0,'empty')"); try db.exec("PRAGMA user_version=1")
        _ = try apply(db,"op1",0,"A")
        result["sqlite_version"] = String(cString:sqlite3_libversion())
    } else if stage == "kill_uncommitted" { _ = try apply(db,"op2",1,"B",crashAt:"uncommitted") }
    else if stage == "kill_committed" { _ = try apply(db,"op2",1,"B",crashAt:"committed_no_receipt") }
    else if stage == "kill_after_receipt" { _ = try apply(db,"op3",2,"C"); try emit(["receipt_revision":3]); try crash("after_receipt") }
    else if stage == "replay" { try require(apply(db,"op2",1,"B")=="replayed") }
    else if stage == "replay_final" { try require(apply(db,"op3",2,"C")=="replayed") }
    else if stage == "conflict" || stage == "stale" || stage == "replay_base_conflict" {
        var refused = false
        do { _ = try apply(db,stage == "stale" ? "op3":"op2",0,stage == "replay_base_conflict" ? "B":"wrong") }
        catch ProbeFailure.invariant { refused = true }
        try require(refused); result["refused"] = true
    } else if stage == "rollback" || stage == "migration_failure" {
        try db.exec("BEGIN IMMEDIATE")
        try db.exec("INSERT INTO operations VALUES('op3',3,'C')")
        try db.exec("UPDATE root SET revision=3,payload='C' WHERE id=1")
        if stage == "migration_failure" { try db.exec("ALTER TABLE root ADD COLUMN extra TEXT"); try db.exec("PRAGMA user_version=2") }
        try db.exec("ROLLBACK") // Inject failure before the successful commit marker.
        try require(db.number("PRAGMA user_version")==1 && db.number("SELECT count(*) FROM pragma_table_info('root') WHERE name='extra'")==0)
    } else if stage == "capacity_full" {
        let limit = try db.number("PRAGMA page_count")+1
        try db.exec("PRAGMA max_page_count=\(limit)")
        var full = false
        try db.exec("BEGIN IMMEDIATE")
        do {
            try db.exec("INSERT INTO operations VALUES('op3',3,'C')")
            try db.exec("UPDATE root SET payload=? WHERE id=1",[String(repeating:"X",count:512*1024)])
            try db.exec("COMMIT")
        } catch ProbeFailure.sqlite(let code) { full = code & 0xff == SQLITE_FULL
            if sqlite3_get_autocommit(db.handle)==0 { try db.exec("ROLLBACK") }
        }
        try require(full); result["sqlite_capacity_full"] = true
    } else { try require(stage=="verify") }
    result["revision"] = try db.number("SELECT revision FROM root WHERE id=1")
    result["payload"] = try db.query("SELECT payload FROM root WHERE id=1")
    result["operation_count"] = try db.number("SELECT count(*) FROM operations")
    result["schema"] = try db.number("PRAGMA user_version")
    result["journal_mode"] = try db.query("PRAGMA journal_mode")
    result["synchronous"] = try db.number("PRAGMA synchronous")
    try emit(result)
}
@main
struct SqliteCrashProbe {
    static func main() {
        do { try runCrashProbe() }
        catch { fputs("Synthetic SQLite probe failed: \(error)\n", stderr); exit(2) }
    }
}
