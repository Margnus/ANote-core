import Foundation
import CoreGraphics
import CryptoKit
import ImageIO
import PDFKit
import UniformTypeIdentifiers
import Darwin

enum ProbeFailure: Error { case invariant }
func require(_ value: Bool) throws { if !value { throw ProbeFailure.invariant } }
func open(_ file: URL) throws -> CGPDFDocument {
    guard let document = CGPDFDocument(file as CFURL) else { throw ProbeFailure.invariant };return document
}
func render(_ page: CGPDFPage, _ file: URL, tile: Bool = false, gutter: Int = 0, scale: CGFloat = 1) throws -> [UInt8] {
    let crop = page.getBoxRect(.cropBox)
    let rotated = page.rotationAngle % 180 != 0
    let width = Int((rotated ? crop.height:crop.width) * scale), height=Int((rotated ? crop.width:crop.height) * scale)
    let w = tile ? 100+gutter*2:width, h = tile ? 80+gutter*2:height
    var bytes = [UInt8](repeating:255,count:w*h*4)
    try bytes.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(data:buffer.baseAddress,width:w,height:h,bitsPerComponent:8,bytesPerRow:w*4,
            space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { throw ProbeFailure.invariant }
        context.setFillColor(CGColor(gray:1,alpha:1));context.fill(CGRect(x:0,y:0,width:w,height:h))
        if tile { context.translateBy(x:CGFloat(-50+gutter),y:CGFloat(h-height+40-gutter)) }
        // getDrawingTransform may center without upscaling; apply pixel/unit scale explicitly.
        context.scaleBy(x: scale, y: scale)
        context.concatenate(page.getDrawingTransform(.cropBox,rect:CGRect(x:0,y:0,width:CGFloat(width)/scale,height:CGFloat(height)/scale),rotate:0,preserveAspectRatio:true))
        context.drawPDFPage(page)
        guard let image=context.makeImage(),let output=CGImageDestinationCreateWithURL(file as CFURL,UTType.png.identifier as CFString,1,nil) else {throw ProbeFailure.invariant}
        CGImageDestinationAddImage(output,image,nil);try require(CGImageDestinationFinalize(output))
    }
    return bytes
}
func main() throws {
    try require(CommandLine.arguments.count==2)
    let root=URL(fileURLWithPath:CommandLine.arguments[1]).standardizedFileURL
    try require(root.path.contains("/build/pdf-capability-"))
    let document=try open(root.appendingPathComponent("crop-rotations.pdf"))
    try require(document.numberOfPages==4)
    var pages=[[String:Any]]()
    for i in 0..<4 {
        guard let page=document.page(at:i+1) else {throw ProbeFailure.invariant}
        let crop=page.getBoxRect(.cropBox),media=page.getBoxRect(.mediaBox)
        let rotated=page.rotationAngle % 180 != 0
        let width=Int(rotated ? crop.height:crop.width),height=Int(rotated ? crop.width:crop.height)
        let full=try render(page,root.appendingPathComponent("page-\(i).png"))
        var result:[String:Any]=["index":i,"width":width,"height":height,"rotation":page.rotationAngle,
            "crop_box":[crop.minX,crop.minY,crop.maxX,crop.maxY],"media_box":[media.minX,media.minY,media.maxX,media.maxY]]
        if i==0 {
            let tile=try render(page,root.appendingPathComponent("tile.png"),tile:true)
            var delta=0
            for y in 0..<80 {for x in 0..<100 {for channel in 0..<3 {
                delta=max(delta,abs(Int(tile[(y*100+x)*4+channel])-Int(full[((y+40)*width+x+50)*4+channel])))
            }}}
            result["tile_max_channel_delta"]=delta
            let guarded=try render(page,root.appendingPathComponent("tile-gutter.png"),tile:true,gutter:4)
            var guardedDelta=0
            for y in 0..<80 {for x in 0..<100 {for channel in 0..<3 {
                guardedDelta=max(guardedDelta,abs(Int(guarded[((y+4)*108+x+4)*4+channel])-Int(full[((y+40)*width+x+50)*4+channel])))
            }}}
            result["tile_with_gutter_max_channel_delta"]=guardedDelta;result["tile_gutter"]=4
        }
        pages.append(result)
    }
    try require(document.page(at:0)==nil && document.page(at:5)==nil)
    let encrypted=try open(root.appendingPathComponent("password.pdf"))
    try require(encrypted.isEncrypted && !encrypted.isUnlocked)
    let wrong="wrong-fixture-password".withCString {encrypted.unlockWithPassword($0)}
    try require(!wrong && !encrypted.isUnlocked)
    let correct="notecore-fixture".withCString {encrypted.unlockWithPassword($0)}
    try require(correct && encrypted.isUnlocked && encrypted.numberOfPages==4)
    guard let first=encrypted.page(at:1) else {throw ProbeFailure.invariant}
    _ = try render(first,root.appendingPathComponent("password.png"))
    try require(CGPDFDocument(root.appendingPathComponent("malformed.pdf") as CFURL)==nil)
    let many=try open(root.appendingPathComponent("many-pages.pdf"));try require(many.numberOfPages==500)
    for i in [0,499] { guard let page=many.page(at:i+1) else {throw ProbeFailure.invariant};_ = try render(page,root.appendingPathComponent("large-\(i).png")) }
    guard let kit=PDFDocument(url:root.appendingPathComponent("crop-rotations.pdf")),let kitPage=kit.page(at:0) else {throw ProbeFailure.invariant}
    let text=kitPage.string?.contains("NoteCore PDF fixture")==true
    let outlineCount=kit.outlineRoot?.numberOfChildren ?? 0
    try require(text && outlineCount==1)
    let result:[String:Any]=["pages":pages,"password_missing_refused":true,"password_wrong_refused":true,
        "password_correct_rendered":true,"malformed_refused":true,"invalid_page_refused":true,
        "native_text_contains_fixture":text,"outline_items":outlineCount,"large_page_count":500,
        "large_render_requests":[0,499],"in_progress_render_cancellation_verified":false]
    let data=try JSONSerialization.data(withJSONObject:result,options:[.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_PDF ".utf8)+data+Data("\n".utf8))
}
func metadataMain() throws {
    try require(CommandLine.arguments.count == 3 && CommandLine.arguments[2] == "--metadata")
    let root = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
    try require(root.path.contains("/build/pdf-capability-"))
    var files = [[String: Any]]()
    for name in ["crop-rotations.pdf", "inherited-geometry.pdf", "user-unit-2.pdf"] {
        let document = try open(root.appendingPathComponent(name))
        try require(document.numberOfPages == 4 && document.isUnlocked)
        var pages = [[String: Any]]()
        for index in 0..<4 {
            guard let page = document.page(at: index + 1), let dictionary = page.dictionary else { throw ProbeFailure.invariant }
            var unitObject: CGPDFObjectRef?
            let unitPresent = CGPDFDictionaryGetObject(dictionary, "UserUnit", &unitObject)
            var unit: CGPDFReal = 1
            if unitPresent { try require(CGPDFDictionaryGetNumber(dictionary, "UserUnit", &unit)) }
            try require(unit.isFinite && unit > 0)
            let crop = page.getBoxRect(.cropBox), media = page.getBoxRect(.mediaBox)
            let rotated = page.rotationAngle % 180 != 0
            let width = Int(rotated ? crop.height : crop.width), height = Int(rotated ? crop.width : crop.height)
            let stem = name.replacingOccurrences(of: ".pdf", with: "") + "-" + String(index)
            let rawImage = stem + "-raw.png"
            _ = try render(page, root.appendingPathComponent(rawImage))
            let physicalImage = unit == 1 ? rawImage : stem + "-physical.png"
            if unit != 1 { _ = try render(page, root.appendingPathComponent(physicalImage), scale: CGFloat(unit)) }
            let transform = page.getDrawingTransform(.cropBox, rect: CGRect(x: 0, y: 0, width: CGFloat(width) * CGFloat(unit), height: CGFloat(height) * CGFloat(unit)), rotate: 0, preserveAspectRatio: true)
            let rawTransform = page.getDrawingTransform(.cropBox, rect: CGRect(x: 0, y: 0, width: width, height: height), rotate: 0, preserveAspectRatio: true)
            let scaled = rawTransform.concatenating(CGAffineTransform(scaleX: CGFloat(unit), y: CGFloat(unit)))
            var cropObject: CGPDFObjectRef?
            let leafCropPresent = CGPDFDictionaryGetObject(dictionary, "CropBox", &cropObject)
            pages.append(["index": index, "rotation": page.rotationAngle,
                "crop_box": [crop.minX, crop.minY, crop.maxX, crop.maxY],
                "media_box": [media.minX, media.minY, media.maxX, media.maxY],
                "leaf_crop_key_present": leafCropPresent, "leaf_user_unit_present": unitPresent,
                "user_unit": unit, "unscaled_width": width, "unscaled_height": height,
                "physical_width": CGFloat(width) * CGFloat(unit), "physical_height": CGFloat(height) * CGFloat(unit),
                "target_rect_drawing_transform": [transform.a, transform.b, transform.c, transform.d, transform.tx, transform.ty],
                "explicit_scaled_drawing_transform": [scaled.a, scaled.b, scaled.c, scaled.d, scaled.tx, scaled.ty],
                "raw_image": rawImage, "physical_image": physicalImage])
        }
        files.append(["name": name, "pages": pages])
    }
    let data = try JSONSerialization.data(withJSONObject: ["files": files, "scope": "Read-only CoreGraphics metadata and rendering observations; pixel/source verification is separate; no shared runtime adapter or App integration."], options: [.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_PDF_METADATA ".utf8) + data + Data("\n".utf8))
}
func passwordMain() throws {
    let root = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
    try require(root.path.contains("/build/pdf-capability-"))
    let encrypted = try open(root.appendingPathComponent("password-aes256.pdf"))
    try require(encrypted.isEncrypted && !encrypted.isUnlocked)
    try require(encrypted.page(at: 1) == nil)
    let wrong = "wrong-fixture-password".withCString { encrypted.unlockWithPassword($0) }
    try require(!wrong && !encrypted.isUnlocked)
    try require(encrypted.page(at: 1) == nil)
    let correct = "notecore-fixture".withCString { encrypted.unlockWithPassword($0) }
    try require(correct && encrypted.isUnlocked && encrypted.numberOfPages == 4)
    let plain = try open(root.appendingPathComponent("crop-rotations.pdf"))
    var pages = [[String: Any]]()
    for index in 0..<4 {
        guard let a = plain.page(at: index+1), let b = encrypted.page(at: index+1) else { throw ProbeFailure.invariant }
        let before = try render(a, root.appendingPathComponent("password-plain-\(index).png"))
        let after = try render(b, root.appendingPathComponent("password-aes256-\(index).png"))
        try require(before == after)
        pages.append(["index": index, "pixels_equal": true])
    }
    let data = try JSONSerialization.data(withJSONObject: ["missing_refused": true, "wrong_refused": true,
        "correct_rendered": true, "pages": pages], options: [.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_PDF_PASSWORD ".utf8) + data + Data("\n".utf8))
}
func outlineMain() throws {
    let url = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
    guard let document = PDFDocument(url: url), let root = document.outlineRoot else {
        throw ProbeFailure.invariant
    }
    var remaining = 1000
    func children(of parent: PDFOutline, depth: Int) throws -> [[String: Any]] {
        let count = parent.numberOfChildren
        if count == 0 { return [] }
        try require(depth < 5 && count > 0 && count <= remaining)
        remaining -= count
        return try (0..<count).map { index in
            guard let item = parent.child(at: index), let title = item.label,
                  let destination = item.destination ?? (item.action as? PDFActionGoTo)?.destination,
                  let page = destination.page, page.document === document else {
                throw ProbeFailure.invariant
            }
            let sourceIndex = document.index(for: page)
            try require((0..<document.pageCount).contains(sourceIndex))
            return ["title": title, "source_index": sourceIndex, "children": try children(of: item, depth: depth + 1)]
        }
    }
    let data = try JSONSerialization.data(withJSONObject: try children(of: root, depth: 0), options: [.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_PDF_OUTLINE ".utf8) + data + Data("\n".utf8))
}

private struct CompositeProbePlan: Decodable {
    struct Quality: Decodable {
        struct Task: Decodable {
            let pageId: String
            let outputNumber: Int
            let sourceIndex: Int?
            let pageWidth: Int
            let pageHeight: Int
            let pixelWidth: Int
            let pixelHeight: Int
            let argbBytes: Int
        }
        let scale: Int
        let retainedArgbBytes: Int
        let tasks: [Task]
    }
    let sourceSha256: String
    let sourcePageCount: Int
    let qualities: [Quality]
}

func compositeMain() throws {
    let root = URL(fileURLWithPath: CommandLine.arguments[1]).standardizedFileURL
    try require(root.path.contains("/build/pdf-capability-"))
    func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    let planBytes = try Data(contentsOf: root.appendingPathComponent("composite-plan.json"))
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    let plan = try decoder.decode(CompositeProbePlan.self, from: planBytes)
    let original = root.appendingPathComponent("crop-rotations.pdf")
    try require(try digest(Data(contentsOf: original)) == plan.sourceSha256)
    let source = try open(original)
    try require(source.numberOfPages == plan.sourcePageCount && plan.qualities.count == 2)

    func export(_ name: String, quality: CompositeProbePlan.Quality, cancelled: Bool) throws {
        let output = root.appendingPathComponent(name)
        let staging = root.appendingPathComponent("composite-\(UUID().uuidString).stage")
        let files = FileManager.default
        try require(!files.fileExists(atPath: output.path))
        defer { if files.fileExists(atPath: staging.path) { try? files.removeItem(at: staging) } }
        do {
            guard let pdf = CGContext(staging as CFURL, mediaBox: nil, nil) else { throw ProbeFailure.invariant }
            defer { pdf.closePDF() }
            var packedBytes = 0
            for (index, spec) in quality.tasks.enumerated() {
                try require(spec.outputNumber == index + 1)
                var bounds = CGRect(x: 0, y: 0, width: spec.pageWidth, height: spec.pageHeight)
                let width = spec.pixelWidth, height = spec.pixelHeight
                guard let raster = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
                        bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)
                else { throw ProbeFailure.invariant }
                let allocation = raster.bytesPerRow * height
                try require(allocation == spec.argbBytes && allocation <= quality.retainedArgbBytes - packedBytes)
                packedBytes += allocation
                raster.scaleBy(x: CGFloat(quality.scale), y: CGFloat(quality.scale))
                raster.setFillColor(CGColor(gray: 1, alpha: 1))
                raster.fill(bounds)
                if let index = spec.sourceIndex {
                    guard let page = source.page(at: index + 1) else { throw ProbeFailure.invariant }
                    let crop = page.getBoxRect(.cropBox)
                    let rotated = page.rotationAngle % 180 != 0
                    try require((rotated ? crop.height : crop.width) == bounds.width &&
                        (rotated ? crop.width : crop.height) == bounds.height)
                    raster.saveGState()
                    raster.concatenate(page.getDrawingTransform(.cropBox, rect: bounds, rotate: 0, preserveAspectRatio: true))
                    raster.drawPDFPage(page)
                    raster.restoreGState()
                }
                raster.setFillColor(red: 0, green: 1, blue: 1, alpha: 1)
                raster.fill(CGRect(x: 45, y: spec.pageHeight - 65, width: 20, height: 20))
                guard let image = raster.makeImage() else { throw ProbeFailure.invariant }
                let box = Data(bytes: &bounds, count: MemoryLayout<CGRect>.size)
                pdf.beginPDFPage([kCGPDFContextMediaBox as String: box] as CFDictionary)
                pdf.draw(image, in: bounds)
                pdf.endPDFPage()
            }
        }
        let staged = try open(staging)
        try require(staged.numberOfPages == quality.tasks.count)
        if !cancelled { try files.moveItem(at: staging, to: output) }
    }

    var pageIds = [String: [String]]()
    for quality in plan.qualities {
        let name = "quality-\(quality.scale).pdf"
        try export(name, quality: quality, cancelled: false)
        let document = try open(root.appendingPathComponent(name))
        try require(document.numberOfPages == quality.tasks.count)
        for index in quality.tasks.indices {
            guard let page = document.page(at: index + 1) else { throw ProbeFailure.invariant }
            _ = try render(page, root.appendingPathComponent("\(name).\(index).png"))
        }
        pageIds[String(quality.scale)] = quality.tasks.map(\.pageId)
    }
    try export("cancelled.pdf", quality: plan.qualities[0], cancelled: true)
    let names = try FileManager.default.contentsOfDirectory(atPath: root.path)
    try require(!names.contains("cancelled.pdf") && !names.contains { $0.hasSuffix(".stage") })
    let result: [String: Any] = ["cancelled_before_publish": true, "plan_sha256": digest(planBytes), "page_ids": pageIds]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
    FileHandle.standardOutput.write(Data("NOTECORE_PDF_COMPOSITE ".utf8) + data + Data("\n".utf8))
}

do {
    if CommandLine.arguments.count == 3 && CommandLine.arguments[2] == "--password" { try passwordMain() }
    else if CommandLine.arguments.count == 3 && CommandLine.arguments[2] == "--outline" { try outlineMain() }
    else if CommandLine.arguments.count == 3 && CommandLine.arguments[2] == "--composite" { try compositeMain() }
    else if CommandLine.arguments.count == 3 { try metadataMain() }
    else { try main() }
} catch {fputs("Synthetic PDF probe failed: \(error)\n",stderr);exit(2)}
