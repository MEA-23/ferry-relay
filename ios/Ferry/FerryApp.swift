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
                    if let link = PairingLink.parse(url.absoluteString) { model.pendingLink = link }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            // Lock on background, not on inactive: the Face ID prompt itself makes the app inactive.
            if phase == .background && model.appLock { model.locked = true }
            if phase == .active {
                model.reloadLocal()
                Task { await model.refresh() }
            }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
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
        return notification.request.content.userInfo["thread"] == nil ? [] : [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let thread = response.notification.request.content.userInfo["thread"] as? String
        await MainActor.run { AppModel.shared.openThread = thread }
    }
}
