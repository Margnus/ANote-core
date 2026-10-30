import XCTest

final class SketchJourneyUITests: XCTestCase {
    @MainActor func testIndependentMemorySketchToolsReadOnlyAndHistory() {
        let app = XCUIApplication(); app.launch()
        XCTAssertTrue(app.staticTexts["内存草图 · 未保存，退出进程后内容消失"].waitForExistence(timeout: 10))
        let canvas = app.otherElements["内存画布"]
        XCTAssertTrue(canvas.exists)
        func stroke(_ y: CGFloat) {
            let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: y))
            let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: y + 0.1))
            start.press(forDuration: 0.03, thenDragTo: end)
        }
        let status = app.staticTexts["草图状态"]
        stroke(0.25); XCTAssertTrue(status.label.hasPrefix("1 笔"))
        app.buttons["红笔"].tap(); stroke(0.5); XCTAssertTrue(status.label.hasPrefix("2 笔"))
        app.buttons["只读"].tap(); stroke(0.65)
        XCTAssertTrue(status.label.hasPrefix("2 笔")); XCTAssertTrue(status.label.contains("只读"))
        app.buttons["只读"].tap(); app.buttons["撤销"].tap(); XCTAssertTrue(status.label.hasPrefix("1 笔"))
        app.buttons["重做"].tap(); XCTAssertTrue(status.label.hasPrefix("2 笔"))
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "独立内存草图两笔"; attachment.lifetime = .keepAlways; add(attachment)
    }
}
