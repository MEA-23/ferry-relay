import Foundation

/// Pull-based catch-up (§6.3, §6.4) and device/profile management (§5.4).
public struct Sync: Sendable {
    public let account: Account
    public let inbox: Inbox

    public init(account: Account, inbox: Inbox = .shared) {
        self.account = account
        self.inbox = inbox
    }

    /// Pulls everything after the watermark, files it, and acks. Returns how many new messages arrived.
    @discardableResult
    public func pull() async throws -> Int {
        let api = account.api
        let receiver = Receiver(account: account, inbox: inbox)
        var added = 0
        var after = inbox.watermark
        while true {
            let page = try await api.pull(after: after)
            for e in page.events {
                do {
                    if case .new = try receiver.ingest(e) { added += 1 }
                } catch {
                    // Undecryptable (e.g. a key this device never had). Skip it rather than stall the stream.
                }
            }
            if page.next != after {
                try await api.ack(upTo: page.next)
                inbox.watermark = page.next
                after = page.next
            }
            if !page.hasMore { break }
        }
        try? inbox.pruneSeen(now: Int64(Date().timeIntervalSince1970 * 1000))
        return added
    }

    /// Fetches the device list, decrypts profiles and caches them for the extension.
    @discardableResult
    public func refreshDevices() async throws -> [DeviceRecord] {
        let records = try await account.api.devices().map { d in
            DeviceRecord(
                id: d.id, roles: d.roles, platform: d.platform, appVersion: d.appVersion, createdAt: d.createdAt,
                lastSeenAt: d.lastSeenAt, profileVersion: d.profile?.version, profile: d.profile.flatMap(decryptProfile(of: d.id))
            )
        }
        try inbox.saveDevices(records)
        return records
    }

    private func decryptProfile(of deviceId: String) -> (SealedProfile) -> DeviceProfile? {
        { p in
            let sealed = FerryCrypto.Sealed(keyId: p.keyId, nonce: p.nonce, ct: p.ct)
            guard let plain = try? FerryCrypto.decryptProfile(umks: account.umks, userId: account.userId, deviceId: deviceId, version: p.version, sealed: sealed)
            else { return nil }
            return try? JSONDecoder().decode(DeviceProfile.self, from: plain)
        }
    }

    /// Encrypts and uploads a new profile for any device of this account (§5.4).
    public func setProfile(_ profile: DeviceProfile, for device: DeviceRecord) async throws {
        let version = (device.profileVersion ?? 0) + 1
        let sealed = FerryCrypto.encryptProfile(
            umk: account.currentUmk, userId: account.userId, deviceId: device.id, version: version,
            plaintext: try JSONEncoder().encode(profile)
        )
        try await account.api.putProfile(device.id, SealedProfile(version: version, keyId: sealed.keyId, nonce: sealed.nonce, ct: sealed.ct))
    }
}

/// Account creation and pairing (§5.2, §5.3).
public enum Onboarding {
    public static func createAccount(server: String, invite: String?) async throws -> Account {
        guard let url = Ferry.normalizeServer(server) else {
            throw APIError(status: 0, code: "invalid_server", message: "Enter an https:// server address (http:// only works for servers on your local network).")
        }
        let umk = FerryCrypto.generateUmk()
        let req = CreateAccountRequest(
            device: NewDevice(roles: ["receiver"], appVersion: Ferry.appVersion),
            keyId: FerryCrypto.keyId(umk),
            invite: invite.flatMap { $0.isEmpty ? nil : $0 }
        )
        let created = try await API(baseURL: url, token: nil).createAccount(req)
        let account = Account(server: url.absoluteString, userId: created.userId, deviceId: created.deviceId, token: created.token, umk: umk)
        try Keychain.save(account)
        return account
    }

    public static func join(_ link: PairingLink) async throws -> Account {
        let api = API(baseURL: URL(string: link.server)!, token: nil)
        let registered = try await api.registerDevice(
            RegisterDeviceRequest(userId: link.userId, code: link.code, roles: ["receiver"], appVersion: Ferry.appVersion)
        )
        let account = Account(server: link.server, userId: link.userId, deviceId: registered.deviceId, token: registered.token, umk: link.umk)
        guard registered.keyId == account.currentKeyId else {
            // §5.3: the scanned key isn't the account's. Discard everything; revoke the half-made device.
            try? await account.api.revoke(registered.deviceId)
            throw APIError(status: 0, code: "key_mismatch", message: "This pairing code's key doesn't match the account. Make a new code on your other device.")
        }
        do {
            try Keychain.save(account)
        } catch {
            try? await account.api.revoke(registered.deviceId)
            throw error
        }
        return account
    }
}
