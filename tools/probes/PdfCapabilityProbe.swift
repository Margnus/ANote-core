import Foundation
import CoreGraphics
import ImageIO
import PDFKit
import UniformTypeIdentifiers
import Darwin

enum ProbeFailure: Error { case invariant }
func require(_ value: Bool) throws { if !value { throw ProbeFailure.invariant } }
func open(_ file: URL) throws -> CGPDFDocument {
    guard let document = CGPDFDocument(file as CFURL) else { throw ProbeFailure.invariant };return document
}
func render(_ page: CGPDFPage, _ file: URL, tile: Bool = false, gutter: Int = 0) throws -> [UInt8] {
    let crop = page.getBoxRect(.cropBox)
    let rotated = page.rotationAngle % 180 != 0
    let width = Int(rotated ? crop.height:crop.width), height=Int(rotated ? crop.width:crop.height)
    let w = tile ? 100+gutter*2:width, h = tile ? 80+gutter*2:height
    var bytes = [UInt8](repeating:255,count:w*h*4)
    try bytes.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(data:buffer.baseAddress,width:w,height:h,bitsPerComponent:8,bytesPerRow:w*4,
            space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { throw ProbeFailure.invariant }
        context.setFillColor(CGColor(gray:1,alpha:1));context.fill(CGRect(x:0,y:0,width:w,height:h))
        if tile { context.translateBy(x:CGFloat(-50+gutter),y:CGFloat(h-height+40-gutter)) }
        context.concatenate(page.getDrawingTransform(.cropBox,rect:CGRect(x:0,y:0,width:width,height:height),rotate:0,preserveAspectRatio:true))
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
do {try main()} catch {fputs("Synthetic PDF probe failed: \(error)\n",stderr);exit(2)}
