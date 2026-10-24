import XCTest

/// End-to-end pairing against a live server. Opt-in: run with
/// `TEST_RUNNER_FERRY_PAIR_LINK=<ferry://pair link> TEST_RUNNER_FERRY_SHOTS=<dir> xcodebuild test …`
/// after a source has created the account (and optionally uploaded events).
final class PairingFlowTests: XCTestCase {
    @MainActor
    func testPairAndReadInbox() throws {
        let env = ProcessInfo.processInfo.environment
        guard let link = env["FERRY_PAIR_LINK"].flatMap(URL.init(string:)) else { throw XCTSkip("FERRY_PAIR_LINK not set") }
        let shots = env["FERRY_SHOTS"]

        let app = XCUIApplication()
        app.launch()
        XCUIDevice.shared.system.open(link)
        let open = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if open.waitForExistence(timeout: 5) { open.tap() }

        let pair = app.buttons["Pair"]
        XCTAssertTrue(pair.waitForExistence(timeout: 10))
        snap(app, "02-confirm", shots)
        pair.tap()

        let alice = app.staticTexts["Alice"]
        XCTAssertTrue(alice.waitForExistence(timeout: 15), "decrypted thread should appear")
        snap(app, "03-inbox", shots)

        app.staticTexts["DeutscheBank"].tap()
        XCTAssertTrue(app.buttons["Copy 482913"].waitForExistence(timeout: 5), "one-time code should be offered")
        snap(app, "04-thread", shots)
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.tabBars.buttons["Devices"].tap()
        XCTAssertTrue(app.staticTexts["Pixel 8"].waitForExistence(timeout: 5), "decrypted profile name should show")
        snap(app, "05-devices", shots)
    }

    @MainActor
    private func snap(_ app: XCUIApplication, _ name: String, _ dir: String?) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        add(attachment)
        if let dir { try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png")) }
    }
}
