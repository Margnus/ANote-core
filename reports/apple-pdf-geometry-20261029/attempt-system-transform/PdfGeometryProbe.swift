// Test-only actual CoreGraphics background + original shared Native coordinates.
import Foundation
import CoreGraphics
import ImageIO

enum GeometryProbeFailure: Error { case arguments, source, geometry, bitmap, png }
func coefficients(_ t: CGAffineTransform) -> [Double] { [t.a,t.b,t.c,t.d,t.tx,t.ty].map(Double.init) }
func png(_ context: CGContext, _ url: URL) throws {
    guard let image = context.makeImage(),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)
    else { throw GeometryProbeFailure.png }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw GeometryProbeFailure.png }
}
func pixels(_ context: CGContext, _ anchors: [[Double]], _ width: Int) throws -> [[Int]] {
    guard let memory = context.data else { throw GeometryProbeFailure.bitmap }
    let bytes = memory.assumingMemoryBound(to: UInt8.self)
    return anchors.map { point in
        let offset = (Int(point[1])*width+Int(point[0]))*4
        return (0..<4).map { Int(bytes[offset+$0]) }
    }
}
func render(_ path: String, _ group: Int, _ geometries: [[String: Any]], _ output: URL) throws -> [String: Any] {
    guard let document = CGPDFDocument(URL(fileURLWithPath: path) as CFURL), document.numberOfPages == 4 else { throw GeometryProbeFailure.source }
    var observations = [[String: Any]]()
    for index in 0..<4 {
        let geometry = geometries[group*4+index]
        guard let page = document.page(at: index+1), let dictionary = page.dictionary,
              let width = geometry["width"] as? Double, let height = geometry["height"] as? Double,
              let anchors = geometry["anchors"] as? [[Double]], anchors.count == 4,
              let unit = geometry["user_unit"] as? Double,
              let rotation = geometry["rotation"] as? Int,
              rotation == page.rotationAngle,
              width > 0, height > 0, width <= 14400, height <= 14400 else { throw GeometryProbeFailure.geometry }
        var nativeUnit: CGPDFReal = 1
        _ = CGPDFDictionaryGetNumber(dictionary, "UserUnit", &nativeUnit)
        guard Double(nativeUnit) == unit else { throw GeometryProbeFailure.geometry }
        let w = Int(width), h = Int(height)
        guard anchors.allSatisfy({ $0.count == 2 && $0[0] >= 0 && $0[0] < width && $0[1] >= 0 && $0[1] < height }),
              let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w*4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)
        else { throw GeometryProbeFailure.bitmap }
        let target = CGRect(x: 0, y: 0, width: width, height: height)
        context.setFillColor(gray: 1, alpha: 1); context.fill(target)
        context.saveGState()
        context.translateBy(x: 0, y: height); context.scaleBy(x: 1, y: -1)
        let transform = page.getDrawingTransform(.cropBox, rect: target, rotate: 0, preserveAspectRatio: true)
        context.concatenate(transform)
        let combined = context.ctm
        context.drawPDFPage(page)
        context.restoreGState()
        let prefix = "source-\(group)-page-\(index)"
        try png(context, output.appendingPathComponent(prefix+"-background.png"))
        let background = try pixels(context, anchors, w)
        context.setFillColor(red: 0, green: 0, blue: 0, alpha: 1)
        for point in anchors { context.fillEllipse(in: CGRect(x: point[0]-3, y: point[1]-3, width: 6, height: 6)) }
        try png(context, output.appendingPathComponent(prefix+"-overlay.png"))
        observations.append(["index": index, "width": w, "height": h, "user_unit": unit,
            "rotation": rotation, "drawing_transform": coefficients(transform), "combined_ctm": coefficients(combined),
            "shared_geometry_index": group*4+index, "anchors": anchors,
            "background_rgba_at_shared_anchors": background,
            "overlay_rgba_at_shared_anchors": try pixels(context, anchors, w)])
    }
    return ["source_path": path, "pages": observations]
}
do {
    let args = Array(CommandLine.arguments.dropFirst())
    guard args.count == 5 else { throw GeometryProbeFailure.arguments }
    let geometries = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: args[0]))) as! [[String: Any]]
    guard geometries.count == 12 else { throw GeometryProbeFailure.geometry }
    let output = URL(fileURLWithPath: args[1], isDirectory: true)
    var files = [[String: Any]]()
    for (group, path) in args.dropFirst(2).enumerated() {
        files.append(try autoreleasepool { try render(path, group, geometries, output) })
    }
    let result: [String: Any] = ["pid": ProcessInfo.processInfo.processIdentifier,
        "os": ProcessInfo.processInfo.operatingSystemVersionString, "files": files]
    FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted,.sortedKeys]))
    FileHandle.standardOutput.write(Data("\n".utf8))
} catch {
    FileHandle.standardError.write(Data("PDF geometry probe failed: \(error)\n".utf8)); exit(1)
}
