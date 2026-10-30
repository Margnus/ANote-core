import Foundation
import CryptoKit
import Darwin

// Disposable simulator App cache only. No NoteCore framework or database linkage.
enum ProbeFailure: Error { case invariant(String) }
func check(_ value: Bool, _ message: String) throws { if !value { throw ProbeFailure.invariant(message) } }
let payload = Data((0..<65539).map { UInt8(($0 * 31 + 7) % 251) })
func write(_ url: URL, _ bytes: Data) throws {
    try check(!FileManager.default.fileExists(atPath: url.path), "fresh fixture")
    try check(FileManager.default.createFile(atPath: url.path, contents: nil), "create")
    let handle = try FileHandle(forWritingTo: url)
    try handle.write(contentsOf: bytes)
    try handle.synchronize()
    try handle.close()
}
func facts(_ url: URL) throws -> [String: Any] {
    let bytes = try Data(contentsOf: url)
    let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
    return ["bytes": bytes.count, "sha256": SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(),
            "inode": attrs[.systemFileNumber]!, "device": attrs[.systemNumber]!]
}
func equal(_ a: [String: Any], _ b: [String: Any]) -> Bool { NSDictionary(dictionary: a).isEqual(to: b) }
func pair(_ root: URL, _ name: String) throws -> [String: Any] {
    let stage = root.appendingPathComponent(name + ".stage"), target = root.appendingPathComponent(name + ".resource")
    try check(Data(contentsOf: stage) == payload, "complete stage")
    try check(Data(contentsOf: target) == payload, "complete target")
    let a = try facts(stage), b = try facts(target)
    try check(equal(a, b), "same inode/device/hash")
    return ["stage": a, "target": b]
}
func emit(_ value: [String: Any]) throws {
    let data = try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
    print("NOTECORE_PROMOTE " + String(data: data, encoding: .utf8)!)
    fflush(stdout)
}
do {
    try check(CommandLine.arguments.count == 3, "directory and mode")
    let root = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
    try check(root.lastPathComponent.range(of: "^resource-promote-[a-f0-9-]{36}$", options: .regularExpression) != nil, "scope")
    try check(root.deletingLastPathComponent().lastPathComponent == "Caches", "App cache only")
    try check(String(contentsOf: root.appendingPathComponent("scope.txt"), encoding: .utf8) == root.lastPathComponent, "marker")
    let mode = CommandLine.arguments[2]
    var result: [String: Any] = ["mode": mode, "pid": getpid()]
    if mode == "cases" {
        let stage = root.appendingPathComponent("fresh.stage"), target = root.appendingPathComponent("fresh.resource")
        try write(stage, payload)
        try FileManager.default.linkItem(at: stage, to: target)
        result["fresh"] = try pair(root, "fresh")
        try FileManager.default.removeItem(at: stage)
        try check(Data(contentsOf: target) == payload, "unlink stage preserves target")
        result["stage_unlink_keeps_target"] = true
        for name in ["identical", "corrupt"] {
            let source = root.appendingPathComponent(name + ".stage"), destination = root.appendingPathComponent(name + ".resource")
            try write(source, payload); try write(destination, name == "identical" ? payload : Data([1,2,3]))
            let before = try facts(destination), sourceBefore = try facts(source)
            var errorDetails: [String: Any] = [:]
            do { try FileManager.default.linkItem(at: source, to: destination) }
            catch {
                let native = error as NSError
                try check(native.domain == NSCocoaErrorDomain && native.code == NSFileWriteFileExistsError, "expected file exists")
                errorDetails = ["domain": native.domain, "code": native.code, "description": native.localizedDescription]
                if let underlying = native.userInfo[NSUnderlyingErrorKey] as? NSError {
                    errorDetails["underlying_domain"] = underlying.domain; errorDetails["underlying_code"] = underlying.code
                }
            }
            try check(!errorDetails.isEmpty, "must reject existing target")
            try check(equal(before, facts(destination)) && equal(sourceBefore, facts(source)), "no mutation")
            result[name] = ["error": errorDetails, "stage": try facts(source), "target": try facts(destination)]
        }
    } else if mode == "crash" {
        try write(root.appendingPathComponent("crash.stage"), payload)
        try FileManager.default.linkItem(at: root.appendingPathComponent("crash.stage"), to: root.appendingPathComponent("crash.resource"))
        result["linked_before_stage_cleanup"] = try pair(root, "crash")
        result["status"] = "kill_boundary"; try emit(result)
        kill(getpid(), SIGKILL)
        throw ProbeFailure.invariant("SIGKILL returned")
    } else if mode == "recover" { result["after_process_death"] = try pair(root, "crash") }
    else { throw ProbeFailure.invariant("mode") }
    result["status"] = "passed"; try emit(result)
} catch { fputs("\(error)\n", stderr); exit(1) }
