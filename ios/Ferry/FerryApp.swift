import FerryKit
import SwiftUI
import UserNotifications

@main
struct FerryApp: App {
    @UIApplicationDelegateAdaptor private var delegate: AppDelegate
    @Environment(\.scenePhase) private var scenePhase
    private let model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .overlay {
                    // Covers the inbox for the app-switcher snapshot when the app lock is on.
                    if model.appLock && model.account != nil && scenePhase != .active && !model.locked { PrivacyCover() }
                }
                .onOpenURL { url in
                    // Only offer to join; never switch accounts silently.
                    if let link = ScannedCode.parse(url.absoluteString) { model.pendingLink = link }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // Lock on background, not on inactive: the Face ID prompt itself makes the app inactive.
            if phase == .background && model.appLock { model.locked = true }
            if phase == .background { model.stopLive() }
            if phase == .active {
                model.reloadLocal()
                Task { await model.refresh() }
                model.startLive()
            }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // An introduction (§4.7) carries a Remove button.
        let remove = UNNotificationAction(identifier: "remove", title: "Remove", options: [.destructive, .authenticationRequired, .foreground])
        UNUserNotificationCenter.current().setNotificationCategories([UNNotificationCategory(identifier: "introduction", actions: [remove], intentIdentifiers: [])])
        if Keychain.load() != nil { application.registerForRemoteNotifications() }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in AppModel.shared.didRegister(pushToken: deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {}

    /// In the foreground, pull right away; show a banner only for what the extension decrypted.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        await MainActor.run {
            AppModel.shared.reloadLocal()
            Task { await AppModel.shared.refresh() }
        }
        let info = notification.request.content.userInfo
        return info["thread"] == nil && info["removeDevice"] == nil ? [] : [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let thread = info["thread"] as? String
        let device = info["removeDevice"] as? String
        await MainActor.run {
            if let device {
                // Tapped, or Remove: show the device in Devices, and ask about removing it for Remove.
                AppModel.shared.tab = .devices
                if response.actionIdentifier == "remove" { AppModel.shared.removeRequest = device }
            } else {
                AppModel.shared.openThread = thread
            }
        }
    }
}
