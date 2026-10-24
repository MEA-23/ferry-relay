import Foundation

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
    }

    public let account: Account
    public let inbox: Inbox

    public init(account: Account, inbox: Inbox = .shared) {
        self.account = account
        self.inbox = inbox
    }

    public func ingest(_ e: StoredEvent) throws -> Outcome {
        if try inbox.isSeen(e.id) { return .duplicate }
        let sealed = FerryCrypto.Sealed(keyId: e.keyId, nonce: e.nonce, ct: e.ct)
        let plain = try FerryCrypto.decryptEvent(umks: account.umks, userId: account.userId, sourceDeviceId: e.sourceDeviceId, eventId: e.id, sealed: sealed)
        let event = try FerryEvent.decode(plain)

        // Mark seen only after a successful decrypt, so a transient failure can be retried by the next pull.
        guard try inbox.markSeen(e.id, at: e.receivedAt) else { return .duplicate }

        if case .status(let s) = event {
            try inbox.saveStatus(deviceId: e.sourceDeviceId, occurredAt: s.occurredAt, payload: plain)
            return .quiet
        }
        guard let summary = Summary(event: event, source: inbox.device(e.sourceDeviceId)) else { return .quiet }

        let quiet = if case .sms(let s) = event { s.isQuiet } else { false }
        let message = Message(
            id: e.id, eventId: e.id, sourceDeviceId: e.sourceDeviceId, type: event.type,
            threadKey: Self.threadKey(event, source: e.sourceDeviceId), title: summary.title, body: summary.body,
            simSlot: event.sim?.slot, occurredAt: event.occurredAt, receivedAt: e.receivedAt, unread: !quiet, event: event
        )
        try inbox.insert(message, mergeKey: Self.mergeKey(event, source: e.sourceDeviceId), payload: plain)
        return quiet ? .filed(message) : .new(message, summary)
    }

    /// One thread per source device and sender (§7.3); calls and texts from a number share it.
    static func threadKey(_ event: FerryEvent, source: String) -> String {
        switch event {
        case .sms(let e): "p:\(source):\(normalize(e.from))"
        case .call(let e): "p:\(source):\(normalize(e.from ?? "unknown"))"
        case .notification(let e): "n:\(source):\(e.packageName)"
        case .status, .unsupported: "x:\(source)"
        }
    }

    static func mergeKey(_ event: FerryEvent, source: String) -> String? {
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
        case .unsupported:
            title = "New type of message"
            body = "Update Ferry to see this."
        case .status:
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
