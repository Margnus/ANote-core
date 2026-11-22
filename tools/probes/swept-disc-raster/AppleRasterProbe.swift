import Foundation
import UIKit
import NoteCanvasPrototype

// NativeBrushRenderer is extracted byte-for-byte from the original NativeInkCanvas.swift.
let destination = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
let fixture = SweptDiscRasterFixture()
var rows: [[String: Any]] = []
for i in 0..<fixture.count {
    let start = DispatchTime.now().uptimeNanoseconds
    let plan = fixture.plan(index: i)
    let built = DispatchTime.now().uptimeNanoseconds
    let renderer = NativeBrushRenderer(plan: plan)
    let prepared = DispatchTime.now().uptimeNanoseconds
    let name = fixture.name(index: i)
    try fixture.describe(index: i, plan: plan).write(to: destination.appendingPathComponent(name + ".json"), atomically: true, encoding: .utf8)
    let width = Int(fixture.width(index: i)), height = Int(fixture.height(index: i))
    var drawNs: UInt64 = 0
    for times in 1...2 {
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        rgba.withUnsafeMutableBytes { bytes in
            let context = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            // Shared plan pixels use a top-left origin, matching Android Bitmap coordinates.
            context.translateBy(x: 0, y: CGFloat(height)); context.scaleBy(x: 1, y: -1)
            context.setShouldAntialias(true)
            let before = DispatchTime.now().uptimeNanoseconds
            for _ in 0..<times { renderer.draw(in: context) }
            drawNs += DispatchTime.now().uptimeNanoseconds - before
        }
        try Data(rgba).write(to: destination.appendingPathComponent(name + "-\(times).rgba"))
    }
    rows.append(["name": name, "build_plan_ns": built - start, "prepare_native_ns": prepared - built,
                 "three_draws_ns": drawNs, "rgba_backing_bytes": width * height * 4])
}
try JSONSerialization.data(withJSONObject: rows, options: [.prettyPrinted, .sortedKeys]).write(to: destination.appendingPathComponent("costs.json"))
print("Swept disc native raster cases: \(fixture.count)")
