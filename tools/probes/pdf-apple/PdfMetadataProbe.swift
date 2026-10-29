// Explicit capability probe, never linked into NoteCore App or the shared SDK.
import Foundation
import CoreGraphics

enum ProbeFailure: Error { case invalidPDF, missingPage, malformedBox, missingAttribute, parentDepthExceeded }

func has(_ dictionary: CGPDFDictionaryRef, _ key: String) -> Bool {
    var object: CGPDFObjectRef?
    return CGPDFDictionaryGetObject(dictionary, key, &object)
}

// Only the three specified inheritable keys are followed. UserUnit is leaf-only.
func inherited(_ leaf: CGPDFDictionaryRef, _ key: String) throws -> (CGPDFDictionaryRef, Int) {
    var dictionary = leaf
    for depth in 0..<32 {
        if has(dictionary, key) { return (dictionary, depth) }
        var parent: CGPDFDictionaryRef?
        guard CGPDFDictionaryGetDictionary(dictionary, "Parent", &parent), let parent else {
            throw ProbeFailure.missingAttribute
        }
        dictionary = parent
    }
    throw ProbeFailure.parentDepthExceeded
}

func rawBox(_ dictionary: CGPDFDictionaryRef, _ key: String) throws -> [Double] {
    var array: CGPDFArrayRef?
    guard CGPDFDictionaryGetArray(dictionary, key, &array), let array,
          CGPDFArrayGetCount(array) == 4 else { throw ProbeFailure.malformedBox }
    return try (0..<4).map { index in
        var number: CGPDFReal = 0
        guard CGPDFArrayGetNumber(array, index, &number), number.isFinite else { throw ProbeFailure.malformedBox }
        return Double(number)
    }
}

func rect(_ value: CGRect) -> [Double] {
    [Double(value.minX), Double(value.minY), Double(value.maxX), Double(value.maxY)]
}

func inspect(_ path: String) throws -> [String: Any] {
    guard let document = CGPDFDocument(URL(fileURLWithPath: path) as CFURL) else { throw ProbeFailure.invalidPDF }
    var pages = [[String: Any]]()
    for index in 1...document.numberOfPages {
        guard let page = document.page(at: index), let leaf = page.dictionary else { throw ProbeFailure.missingPage }
        let (media, mediaDepth) = try inherited(leaf, "MediaBox")
        let (crop, cropDepth) = try inherited(leaf, "CropBox")
        let (rotation, rotationDepth) = try inherited(leaf, "Rotate")
        var rawRotation: CGPDFInteger = 0
        guard CGPDFDictionaryGetInteger(rotation, "Rotate", &rawRotation) else { throw ProbeFailure.missingAttribute }
        let unitPresent = has(leaf, "UserUnit")
        var unit: CGPDFReal = 1
        if unitPresent {
            guard CGPDFDictionaryGetNumber(leaf, "UserUnit", &unit) else { throw ProbeFailure.missingAttribute }
        }
        pages.append([
            "index": index - 1,
            "native_media_box": rect(page.getBoxRect(.mediaBox)),
            "native_crop_box": rect(page.getBoxRect(.cropBox)),
            "native_rotation": page.rotationAngle,
            "raw_effective_media_box": try rawBox(media, "MediaBox"),
            "raw_effective_crop_box": try rawBox(crop, "CropBox"),
            "raw_effective_rotation": rawRotation,
            "attribute_depth": ["MediaBox": mediaDepth, "CropBox": cropDepth, "Rotate": rotationDepth],
            "leaf_keys_present": ["MediaBox": has(leaf, "MediaBox"), "CropBox": has(leaf, "CropBox"),
                                  "Rotate": has(leaf, "Rotate"), "UserUnit": unitPresent],
            "leaf_user_unit": Double(unit), "unit_default_from_proven_leaf_absence": !unitPresent
        ])
    }
    return ["source_path": path, "page_count": document.numberOfPages, "encrypted": document.isEncrypted,
            "unlocked": document.isUnlocked, "pages": pages]
}

do {
    var files = [[String: Any]]()
    for path in CommandLine.arguments.dropFirst() {
        files.append(try autoreleasepool { try inspect(path) })
    }
    let result: [String: Any] = ["probe": "CoreGraphics native PDF metadata only", "pid": ProcessInfo.processInfo.processIdentifier,
        "os": ProcessInfo.processInfo.operatingSystemVersionString, "files": files]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys, .prettyPrinted])
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data("\n".utf8))
} catch {
    FileHandle.standardError.write(Data("PDF metadata probe failed: \(error)\n".utf8))
    exit(1)
}
