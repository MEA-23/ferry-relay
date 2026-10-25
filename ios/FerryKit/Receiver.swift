import Foundation

/// An event from a node this receiver doesn't trust yet: keep it and retry after the next device refresh (§3.7).
public struct UnknownNodeError: Error {
    public let deviceId: String
}

/// Decrypts stored events and files them into the inbox. Shared by the app's sync and the
/// Notification Service Extension so both handle an event identically.
public struct Receiver: Sendable {
    public enum Outcome: Sendable {
        /// A new event worth showing, with its notification text.
        case new(Message, Summary)
        /// Filed in the inbox as already read, without a notification: sent texts and imported history (§4.2).
        case filed(Message)
        case duplicate
        /// A status heartbeat or unsupported type: recorded, but not shown as a notification.
        case quiet
        /// A node's new name and SIM labels (§4.6), for the app to write into its profile.
        case profile(deviceId: String, ProfileEvent)
        /// A trusted node added another one (§4.7). Saved for the app to check and accept.
        case introduced(deviceId: String, Summary)
    }

    public let account: Account
    public let inbox: Inbox

    public init(account: Account, inbox: Inbox = .shared) {
        self.account = account
        self.inbox = inbox
    }

    /// Throws `UnknownNodeError` for an event from a node that isn't trusted yet.
    public func ingest(_ e: StoredEvent) throws -> Outcome {
        if try inbox.isSeen(e.id) { return .duplicate }
        let plain = try decrypt(e)
        let event = try FerryEvent.decode(plain)

        // Mark seen only after a successful decrypt, so a transient failure can be retried by the next pull.
        guard try inbox.markSeen(e.id, at: e.receivedAt) else { return .duplicate }
        if case .profile(let p) = event { return .profile(deviceId: e.sourceDeviceId, p) }
        if case .smsStatus(let s) = event {
            try inbox.updateStatus(rowKey: Self.commandRow(s.commandId), status: s.reason.map { "\(s.status):\($0)" } ?? s.status)
            return .quiet
        }
        if case .introduce(let i) = event {
            try inbox.saveIntroduction(.init(eventId: e.id, by: e.sourceDeviceId, deviceId: i.deviceId, nodePk: i.nodePk))
            return .introduced(deviceId: i.deviceId, Summary(event: event, source: inbox.device(e.sourceDeviceId))!)
        }

        if case .status(let s) = event {
            try inbox.saveStatus(deviceId: e.sourceDeviceId, occurredAt: s.occurredAt, payload: plain)
            return .quiet
        }
        guard let summary = Summary(event: event, source: inbox.device(e.sourceDeviceId)) else { return .quiet }

        let quiet = if case .sms(let s) = event { s.isQuiet } else { false }
        // A text sent through a node (§6.6) starts out as sending; its sms_status events move it on.
        let sent = if case .sms(let s) = event { s.commandId != nil } else { false }
        let message = Message(
            id: e.id, eventId: e.id, sourceDeviceId: e.sourceDeviceId, type: event.type,
            threadKey: Self.threadKey(event, source: e.sourceDeviceId), title: summary.title, body: summary.body,
            simSlot: event.sim?.slot, occurredAt: event.occurredAt, receivedAt: e.receivedAt, unread: !quiet, event: event,
            status: sent ? "sending" : nil
        )
        try inbox.insert(message, mergeKey: Self.mergeKey(event, source: e.sourceDeviceId), payload: plain)
        return quiet ? .filed(message) : .new(message, summary)
    }

    /// UMK events as in v1; a node's events with the root shared with that node (§3.7).
    private func decrypt(_ e: StoredEvent) throws -> Data {
        let sealed = FerryCrypto.Sealed(keyId: e.keyId, nonce: e.nonce, ct: e.ct)
        let umks = account.umks
        if umks[e.keyId] != nil {
            return try FerryCrypto.decryptEvent(umks: umks, userId: account.userId, sourceDeviceId: e.sourceDeviceId, eventId: e.id, sealed: sealed)
        }
        let root = try nodeRoot(e, umks: Array(umks.values))
        return try FerryCrypto.decryptNodeEvent(root: root, userId: account.userId, sourceDeviceId: e.sourceDeviceId, eventId: e.id, sealed: sealed)
    }

    /// The node's public key comes only from its profile, which the server can't forge (§3.7).
    private func nodeRoot(_ e: StoredEvent, umks: [Data]) throws -> Data {
        guard let device = inbox.device(e.sourceDeviceId), !device.roles.contains("receiver"),
              let encoded = device.profile?.nodePk, let nodePk = Data(base64URL: encoded)
        else { throw UnknownNodeError(deviceId: e.sourceDeviceId) }
        if inbox.removedNodeKeys.contains(encoded) { throw FerryCrypto.CryptoError.unknownKeyId(e.keyId) }
        for umk in umks {
            let root = try FerryCrypto.nodeRootForReceiver(umk: umk, nodePk: nodePk)
            if FerryCrypto.nodeKeyId(root) == e.keyId { return root }
        }
        // A key this profile doesn't explain, e.g. the node re-paired and its new profile isn't here yet.
        throw UnknownNodeError(deviceId: e.sourceDeviceId)
    }

    /// One thread per source device and sender (§7.3); calls and texts from a number share it.
    static func threadKey(_ event: FerryEvent, source: String) -> String {
        switch event {
        case .sms(let e): smsThread(e.from, source: source)
        case .call(let e): "p:\(source):\(normalize(e.from ?? "unknown"))"
        case .notification(let e): "n:\(source):\(e.packageName)"
        case .status, .profile, .introduce, .smsStatus, .unsupported: "x:\(source)"
        }
    }

    /// The thread of texts with `number` through `source`, where a new message to it goes too.
    public static func smsThread(_ number: String, source: String) -> String { "p:\(source):\(normalize(number))" }

    /// The row of a text sent through a node (§6.6): the one queued here, then the node's copy.
    static func commandRow(_ commandId: String) -> String { "cmd:\(commandId)" }

    static func mergeKey(_ event: FerryEvent, source: String) -> String? {
        if case .sms(let e) = event, let id = e.commandId { return commandRow(id) }
        if case .call(let e) = event, let key = e.callKey { return "call:\(source):\(key)" }
        return nil
    }

    private static func normalize(_ number: String) -> String {
        number.filter { !" -().".contains($0) }.lowercased()
    }
}

/// Title and body for the inbox and notifications, matching the Android receiver's wording.
public struct Summary: Sendable, Hashable {
    public let title: String
    public let body: String
    /// e.g. "Work SIM · Pixel 8"
    public let subtitle: String

    public init?(event: FerryEvent, source: DeviceRecord?) {
        let deviceName = source?.displayName ?? "Android phone"
        if let slot = event.sim?.slot {
            subtitle = "\(source?.simLabel(slot) ?? "SIM \(slot + 1)") · \(deviceName)"
        } else {
            subtitle = deviceName
        }

        switch event {
        case .sms(let e):
            title = e.contactName ?? e.from
            body = e.body
        case .call(let e):
            title = e.contactName ?? e.from ?? "Unknown number"
            body = Self.callText(e)
        case .notification(let e):
            title = [e.appName, e.title].compactMap { $0 }.joined(separator: " · ")
            body = e.text ?? ""
        case .introduce:
            title = "\(deviceName) added a phone"
            body = "A new phone now relays to your account. If that wasn't you, remove it."
        case .unsupported:
            title = "New type of message"
            body = "Update Ferry to see this."
        case .status, .profile, .smsStatus:
            return nil
        }
    }

    public static func callText(_ e: CallEvent) -> String {
        let d = e.durationSec ?? 0
        return switch e.state {
        case "ringing": "Incoming call"
        case "missed": "Missed call"
        case "rejected": "Declined call"
        default: String(format: "Call · %d:%02d", d / 60, d % 60)
        }
    }
}
