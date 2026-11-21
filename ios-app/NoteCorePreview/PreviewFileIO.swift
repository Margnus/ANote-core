import Foundation
import NoteCanvasPrototype

enum LocalReadFailure: Error {
    case invalidFormat, unsupportedContent, characterCapacity, byteCapacity, invalidUTF8, wrongDocument, invalidLocalIdentity, ioFailure
    init(_ reason: PreviewReadFailure) {
        switch reason {
        case .characterCapacity: self = .characterCapacity
        case .unsupportedPreviewContent: self = .unsupportedContent
        case .invalidOrUnsupportedFormat: self = .invalidFormat
        default: preconditionFailure("Unknown content refusal")
        }
    }
    var message: String {
        switch self {
        case .invalidFormat: return "笔记格式无效或版本不支持，原文件已保留"
        case .unsupportedContent: return "当前预览不支持此笔记内容，原文件已保留"
        case .characterCapacity: return "笔记内容超过预览容量，原文件已保留"
        case .byteCapacity: return "笔记文件超过预览读取容量，原文件已保留"
        case .invalidUTF8: return "笔记文本编码无效，原文件已保留"
        case .wrongDocument: return "笔记身份与文件不一致，原文件已保留"
        case .invalidLocalIdentity: return "笔记身份不适用于本机文件，原文件已保留"
        case .ioFailure: return "笔记文件读取失败，请检查文件后重试"
        }
    }
}
enum LocalRecoveryRead { case opened(PagedPreviewSnapshot), refused(LocalReadFailure) }

enum CatalogCapacityRefusal: Error { case tooManyRows }

private enum TextReadRefusal: Error { case byteCapacity, invalidUTF8 }

private struct BoundedUTF8Input {
    private static let chunkByteLimit = 8192
    private let handle: FileHandle
    private var remaining: Int
    private var carry = Data()
    private var first = true

    init(handle: FileHandle, maxBytes: Int) throws {
        guard maxBytes >= 0, try handle.seekToEnd() <= UInt64(maxBytes) else { throw TextReadRefusal.byteCapacity }
        try handle.seek(toOffset: 0)
        self.handle = handle
        remaining = maxBytes
    }

    private mutating func readBytes() throws -> Data? {
        // Read one extra byte at the limit to distinguish true EOF from a growing file.
        let limit = min(Self.chunkByteLimit - carry.count - 1, remaining) + 1
        guard let bytes = try handle.read(upToCount: limit), !bytes.isEmpty else { return nil }
        guard bytes.count <= remaining else { throw TextReadRefusal.byteCapacity }
        remaining -= bytes.count
        return bytes
    }

    private func completePrefixLength(_ bytes: Data) -> Int {
        var last = bytes.count - 1
        // A valid scalar can leave at most three bytes at the end of this chunk.
        while last >= max(0, bytes.count - 3), bytes[last] & 0xc0 == 0x80 { last -= 1 }
        guard last >= max(0, bytes.count - 3) else { return bytes.count }
        let width: Int
        switch bytes[last] {
        case 0xc2...0xdf: width = 2
        case 0xe0...0xef: width = 3
        case 0xf0...0xf4: width = 4
        default: return bytes.count
        }
        return bytes.count - last < width ? last : bytes.count
    }

    mutating func next() throws -> String? {
        while let chunk = try readBytes() {
            let bytes: Data
            if carry.isEmpty { bytes = chunk }
            else { carry.append(chunk); bytes = carry }
            let end = completePrefixLength(bytes)
            carry = Data(bytes.suffix(from: end))
            guard end > 0 else { continue }
            let prefix = bytes.prefix(end)
            // Decode with the deployment-compatible stdlib API, then reject any repair.
            // A malformed byte sequence cannot round-trip through replacement scalars.
            var text = String(decoding: prefix, as: UTF8.self)
            guard text.utf8.elementsEqual(prefix) else {
                // Before streaming, byte capacity and filesystem failures took precedence over malformed UTF-8.
                while try readBytes() != nil {}
                throw TextReadRefusal.invalidUTF8
            }
            defer { first = false }
            // Only one BOM at the beginning of the file is a transport signature.
            // Interior/chunk-leading BOMs are canonical content, preserved exactly once.
            if first, text.hasPrefix("\u{feff}") { text.removeFirst() }
            if !text.isEmpty { return text }
        }
        guard carry.isEmpty else { throw TextReadRefusal.invalidUTF8 }
        return nil
    }
}

// Preview text IO only. No UIKit, mutable owner collections or production storage schema.
enum PreviewWriteBoundary: String { case beforeEncoding, afterEncoding, beforeAtomicWrite, afterAtomicWrite }

enum PreviewFileIO {
    // Local filename policy only; canonical document IDs may contain other Unicode scalars.
    // Check every byte explicitly: regex end-anchor behaviour differs across Foundation targets.
    static func isLocalDocumentId(_ id: String) -> Bool {
        (1...128).contains(id.utf16.count) && id.utf8.allSatisfy {
            $0 == 45 || (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0)
        }
    }
    static let maxReadBytes = Int(PagedPreviewCodec.shared.maximumUtf8ByteCount())
    static func readUTF8(_ url: URL, maxBytes: Int = maxReadBytes) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var input = try BoundedUTF8Input(handle: handle, maxBytes: maxBytes)
        var text = ""
        while let chunk = try input.next() { text.append(chunk) }
        return text
    }
    private static func readPreview(_ url: URL, allowRecovery: Bool) throws -> PreviewRecoveryRead {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var input = try BoundedUTF8Input(handle: handle, maxBytes: maxReadBytes)
        var transportError: Error?
        // The parser invokes this synchronously. Return transport errors outside its format result.
        let result = ApplePreviewTextCodec.shared.read(allowRecovery: allowRecovery) {
            do { return try input.next() }
            catch { transportError = error; return nil }
        }
        if let transportError { throw transportError }
        return result
    }
    static func acceptRecovery(_ snapshot: PagedPreviewSnapshot, documentId: String) -> LocalRecoveryRead {
        let content = PreviewDocumentReader.shared.accept(root: snapshot.root)
        if let reason = content.failure {
            return .refused(LocalReadFailure(reason))
        }
        guard snapshot.documentId == documentId else { return .refused(.wrongDocument) }
        return .opened(snapshot)
    }
    static func readRecovery(_ url: URL, documentId: String, beforeRead: () throws -> Void) throws -> LocalRecoveryRead {
        do {
            try beforeRead()
            let reading = try readPreview(url, allowRecovery: true)
            guard let snapshot = reading.snapshot else {
                guard let reason = reading.failure else { preconditionFailure("Reader returned neither snapshot nor failure") }
                return .refused(LocalReadFailure(reason))
            }
            guard snapshot.documentId == documentId else { return .refused(.wrongDocument) }
            return .opened(snapshot)
        } catch TextReadRefusal.byteCapacity { return .refused(.byteCapacity) }
          catch TextReadRefusal.invalidUTF8 { return .refused(.invalidUTF8) }
          catch let error as NSError where error.domain == NSCocoaErrorDomain || error.domain == NSPOSIXErrorDomain {
            return .refused(.ioFailure)
        }
    }
    static func readImport(_ url: URL) throws -> Result<DocumentRoot, LocalReadFailure> {
        do {
            let reading = try readPreview(url, allowRecovery: false)
            if let root = reading.snapshot?.root { return .success(root) }
            guard let reason = reading.failure else { preconditionFailure("Reader returned neither root nor failure") }
            return .failure(LocalReadFailure(reason))
        } catch TextReadRefusal.byteCapacity { return .failure(.byteCapacity) }
          catch TextReadRefusal.invalidUTF8 { return .failure(.invalidUTF8) }
          catch let error as NSError where error.domain == NSCocoaErrorDomain || error.domain == NSPOSIXErrorDomain {
            return .failure(.ioFailure)
        }
    }
    static func readHeaderUTF8(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var data = Data(), newlines = 0
        while let chunk = try handle.read(upToCount: 256), !chunk.isEmpty {
            for byte in chunk {
                guard data.count < 4096 else { throw CocoaError(.fileReadTooLarge) }
                data.append(byte)
                if byte == 10 { newlines += 1 }
                if newlines == 2 {
                    guard let text = String(data: data, encoding: .utf8) else { throw CocoaError(.fileReadCorruptFile) }
                    return text
                }
            }
        }
        throw CocoaError(.fileReadCorruptFile)
    }
    static func writeSnapshot(_ snapshot: any PreviewSaveSnapshot, to url: URL,
                              observeBoundary: (PreviewWriteBoundary) -> Void) throws {
        observeBoundary(.beforeEncoding)
        let text = try snapshot.encode()
        observeBoundary(.afterEncoding)
        try writeUTF8(text, to: url, observeBoundary: observeBoundary)
    }

    static func writeUTF8(_ text: String, to url: URL, observeBoundary: (PreviewWriteBoundary) -> Void = { _ in }) throws {
        guard text.utf16.count <= Int(PagedPreviewCodec.shared.maximumCharacterCount()) else { throw CocoaError(.fileWriteOutOfSpace) }
        let bytes = Data(text.utf8)
        guard bytes.count <= maxReadBytes else { throw CocoaError(.fileWriteOutOfSpace) }
        observeBoundary(.beforeAtomicWrite)
        try bytes.write(to: url, options: .atomic)
        observeBoundary(.afterAtomicWrite)
    }
    static func catalog(_ directory: URL) throws -> [URL] {
        var enumerationError: Error?
        guard let iterator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey],
            options: [.skipsSubdirectoryDescendants], errorHandler: { _, error in enumerationError = error; return false }) else { throw CocoaError(.fileReadUnknown) }
        var urls: [URL] = []
        // Enumerator errors must surface rather than falsely report an empty catalog.
        guard try directory.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true else { throw CocoaError(.fileReadNoSuchFile) }
        for case let url as URL in iterator where url.pathExtension == "ncp" {
            let id = url.deletingPathExtension().lastPathComponent
            guard isLocalDocumentId(id),
                  try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { continue }
            guard urls.count < Int(PreviewCatalogBudget.shared.maxDiskRows) else { throw CatalogCapacityRefusal.tooManyRows }
            urls.append(url)
        }
        if let error = enumerationError { throw error }
        return urls
    }
}
