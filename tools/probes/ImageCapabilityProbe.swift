import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import Darwin

enum ImageProbeFailure: Error { case invalid }
func requireImage(_ value: Bool) throws { if !value { throw ImageProbeFailure.invalid } }

func saveImage(_ image: CGImage, _ name: String, _ output: URL) throws -> [String: Any] {
    guard let writer = CGImageDestinationCreateWithURL(output.appendingPathComponent(name) as CFURL,
        UTType.png.identifier as CFString, 1, nil) else { throw ImageProbeFailure.invalid }
    CGImageDestinationAddImage(writer, image, nil)
    try requireImage(CGImageDestinationFinalize(writer))
    return ["file": name, "width": image.width, "height": image.height, "row_bytes": image.bytesPerRow,
        "bits_per_component": image.bitsPerComponent, "bits_per_pixel": image.bitsPerPixel,
        "alpha_info": image.alphaInfo.rawValue, "row_payload_bytes": image.bytesPerRow * image.height]
}

func main() throws {
    try requireImage(CommandLine.arguments.count == 3)
    let source = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
    let output = URL(fileURLWithPath: CommandLine.arguments[2]).standardizedFileURL
    try requireImage(source.path.contains("/fixtures/image-orientation") && output.path.contains("/build/apple-image"))
    let manifest = try JSONSerialization.jsonObject(with: Data(contentsOf: source.appendingPathComponent("manifest.json"))) as! [String: Any]
    let cases = manifest["cases"] as! [[String: Any]]
    var rows = [[String: Any]]()
    for item in cases {
        try autoreleasepool {
            let name = item["file"] as! String
            try requireImage(!name.contains("/") && !name.contains(".."))
            guard let reader = CGImageSourceCreateWithURL(source.appendingPathComponent(name) as CFURL,
                [kCGImageSourceShouldCache: false] as CFDictionary) else { throw ImageProbeFailure.invalid }
            let properties = CGImageSourceCopyPropertiesAtIndex(reader, 0, nil) as? [String: Any] ?? [:]
            var row: [String: Any] = ["source": name, "metadata_width": properties[kCGImagePropertyPixelWidth as String] ?? NSNull(),
                "metadata_height": properties[kCGImagePropertyPixelHeight as String] ?? NSNull(),
                "metadata_orientation": properties[kCGImagePropertyOrientation as String] ?? NSNull()]
            guard let raw = CGImageSourceCreateImageAtIndex(reader, 0,
                [kCGImageSourceShouldCacheImmediately: true, kCGImageSourceShouldAllowFloat: false] as CFDictionary)
                else { throw ImageProbeFailure.invalid }
            let stem = (name as NSString).deletingPathExtension
            row["raw"] = try saveImage(raw, stem + "-raw.png", output)
            for (label, transform) in [("thumbnail_raw", false), ("thumbnail_oriented", true)] {
                guard let image = CGImageSourceCreateThumbnailAtIndex(reader, 0,
                    [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: transform,
                     kCGImageSourceThumbnailMaxPixelSize: 60, kCGImageSourceShouldCacheImmediately: true,
                     kCGImageSourceShouldAllowFloat: false] as CFDictionary) else { throw ImageProbeFailure.invalid }
                row[label] = try saveImage(image, stem + "-" + label + ".png", output)
            }
            rows.append(row)
        }
    }
    let result: [String: Any] = ["rows": rows, "thumbnail_max_pixel_size": 60,
        "scope": "Actual iOS ImageIO observations only; pixel/source audit separate. No shared adapter, heap/scratch, worker owner or App acceptance."]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_IMAGE ".utf8) + data + Data("\n".utf8))
}
do { try main() } catch { fputs("Image capability probe failed: \(error)\n", stderr); exit(2) }
