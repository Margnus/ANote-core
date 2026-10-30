import XCTest

/// Separate automation process: no @testable import, model calls or launch fixtures.
final class PreviewJourneyUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "dev.notecore.preview")

    override func setUpWithError() throws {
        continueAfterFailure = false
        XCUIDevice.shared.orientation = .portrait
    }

    private func value(_ element: XCUIElement, _ expected: String) {
        let predicate = NSPredicate(format: "value == %@", expected)
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        expectation(for: predicate, evaluatedWith: element)
        waitForExpectations(timeout: 10)
    }

    private func screenshot(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func draw(_ y: CGFloat) {
        let canvas = app.otherElements["note-canvas"]
        XCTAssertTrue(canvas.waitForExistence(timeout: 10))
        XCTAssertTrue(canvas.isHittable)
        let start = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: y))
        let end = canvas.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: y + 0.08))
        start.press(forDuration: 0.05, thenDragTo: end)
    }

    func testTwoPageInkHistoryBackgroundSaveAndProcessReopen() {
        let title = "AppleUI-" + UUID().uuidString
        app.launch()
        XCTAssertTrue(app.buttons["新建笔记"].waitForExistence(timeout: 15))
        app.buttons["新建笔记"].tap()
        let titleButton = app.buttons["note-title"]
        XCTAssertTrue(titleButton.waitForExistence(timeout: 15))
        titleButton.tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        let old = field.value as? String ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count) + title)
        app.alerts.buttons["完成"].tap()
        XCTAssertEqual(titleButton.label, title)

        let canvas = app.otherElements["note-canvas"]
        value(canvas, "0笔")
        draw(0.35)
        value(canvas, "1笔")
        draw(0.55)
        value(canvas, "2笔")
        app.buttons["撤销"].tap()
        value(canvas, "1笔")
        XCTAssertTrue(app.buttons["重做"].isEnabled)
        app.buttons["重做"].tap()
        value(canvas, "2笔")
        screenshot("page-one-two-strokes")

        app.buttons["加页"].tap()
        value(app.staticTexts["当前页码"], "2/2")
        value(canvas, "0笔")
        draw(0.45)
        value(canvas, "1笔")
        app.buttons["上一页"].tap()
        value(app.staticTexts["当前页码"], "1/2")
        value(canvas, "2笔")
        app.buttons["下一页"].tap()
        value(canvas, "1笔")
        app.buttons["放大"].tap()
        screenshot("page-two-zoomed")
        app.buttons["缩小"].tap()
        app.buttons["适合页面"].tap()
        XCUIDevice.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 5) || app.state == .runningBackgroundSuspended)
        app.activate()
        value(canvas, "1笔")

        app.buttons["保存"].tap()
        let status = app.staticTexts["note-status"]
        expectation(for: NSPredicate(format: "label == %@", "已保存到本机 · 3 笔"), evaluatedWith: status)
        waitForExpectations(timeout: 15)
        screenshot("saved-three-strokes")
        app.buttons["返回"].tap()
        let saved = app.buttons.containing(.staticText, identifier: title).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 15))
        saved.tap()
        value(app.staticTexts["当前页码"], "1/2")
        value(canvas, "2笔")
        app.buttons["下一页"].tap()
        value(canvas, "1笔")
        app.buttons["返回"].tap()
        XCTAssertTrue(saved.waitForExistence(timeout: 15))
        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10))
        app.launch()
        XCTAssertTrue(saved.waitForExistence(timeout: 15))
        saved.tap()
        value(app.staticTexts["当前页码"], "1/2")
        value(canvas, "2笔")
        app.buttons["下一页"].tap()
        value(canvas, "1笔")
        XCTAssertEqual(titleButton.label, title)
        screenshot("process-reopened-page-two")
        app.buttons["返回"].tap()
        XCTAssertTrue(saved.waitForExistence(timeout: 15))
    }
}
