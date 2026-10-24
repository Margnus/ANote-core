import Foundation
import NoteCanvasPrototype

enum LocalReadFailure {
    case invalidFormat, unsupportedContent, characterCapacity, byteCapacity, invalidUTF8, wrongDocument, ioFailure
    var message: String {
        switch self {
        case .invalidFormat: return "笔记格式无效或版本不支持，原文件已保留"
        case .unsupportedContent: return "当前预览不支持此笔记内容，原文件已保留"
        case .characterCapacity: return "笔记内容超过预览容量，原文件已保留"
        case .byteCapacity: return "笔记文件超过预览读取容量，原文件已保留"
        case .invalidUTF8: return "笔记文本编码无效，原文件已保留"
        case .wrongDocument: return "笔记身份与文件不一致，原文件已保留"
        case .ioFailure: return "笔记文件读取失败，请检查文件后重试"
        }
    }
}
enum LocalRecoveryRead { case opened(PagedPreviewSnapshot), refused(LocalReadFailure) }

private enum TextReadRefusal: Error { case byteCapacity, invalidUTF8 }

// Preview text IO only. No UIKit, mutable owner collections or production storage schema.
enum PreviewFileIO {
    static let maxReadBytes = 32 * 1024 * 1024
    static func readUTF8(_ url: URL, maxBytes: Int = maxReadBytes) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        // Check before creating the payload buffer; growing files are checked again for each chunk.
        let size = try handle.seekToEnd(); guard size <= UInt64(maxBytes) else { throw TextReadRefusal.byteCapacity }
        try handle.seek(toOffset: 0)
        var data = Data()
        while let chunk = try handle.read(upToCount: 8192), !chunk.isEmpty {
            guard chunk.count <= maxBytes - data.count else { throw TextReadRefusal.byteCapacity }
            data.append(chunk)
        }
        guard let text = String(data: data, encoding: .utf8) else { throw TextReadRefusal.invalidUTF8 }
        return text
    }
    static func acceptRecovery(_ snapshot: PagedPreviewSnapshot, documentId: String) -> LocalRecoveryRead {
        let content = PreviewDocumentReader.shared.accept(root: snapshot.root)
        if let reason = content.failure {
            switch reason {
            case .characterCapacity: return .refused(.characterCapacity)
            case .unsupportedPreviewContent: return .refused(.unsupportedContent)
            case .invalidOrUnsupportedFormat: return .refused(.invalidFormat)
            default: preconditionFailure("Unknown content refusal")
            }
        }
        guard snapshot.documentId == documentId else { return .refused(.wrongDocument) }
        return .opened(snapshot)
    }
    static func readRecovery(_ url: URL, documentId: String, beforeRead: () throws -> Void) throws -> LocalRecoveryRead {
        do {
            try beforeRead()
            let reading = try PreviewRecoveryCodec.shared.read(text: readUTF8(url))
            guard let snapshot = reading.snapshot else {
                switch reading.failure {
                case .characterCapacity: return .refused(.characterCapacity)
                case .unsupportedPreviewContent: return .refused(.unsupportedContent)
                case .invalidOrUnsupportedFormat: return .refused(.invalidFormat)
                default: preconditionFailure("Reader returned neither snapshot nor a known failure")
                }
            }
            guard snapshot.documentId == documentId else { return .refused(.wrongDocument) }
            return .opened(snapshot)
        } catch TextReadRefusal.byteCapacity { return .refused(.byteCapacity) }
          catch TextReadRefusal.invalidUTF8 { return .refused(.invalidUTF8) }
          catch let error as NSError where error.domain == NSCocoaErrorDomain || error.domain == NSPOSIXErrorDomain {
            return .refused(.ioFailure)
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
    static func writeUTF8(_ text: String, to url: URL) throws {
        guard text.utf16.count <= 16_000_000 else { throw CocoaError(.fileWriteOutOfSpace) }
        try Data(text.utf8).write(to: url, options: .atomic)
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
            guard id.range(of: "^[a-zA-Z0-9-]{1,128}$", options: .regularExpression) != nil,
                  try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else { continue }
            guard urls.count < 2000 else { throw CocoaError(.fileReadTooLarge) }
            urls.append(url)
        }
        if let error = enumerationError { throw error }
        return urls
    }
}
