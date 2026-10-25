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
    /// Mute rules and decisions on this iPhone (see `Filters`).
    private(set) var mutes: [Mute] = []
    /// Texts that look like marketing get a "Mute?" suggestion in the inbox.
    private(set) var suggestMuting = false
    /// Texts that score as marketing arrive muted, without asking.
    private(set) var autoMute = false
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
    /// A pairing link opened from outside the app, waiting for the user to confirm.
    var pendingLink: ScannedCode?
    /// Set when a notification is tapped, so the inbox can open that thread.
    var openThread: String?
    var tab = AppTab.inbox
    /// Opens the pairing sheet on the Devices tab, e.g. from the empty inbox.
    var showPairing = false
    /// A device to ask about removing, from an introduction's Remove button (§4.7).
    var removeRequest: String?

    private var pushToken: Data?
    private var live: Task<Void, Never>?
    private var liveId = UUID()

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
        // What counts as wanted grows with the inbox; relearn once per launch.
        if account != nil {
            Task.detached(priority: .utility) {
                try? Inbox.shared.relearn()
                await MainActor.run { AppModel.shared.reloadLocal() }
            }
        }
    }

    var sources: [DeviceRecord] { devices.filter { $0.roles.contains("source") } }

    func reloadLocal() {
        threads = (try? Inbox.shared.threads()) ?? []
        devices = (try? Inbox.shared.devices()) ?? []
        statuses = (try? Inbox.shared.statuses()) ?? [:]
        mutes = (try? Inbox.shared.mutes()) ?? []
        suggestMuting = Inbox.shared.suggestMuting
        autoMute = Inbox.shared.autoMute
        updateBadge()
    }

    // ── onboarding ───────────────────────────────────────────────────────────

    func createAccount(server: String, invite: String) async {
        await run { self.didJoin(try await Onboarding.createAccount(server: server, invite: invite)) }
    }

    func join(_ code: ScannedCode) async {
        await run {
            switch code {
            case .receiver(let link): self.didJoin(try await Onboarding.join(link))
            case .node(let link): self.didJoin(try await Onboarding.joinNode(link))
            case .intro: throw UserFacingError("This code adds an Android phone that relays its SIMs. Scan it with Ferry on that phone.")
            }
        }
    }

    private func didJoin(_ account: Account) {
        Inbox.shared.wipe()
        self.account = account
        pendingLink = nil
        reloadLocal()
        requestNotifications()
        Task { await refresh() }
        startLive()
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

    /// While the app is open, a long poll brings events in the moment they're stored (§6.5),
    /// sent and delivered updates included, which never come as a push. Push covers the rest.
    func startLive() {
        guard live == nil, account != nil else { return }
        let id = UUID()
        liveId = id
        live = Task {
            while !Task.isCancelled, let account {
                do {
                    let start = Date.now, before = Inbox.shared.watermark
                    // Under the requests' 20-second timeout.
                    try await Sync(account: account).pull(wait: 15)
                    reloadLocal()
                    // A server without live pull answers at once with nothing; don't spin on it.
                    if Inbox.shared.watermark == before, Date.now.timeIntervalSince(start) < 1 { try? await Task.sleep(for: .seconds(15)) }
                } catch let e as APIError where e.code == "unauthorized" {
                    break // a refresh notices the revocation and wipes
                } catch {
                    if Task.isCancelled { break }
                    try? await Task.sleep(for: .seconds(5))
                }
            }
            // Only if a newer loop hasn't taken over since this one was stopped.
            if liveId == id { live = nil }
        }
    }

    func stopLive() {
        live?.cancel()
        live = nil
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

    func archive(_ thread: String, _ archived: Bool) {
        try? Inbox.shared.archive(thread, archived)
        reloadLocal()
    }

    // ── muting ───────────────────────────────────────────────────────────────

    func isMuted(_ thread: String) -> Bool {
        guard let target = Filters.target(thread) else { return false }
        return mutes.contains { $0.target == target }
    }

    /// Mutes `thread`'s sender or app, wherever it appears.
    func mute(_ thread: String, label: String) {
        try? Inbox.shared.mute(threadKey: thread, label: label)
        reloadLocal()
    }

    func unmute(_ target: Filters.Target) {
        try? Inbox.shared.unmute(target)
        reloadLocal()
    }

    /// The "Mute?" suggestion was wrong: this sender isn't marketing.
    func keep(_ thread: String, label: String) {
        try? Inbox.shared.keep(threadKey: thread, label: label)
        reloadLocal()
    }

    func setSuggestMuting(_ on: Bool) {
        Inbox.shared.suggestMuting = on
        suggestMuting = on
    }

    func setAutoMute(_ on: Bool) {
        Inbox.shared.autoMute = on
        autoMute = on
    }

    func addMutedWord(_ word: String) {
        try? Inbox.shared.addWord(word)
        reloadLocal()
    }

    func removeMutedWord(_ value: String) {
        try? Inbox.shared.removeWord(value)
        reloadLocal()
    }

    func skipSuggestion(_ value: String) {
        try? Inbox.shared.skipWord(value)
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

    /// Removes `devices` in order: one, or a node and the nodes it added.
    func revoke(_ devices: [DeviceRecord]) async {
        guard let account else { return }
        if devices.contains(where: { $0.id == account.deviceId }) { return await leave() }
        let sync = Sync(account: account)
        await run {
            for device in devices {
                // Stop trusting a node's key here first, whatever the server does next (§3.7).
                sync.forgetNode(device)
                try await account.api.revoke(device.id)
            }
            await self.refresh()
        }
    }

    func canSend(via deviceId: String) -> Bool {
        guard let account else { return false }
        return Sync(account: account).canSend(via: devices.first { $0.id == deviceId })
    }

    /// §6.6: sends a reply through the node `last` came from.
    func sendSms(_ body: String, to number: String, thread: String, replyingTo last: Message) async {
        guard let account, let node = devices.first(where: { $0.id == last.sourceDeviceId }) else { return }
        do {
            try await Sync(account: account).sendSms(body, to: number, via: node, thread: thread, replyingTo: last)
        } catch {
            self.error = Self.describe(error)
        }
        reloadLocal()
    }

    /// Where a new text can go out from: each phone that sends for this one, once per active SIM.
    var sendOptions: [SendOption] {
        guard let account else { return [] }
        let sync = Sync(account: account)
        return devices.filter { sync.canSend(via: $0) }.flatMap { node in
            let sims = statuses[node.id]?.sims?.filter(\.active) ?? []
            return sims.isEmpty ? [SendOption(node: node, sim: nil)] : sims.map { SendOption(node: node, sim: $0) }
        }
    }

    /// §6.6: a new text to `number`, added to its conversation through that phone. Returns the conversation.
    func sendSms(_ body: String, to number: String, from option: SendOption) async -> String? {
        guard let account else { return nil }
        let thread = Receiver.smsThread(number, source: option.node.id)
        // Keeps the contact name if this number already has a conversation.
        let title = (try? Inbox.shared.messages(thread: thread))?.last?.title ?? number
        do {
            let sim = option.sim.map { Sim(slot: $0.slot, subscriptionId: $0.subscriptionId, carrier: $0.carrier) }
            try await Sync(account: account).sendSms(body, to: number, via: option.node, thread: thread, title: title, sim: sim)
        } catch {
            self.error = Self.describe(error)
        }
        reloadLocal()
        return thread
    }

    /// Nodes that `device` paired or introduced (§5.4).
    func added(by device: DeviceRecord) -> [DeviceRecord] {
        devices.filter { $0.profile?.addedBy == device.id && $0.id != account?.deviceId }
    }

    /// §5.3: a code for another phone that reads messages. It carries the UMK.
    func makeReaderCode() async throws -> PairingLink {
        guard let account else { throw CancellationError() }
        let code = try await account.api.requestPairing(roles: ["receiver"])
        return PairingLink(server: account.server, userId: account.userId, code: code.code, umk: account.currentUmk)
    }

    /// §5.6 flow B: a code that adds the Android phone that scans it as a node, with its own key.
    func makeNodeCode() async throws -> IntroLink {
        guard let account else { throw CancellationError() }
        let code = try await account.api.requestPairing(roles: ["source"])
        let acctPk = FerryCrypto.accountKeyPair(account.currentUmk).publicKey
        return IntroLink(server: account.server, userId: account.userId, code: code.code, acctPk: acctPk, secret: FerryCrypto.randomBytes(FerryCrypto.keyBytes))
    }

    /// Whether the node that scanned `link` has answered. Trusts it once its MAC checks out, which
    /// proves the key came from the phone that scanned the code, not from the server.
    func checkNodeReply(_ link: IntroLink) async throws -> Bool {
        guard let account, let msg = try await account.api.pairingReply(code: link.code) else { return false }
        guard let nodePk = Data(base64URL: msg.publicKey), let mac = Data(base64URL: msg.mac),
              FerryCrypto.verifyPairingMac(secret: link.secret, userId: account.userId, deviceId: msg.deviceId, publicKey: nodePk, mac: mac)
        else { throw UserFacingError("The new phone's answer didn't check out. Make a new code and scan it again.") }
        try await Sync(account: account).trustNode(msg.deviceId, nodePk: nodePk)
        await refresh()
        return true
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

/// An error whose message is already written for people.
struct UserFacingError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

enum AppTab: Hashable {
    case inbox, devices, settings
}

/// A phone and SIM a new text can be sent from.
struct SendOption: Identifiable, Hashable {
    let node: DeviceRecord
    let sim: SimStatus?

    var id: String { "\(node.id):\(sim?.slot ?? -1)" }
    var label: String { sim.map { "\(node.simLabel($0.slot)) · \(node.displayName)" } ?? node.displayName }
}
