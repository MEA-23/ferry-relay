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
    /// With `wait`, the first request waits up to that many seconds for something to arrive (§6.5).
    @discardableResult
    public func pull(wait: Int = 0) async throws -> Int {
        let api = account.api
        let receiver = Receiver(account: account, inbox: inbox)
        var added = 0
        var profiles: [(String, ProfileEvent)] = []
        var after = inbox.watermark
        var wait = wait
        while true {
            let page = try await api.pull(after: after, wait: wait)
            wait = 0
            for e in page.events {
                do {
                    switch try receiver.ingest(e) {
                    case .new: added += 1
                    case .profile(let deviceId, let p): profiles.append((deviceId, p))
                    default: break
                    }
                    // Introductions wait in the inbox (§4.7), as the extension may be the one that decrypts them.
                } catch is UnknownNodeError {
                    try? inbox.hold(e)
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
        if !profiles.isEmpty || !((try? inbox.held()) ?? []).isEmpty || !((try? inbox.introductions()) ?? []).isEmpty {
            added += try await syncDevices(profiles, receiver: receiver)
        }
        try? inbox.pruneSeen(now: Int64(Date().timeIntervalSince1970 * 1000))
        return added
    }

    /// Writes nodes' new names into their profiles (§4.6) and trusts the nodes they introduced (§4.7),
    /// refreshes which nodes are trusted, then retries events that were held for want of a profile
    /// (§3.7), until nothing more comes free.
    private func syncDevices(_ profiles: [(String, ProfileEvent)], receiver: Receiver) async throws -> Int {
        var updates = profiles
        var added = 0
        while true {
            for (deviceId, p) in updates {
                try? await updateProfile(of: deviceId) { current in
                    guard var next = current, next.nodePk != nil, (next.namedAt ?? 0) < p.occurredAt else { return nil }
                    next.name = p.name
                    next.sims = p.sims ?? [:]
                    next.namedAt = p.occurredAt
                    return next
                }
            }
            try await acceptIntroductions()
            try await refreshDevices()
            updates = []
            var introduced = false
            for e in try inbox.held() {
                let outcome: Receiver.Outcome?
                do {
                    outcome = try receiver.ingest(e)
                } catch is UnknownNodeError {
                    continue
                } catch {
                    outcome = nil
                }
                try? inbox.release(e.id)
                switch outcome {
                case .new: added += 1
                case .profile(let deviceId, let p): updates.append((deviceId, p))
                case .introduced: introduced = true
                default: break
                }
            }
            if updates.isEmpty && !introduced { return added }
        }
    }

    /// §4.7: trusts nodes that trusted nodes introduced. An introduction whose introducer is no longer
    /// trusted, or whose device isn't a node on the account, is dropped; one that fails to save stays.
    private func acceptIntroductions() async throws {
        let pending = try inbox.introductions()
        guard !pending.isEmpty else { return }
        let removed = inbox.removedNodeKeys
        let devices = try await account.api.devices()
        for i in pending {
            let introducer = inbox.device(i.by)
            let trusted = introducer.map { !$0.roles.contains("receiver") && $0.profile?.nodePk.map { !removed.contains($0) } == true } ?? false
            let isNode = devices.first { $0.id == i.deviceId }.map { !$0.roles.contains("receiver") } ?? false
            if trusted, isNode, !removed.contains(i.nodePk) {
                try await updateProfile(of: i.deviceId) { current in
                    guard current?.nodePk == nil else { return nil }
                    var next = current ?? DeviceProfile(name: "")
                    next.nodePk = i.nodePk
                    next.addedBy = i.by
                    return next
                }
            }
            try inbox.removeIntroduction(i.eventId)
        }
    }

    /// Fetches the device list, decrypts profiles and caches them for the extension. A trusted node
    /// that's gone from the list was removed, so its key is never trusted again (§3.7).
    @discardableResult
    public func refreshDevices() async throws -> [DeviceRecord] {
        let records = try await account.api.devices().map { d in
            DeviceRecord(
                id: d.id, roles: d.roles, platform: d.platform, appVersion: d.appVersion, createdAt: d.createdAt,
                lastSeenAt: d.lastSeenAt, profileVersion: d.profile?.version, profile: d.profile.flatMap(decryptProfile(of: d.id))
            )
        }
        let gone = ((try? inbox.devices()) ?? []).filter { old in !records.contains { $0.id == old.id } }.compactMap(\.profile?.nodePk)
        if !gone.isEmpty { inbox.removedNodeKeys.formUnion(gone) }
        try inbox.saveDevices(records)
        return records
    }

    /// Stops trusting a node's key on this iPhone, before the server is even asked to remove it.
    public func forgetNode(_ device: DeviceRecord) {
        if let pk = device.profile?.nodePk { inbox.removedNodeKeys.insert(pk) }
    }

    /// Writes `nodePk` into the node's sealed profile, which is what makes receivers trust it (§3.7).
    public func trustNode(_ deviceId: String, nodePk: Data) async throws {
        try await updateProfile(of: deviceId) { current in
            var next = current ?? DeviceProfile(name: "")
            next.nodePk = nodePk.base64URL
            next.addedBy = account.deviceId
            return next
        }
    }

    private func decryptProfile(of deviceId: String) -> (SealedProfile) -> DeviceProfile? {
        { p in
            let sealed = FerryCrypto.Sealed(keyId: p.keyId, nonce: p.nonce, ct: p.ct)
            guard let plain = try? FerryCrypto.decryptProfile(umks: account.umks, userId: account.userId, deviceId: deviceId, version: p.version, sealed: sealed)
            else { return nil }
            return try? JSONDecoder().decode(DeviceProfile.self, from: plain)
        }
    }

    /// Whether a node can send replies for this account: it's trusted and not removed (§3.7, §6.6).
    public func canSend(via device: DeviceRecord?) -> Bool {
        guard let device, !device.roles.contains("receiver"), let pk = device.profile?.nodePk else { return false }
        return !inbox.removedNodeKeys.contains(pk)
    }

    /// §6.6: asks `node` to text `body` to `number`, on the SIM `replyingTo` came in on.
    public func sendSms(_ body: String, to number: String, via node: DeviceRecord, thread: String, replyingTo last: Message?) async throws {
        try await sendSms(body, to: number, via: node, thread: thread, title: last?.title ?? number, sim: last?.event?.sim)
    }

    /// §6.6: asks `node` to text `body` to `number` from `sim` (nil: its default). It shows in the
    /// thread right away as sending; the node's reports move it on.
    public func sendSms(_ body: String, to number: String, via node: DeviceRecord, thread: String, title: String, sim: Sim?) async throws {
        guard canSend(via: node), let nodePk = node.profile?.nodePk.flatMap({ Data(base64URL: $0) }) else {
            throw APIError(status: 0, code: "not_found", message: "")
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let id = Self.uuidv7(now)
        let payload: [String: Any?] = [
            "t": "sms", "occurredAt": now, "from": number, "body": body, "direction": "out", "commandId": id,
            "sim": sim.map { ["slot": $0.slot, "subscriptionId": $0.subscriptionId as Any] },
        ]
        let plain = try JSONSerialization.data(withJSONObject: payload.compactMapValues { $0 })
        let message = Message(
            id: Receiver.commandRow(id), eventId: id, sourceDeviceId: node.id, type: "sms", threadKey: thread,
            title: title, body: body, simSlot: sim?.slot, occurredAt: now, receivedAt: now, unread: false,
            event: try? FerryEvent.decode(plain), status: "sending"
        )
        try inbox.insert(message, mergeKey: Receiver.commandRow(id), payload: plain)
        do {
            let command: [String: Any?] = [
                "t": "send_sms", "to": number, "body": body, "subscriptionId": sim?.subscriptionId,
                "createdAt": now, "expiresAt": now + Self.commandTtlMs,
            ]
            let root = try FerryCrypto.nodeRootForReceiver(umk: account.currentUmk, nodePk: nodePk)
            let sealed = FerryCrypto.encryptCommand(
                root: root, userId: account.userId, nodeId: node.id, commandId: id,
                plaintext: try JSONSerialization.data(withJSONObject: command.compactMapValues { $0 })
            )
            try await account.api.sendCommand(nodeId: node.id, id: id, sealed: sealed)
        } catch {
            try? inbox.updateStatus(rowKey: Receiver.commandRow(id), status: "failed:not_queued")
            throw error
        }
    }

    /// How long a node may wait before sending a reply; it refuses anything older (§6.6).
    static let commandTtlMs: Int64 = 5 * 60_000

    /// A time-ordered id, as the protocol asks for (§2).
    static func uuidv7(_ ms: Int64) -> String {
        var b = [UInt8](FerryCrypto.randomBytes(16))
        for i in 0..<6 { b[i] = UInt8(truncatingIfNeeded: ms >> (8 * (5 - i))) }
        b[6] = (b[6] & 0x0f) | 0x70
        b[8] = (b[8] & 0x3f) | 0x80
        let h = b.map { String(format: "%02x", $0) }.joined()
        let c = Array(h)
        return "\(String(c[0..<8]))-\(String(c[8..<12]))-\(String(c[12..<16]))-\(String(c[16..<20]))-\(String(c[20...]))"
    }

    /// Renames any device of this account (§5.4), keeping a node's key and who added it.
    public func setProfile(_ profile: DeviceProfile, for device: DeviceRecord) async throws {
        try await updateProfile(of: device.id) { current in
            var next = current ?? DeviceProfile(name: "")
            next.name = profile.name
            next.sims = profile.sims
            next.namedAt = Int64(Date().timeIntervalSince1970 * 1000)
            return next
        }
    }

    /// Rewrites a device's profile with `update` applied to the current one (nil: leave it). Keeps the
    /// version rising, and retries once if another receiver wrote in between.
    func updateProfile(of deviceId: String, _ update: (DeviceProfile?) -> DeviceProfile?) async throws {
        for attempt in 0..<2 {
            guard let device = try await account.api.devices().first(where: { $0.id == deviceId }),
                  let next = update(device.profile.flatMap(decryptProfile(of: deviceId)))
            else { return }
            let version = (device.profile?.version ?? 0) + 1
            let sealed = FerryCrypto.encryptProfile(
                umk: account.currentUmk, userId: account.userId, deviceId: deviceId, version: version,
                plaintext: try JSONEncoder().encode(next)
            )
            do {
                try await account.api.putProfile(deviceId, SealedProfile(version: version, keyId: sealed.keyId, nonce: sealed.nonce, ct: sealed.ct))
                return
            } catch let e as APIError where e.code == "profile_stale" && attempt == 0 {
                continue
            }
        }
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

    /// §5.6 flow A: this iPhone becomes the first receiver of an account a node created. It makes the
    /// UMK, trusts the node and sends it the account public key, with a MAC under the code's secret.
    public static func joinNode(_ link: NodeLink) async throws -> Account {
        let api = API(baseURL: URL(string: link.server)!, token: nil)
        let registered = try await api.registerDevice(
            RegisterDeviceRequest(userId: link.userId, code: link.code, roles: ["receiver"], appVersion: Ferry.appVersion)
        )
        let umk = FerryCrypto.generateUmk()
        let account = Account(server: link.server, userId: link.userId, deviceId: registered.deviceId, token: registered.token, umk: umk)
        // Saved before the server records its key ID, so the account never has a key no phone holds.
        do {
            try Keychain.save(account)
            try await account.api.setInitialKey(account.currentKeyId)
        } catch {
            try? await account.api.revoke(registered.deviceId)
            Keychain.delete()
            throw error
        }
        try await Sync(account: account).trustNode(link.deviceId, nodePk: link.nodePk)
        let acctPk = FerryCrypto.accountKeyPair(umk).publicKey
        let mac = FerryCrypto.pairingMac(secret: link.secret, userId: link.userId, deviceId: link.deviceId, publicKey: acctPk)
        try await account.api.sendPairingReply(code: link.code, PairingMessage(deviceId: link.deviceId, publicKey: acctPk.base64URL, mac: mac.base64URL))
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
