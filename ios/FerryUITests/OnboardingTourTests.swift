import XCTest

/// Walks the onboarding screens and saves screenshots for design review. Opt-in: needs no server,
/// run with `TEST_RUNNER_FERRY_SHOTS=<dir> xcodebuild test -only-testing:FerryUITests/OnboardingTourTests …`
/// on a fresh install.
final class OnboardingTourTests: XCTestCase {
    @MainActor
    func testOnboardingScreens() throws {
        guard let dir = ProcessInfo.processInfo.environment["FERRY_SHOTS"] else { throw XCTSkip("FERRY_SHOTS not set") }
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.buttons["Start a new account here"].waitForExistence(timeout: 5))
        snap("tour-1-welcome", dir)

        app.buttons["Pair with my Android phone"].tap()
        XCTAssertTrue(app.buttons["Scan pairing code"].waitForExistence(timeout: 5))
        app.buttons["Type or edit the link"].tap()
        // Multi-line SwiftUI fields report mismatched element types to XCUITest; match by label only.
        let link = app.descendants(matching: .any)["Pairing link"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 3))
        link.tap()
        link.typeText("https://example.com/not-a-link")
        app.buttons["Pair"].tap()
        XCTAssertTrue(app.staticTexts["That isn't a Ferry pairing link. It starts with ferry://pair"].waitForExistence(timeout: 3))
        snap("tour-2-join", dir)
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["Start a new account here"].tap()
        XCTAssertTrue(app.buttons["Create account"].waitForExistence(timeout: 5))
        snap("tour-3-create", dir)

        app.buttons["My own server"].tap()
        let server = app.textFields["Server address"]
        XCTAssertTrue(server.waitForExistence(timeout: 3))
        server.typeText("http://ferry.example.com")
        app.textFields["Invite code"].tap()
        XCTAssertTrue(app.staticTexts["Use https:// — plain http:// only works for servers on your local network."].waitForExistence(timeout: 3))
        snap("tour-4-invalid-server", dir)

        // A local address nobody listens on: the connection is refused straight away.
        server.tap()
        server.buttons["Clear"].tap()
        server.typeText("127.0.0.1:9")
        app.textFields["Invite code"].tap()
        app.textFields["Invite code"].typeText("demo-invite\n")
        XCTAssertTrue(app.staticTexts["Couldn't reach the server. Check the address, and that your iPhone and the server are on a network that can reach each other."].waitForExistence(timeout: 25))
        snap("tour-5-error", dir)
    }

    @MainActor
    private func snap(_ name: String, _ dir: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        add(attachment)
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }
}
