import Foundation

/// Plaintext event payloads (docs/PROTOCOL.md §4). Only ever received encrypted.

public struct Sim: Codable, Hashable, Sendable {
    public let slot: Int
    public let subscriptionId: Int?
    public let carrier: String?
    public let number: String?
}

public struct SmsEvent: Codable, Hashable, Sendable {
    public let occurredAt: Int64
    public let sim: Sim?
    /// The other party: the sender of an incoming SMS, the recipient of an outgoing one (§4.2).
    public let from: String
    public let body: String
    public let contactName: String?
    public let truncated: Bool?
    /// "in" (default) or "out".
    public let direction: String?
    /// Copied from the source phone's SMS history rather than captured live.
    public let imported: Bool?

    public var isOutgoing: Bool { direction == "out" }
    /// Sent texts and imported history are filed quietly: never notified, never unread (§4.2).
    public var isQuiet: Bool { isOutgoing || imported == true }
}

public struct CallEvent: Codable, Hashable, Sendable {
    public let occurredAt: Int64
    public let sim: Sim?
    public let from: String?
    /// ringing · missed · rejected · answered · ended
    public let state: String
    public let callKey: String?
    public let durationSec: Int?
    public let contactName: String?
}

public struct NotificationEvent: Codable, Hashable, Sendable {
    public let occurredAt: Int64
    public let packageName: String
    public let appName: String
    public let title: String?
    public let text: String?
    public let category: String?
    public let key: String?
    public let truncated: Bool?

    enum CodingKeys: String, CodingKey {
        case occurredAt, appName, title, text, category, key, truncated
        case packageName = "package"
    }
}

public struct SimStatus: Codable, Hashable, Sendable {
    public let slot: Int
    public let subscriptionId: Int?
    public let carrier: String?
    public let active: Bool
}

public struct StatusEvent: Codable, Hashable, Sendable {
    public let occurredAt: Int64
    public let battery: Int?
    public let charging: Bool?
    public let sims: [SimStatus]?
    public let permissions: [String: Bool]?
    public let appVersion: String?
}

public enum FerryEvent: Hashable, Sendable {
    case sms(SmsEvent)
    case call(CallEvent)
    case notification(NotificationEvent)
    case status(StatusEvent)
    /// Unknown `t` (§10): shown as "Unsupported event" at most.
    case unsupported(type: String, occurredAt: Int64)

    public var type: String {
        switch self {
        case .sms: "sms"
        case .call: "call"
        case .notification: "notification"
        case .status: "status"
        case .unsupported(let t, _): t
        }
    }

    public var occurredAt: Int64 {
        switch self {
        case .sms(let e): e.occurredAt
        case .call(let e): e.occurredAt
        case .notification(let e): e.occurredAt
        case .status(let e): e.occurredAt
        case .unsupported(_, let at): at
        }
    }

    public var sim: Sim? {
        switch self {
        case .sms(let e): e.sim
        case .call(let e): e.sim
        default: nil
        }
    }

    public static func decode(_ data: Data) throws -> FerryEvent {
        struct Head: Decodable { let t: String; let occurredAt: Int64? }
        let decoder = JSONDecoder()
        let head = try decoder.decode(Head.self, from: data)
        switch head.t {
        case "sms": return .sms(try decoder.decode(SmsEvent.self, from: data))
        case "call": return .call(try decoder.decode(CallEvent.self, from: data))
        case "notification": return .notification(try decoder.decode(NotificationEvent.self, from: data))
        case "status": return .status(try decoder.decode(StatusEvent.self, from: data))
        default: return .unsupported(type: head.t, occurredAt: head.occurredAt ?? 0)
        }
    }
}

/// Device profile plaintext (§5.4).
public struct DeviceProfile: Codable, Hashable, Sendable {
    public var name: String
    public var sims: [String: String]

    public init(name: String, sims: [String: String] = [:]) {
        self.name = name
        self.sims = sims
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        sims = try c.decodeIfPresent([String: String].self, forKey: .sims) ?? [:]
    }
}
