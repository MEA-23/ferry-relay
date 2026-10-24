import FerryKit
import Observation
import UIKit
import UserNotifications

@MainActor
@Observable
final class AppModel {
    static let shared = AppModel()

    private(set) var account: Account?
    private(set) var threads: [ThreadSummary] = []
    private(set) var devices: [DeviceRecord] = []
    private(set) var statuses: [String: StatusEvent] = [:]
    private(set) var syncing = false
    /// Set after a revocation wiped this iPhone, so onboarding can say why it's back at the start.
    var wasRemoved = false

    /// Face ID / passcode before showing the inbox (§9.1).
    var appLock = UserDefaults.standard.bool(forKey: "ferry.appLock") {
        didSet { UserDefaults.standard.set(appLock, forKey: "ferry.appLock") }
    }
    /// True until the user authenticates, whenever the app lock is on.
    var locked = UserDefaults.standard.bool(forKey: "ferry.appLock")
    var error: String?
    /// A `ferry://pair` link opened from outside the app, waiting for the user to confirm.
    var pendingLink: PairingLink?
    /// Set when a notification is tapped, so the inbox can open that thread.
    var openThread: String?
    var tab = AppTab.inbox
    /// Opens the pairing sheet on the Devices tab, e.g. from the empty inbox.
    var showPairing = false

    private var pushToken: Data?

    private init() {
        // Keychain items survive deleting the app; UserDefaults don't. A fresh install starts
        // clean instead of resurrecting an account whose inbox was deleted with the app.
        let installedKey = "ferry.installed"
        if !UserDefaults.standard.bool(forKey: installedKey) {
            Keychain.delete()
            Inbox.shared.wipe()
            UserDefaults.standard.set(true, forKey: installedKey)
        }
        account = Keychain.load()
        reloadLocal()
    }

    var sources: [DeviceRecord] { devices.filter { $0.roles.contains("source") } }

    func reloadLocal() {
        threads = (try? Inbox.shared.threads()) ?? []
        devices = (try? Inbox.shared.devices()) ?? []
        statuses = (try? Inbox.shared.statuses()) ?? [:]
        updateBadge()
    }

    // ── onboarding ───────────────────────────────────────────────────────────

    func createAccount(server: String, invite: String) async {
        await run { self.didJoin(try await Onboarding.createAccount(server: server, invite: invite)) }
    }

    func join(_ link: PairingLink) async {
        await run { self.didJoin(try await Onboarding.join(link)) }
    }

    private func didJoin(_ account: Account) {
        Inbox.shared.wipe()
        self.account = account
        pendingLink = nil
        reloadLocal()
        requestNotifications()
        Task { await refresh() }
    }

    /// Forgets the account on this device. Revokes it on the server first when still possible.
    func leave() async {
        if let account { try? await account.api.revoke(account.deviceId) }
        Keychain.delete()
        Inbox.shared.wipe()
        account = nil
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        reloadLocal()
    }

    // ── sync ─────────────────────────────────────────────────────────────────

    func refresh() async {
        guard let account, !syncing else { return }
        syncing = true
        defer { syncing = false }
        do {
            let sync = Sync(account: account)
            try await sync.refreshDevices()
            try await sync.pull()
            if let pushToken { try? await registerPush(pushToken) }
        } catch let e as APIError where e.code == "unauthorized" {
            // Revoked from another device: its messages and keys must not outlive that (§9.1).
            wipeAfterRevocation()
        } catch is CancellationError {
        } catch let e as URLError where e.code == .cancelled {
        } catch {
            self.error = Self.describe(error)
        }
        reloadLocal()
    }

    private func wipeAfterRevocation() {
        Keychain.delete()
        Inbox.shared.wipe()
        account = nil
        wasRemoved = true
        // Delivered notifications hold decrypted text too.
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    func markRead(_ thread: String) {
        try? Inbox.shared.markRead(thread: thread)
        reloadLocal()
    }

    func delete(_ thread: String) {
        try? Inbox.shared.deleteThread(thread)
        reloadLocal()
    }

    private func updateBadge() {
        UNUserNotificationCenter.current().setBadgeCount((try? Inbox.shared.unreadCount()) ?? 0)
    }

    // ── devices ──────────────────────────────────────────────────────────────

    func rename(_ device: DeviceRecord, name: String, sims: [String: String]) async {
        guard let account else { return }
        await run {
            try await Sync(account: account).setProfile(DeviceProfile(name: name, sims: sims.filter { !$0.value.isEmpty }), for: device)
            await self.refresh()
        }
    }

    func revoke(_ device: DeviceRecord) async {
        guard let account else { return }
        if device.id == account.deviceId { return await leave() }
        await run {
            try await account.api.revoke(device.id)
            await self.refresh()
        }
    }

    func makePairingCode() async throws -> PairingLink {
        guard let account else { throw CancellationError() }
        let code = try await account.api.requestPairing(roles: ["source", "receiver"])
        return PairingLink(server: account.server, userId: account.userId, code: code.code, umk: account.currentUmk)
    }

    func deviceIds() async -> Set<String>? {
        guard let account, let list = try? await account.api.devices() else { return nil }
        return Set(list.map(\.id))
    }

    // ── push ─────────────────────────────────────────────────────────────────

    func requestNotifications() {
        Task {
            let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            if granted { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    func didRegister(pushToken token: Data) {
        pushToken = token
        Task { try? await registerPush(token) }
    }

    private func registerPush(_ token: Data) async throws {
        guard let account else { return }
        #if DEBUG
        let environment = "sandbox"
        #else
        let environment = "production"
        #endif
        let hex = token.map { String(format: "%02x", $0) }.joined()
        try await account.api.putPush(account.deviceId, PushRegistration(token: hex, appId: Ferry.appId, environment: environment))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private func run(_ body: () async throws -> Void) async {
        do {
            try await body()
        } catch {
            self.error = Self.describe(error)
        }
    }

    /// Plain-language text for errors people actually hit.
    static func describe(_ error: Error) -> String {
        if let e = error as? URLError {
            switch e.code {
            case .notConnectedToInternet, .networkConnectionLost, .dataNotAllowed:
                return "You're offline. Check your internet connection and try again."
            case .cannotFindHost, .dnsLookupFailed:
                return "Couldn't find that server. Check the address for typos."
            case .cannotConnectToHost, .timedOut:
                return "Couldn't reach the server. Check the address, and that your iPhone and the server are on a network that can reach each other."
            case .appTransportSecurityRequiresSecureConnection, .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateNotYetValid:
                return "The server's secure connection isn't set up correctly. Servers on the internet need a valid https:// certificate."
            default:
                return "Network problem: \(e.localizedDescription)"
            }
        }
        if (error as NSError).domain == NSOSStatusErrorDomain {
            return "Ferry couldn't save its key on this iPhone. Try again, or reinstall the app."
        }
        if error is DecodingError {
            return "The server sent something unexpected. Is this a Ferry server?"
        }
        return error.localizedDescription
    }
}

enum AppTab: Hashable {
    case inbox, devices, settings
}
