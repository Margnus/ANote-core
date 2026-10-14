import Foundation

// Preview text IO only. No UIKit, mutable owner collections or production storage schema.
enum PreviewFileIO {
    static let maxReadBytes = 32 * 1024 * 1024
    static func readUTF8(_ url: URL, maxBytes: Int = maxReadBytes) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        // Check before creating the payload buffer; growing files are checked again for each chunk.
        let size = try handle.seekToEnd(); guard size <= UInt64(maxBytes) else { throw CocoaError(.fileReadTooLarge) }
        try handle.seek(toOffset: 0)
        var data = Data()
        while let chunk = try handle.read(upToCount: 8192), !chunk.isEmpty {
            guard chunk.count <= maxBytes - data.count else { throw CocoaError(.fileReadTooLarge) }
            data.append(chunk)
        }
        guard let text = String(data: data, encoding: .utf8) else { throw CocoaError(.fileReadCorruptFile) }
        return text
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
