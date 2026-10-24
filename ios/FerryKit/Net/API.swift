import Foundation

/// Server error (§8). `code` is the protocol's snake_case error code.
public struct APIError: LocalizedError, Sendable {
    public let status: Int
    public let code: String
    public let message: String

    /// Plain-language text. The server's own `message` is written for developers, so it never reaches the screen.
    public var errorDescription: String? {
        switch code {
        case "pairing_expired": "This pairing code was already used or has expired. Create a new one."
        case "unauthorized": "This device was removed from your Ferry account."
        case "key_rotated": "Your account's encryption key changed. Pair this device again."
        case "profile_stale": "These names were just changed on another device. Try again."
        case "not_found": "That's no longer on your account. Refresh and try again."
        case "forbidden" where message.contains("invite"): "The invite code is wrong."
        case "forbidden" where message.contains("Pairing code"):
            "This code is for a phone that relays messages. On your Android phone, choose “Read messages”, then show a new code."
        case "forbidden": "This device isn't set up to do that."
        case "invalid_request" where message.contains("sources"): "Only Android phones can relay messages."
        default: "Something went wrong on the server. Try again in a moment."
        }
    }
}

public struct NewDevice: Codable, Sendable {
    public var roles: [String]
    public var platform = "ios"
    public var appVersion: String?
}

public struct CreateAccountRequest: Codable, Sendable {
    public var device: NewDevice
    public var keyId: String
    public var invite: String?
}

public struct CreatedAccount: Codable, Sendable {
    public let userId: String
    public let deviceId: String
    public let token: String
}

public struct PairingCode: Codable, Sendable {
    public let code: String
    public let expiresAt: Int64
}

public struct RegisterDeviceRequest: Codable, Sendable {
    public var userId: String
    public var code: String
    public var roles: [String]
    public var platform = "ios"
    public var appVersion: String?
}

public struct RegisteredDevice: Codable, Sendable {
    public let deviceId: String
    public let token: String
    public let keyId: String
}

public struct SealedProfile: Codable, Sendable {
    public let version: Int64
    public let keyId: String
    public let nonce: String
    public let ct: String

    public init(version: Int64, keyId: String, nonce: String, ct: String) {
        self.version = version
        self.keyId = keyId
        self.nonce = nonce
        self.ct = ct
    }
}

public struct DeviceDTO: Codable, Sendable, Identifiable {
    public let id: String
    public let roles: [String]
    public let platform: String
    public let appVersion: String?
    public let createdAt: Int64
    public let lastSeenAt: Int64
    public let profile: SealedProfile?
}

public struct StoredEvent: Codable, Sendable {
    public let id: String
    public let keyId: String
    public let nonce: String
    public let ct: String
    public let kind: String
    public let seq: String
    public let sourceDeviceId: String
    public let receivedAt: Int64
}

public struct EventPage: Codable, Sendable {
    public let events: [StoredEvent]
    public let next: String
    public let hasMore: Bool
}

public struct PushRegistration: Codable, Sendable {
    public var provider = "apns"
    public var token: String
    public var appId: String
    public var environment: String

    public init(token: String, appId: String, environment: String) {
        self.token = token
        self.appId = appId
        self.environment = environment
    }
}

public struct API: Sendable {
    public let baseURL: URL
    public let token: String?

    public init(baseURL: URL, token: String?) {
        self.baseURL = baseURL
        self.token = token
    }

    public func createAccount(_ req: CreateAccountRequest) async throws -> CreatedAccount {
        try await send("POST", "/v1/accounts", body: req, auth: false)
    }

    public func requestPairing(roles: [String]) async throws -> PairingCode {
        try await send("POST", "/v1/pairing", body: ["roles": roles])
    }

    public func registerDevice(_ req: RegisterDeviceRequest) async throws -> RegisteredDevice {
        try await send("POST", "/v1/devices", body: req, auth: false)
    }

    public func devices() async throws -> [DeviceDTO] {
        struct List: Decodable { let devices: [DeviceDTO] }
        let list: List = try await send("GET", "/v1/devices")
        return list.devices
    }

    public func revoke(_ id: String) async throws {
        try await sendNoContent("DELETE", "/v1/devices/\(id)")
    }

    public func putProfile(_ id: String, _ profile: SealedProfile) async throws {
        try await sendNoContent("PUT", "/v1/devices/\(id)/profile", body: profile)
    }

    public func putPush(_ id: String, _ reg: PushRegistration) async throws {
        try await sendNoContent("PUT", "/v1/devices/\(id)/push", body: reg)
    }

    public func pull(after: String?, limit: Int = 100) async throws -> EventPage {
        try await send("GET", "/v1/events?limit=\(limit)" + (after.map { "&after=\($0)" } ?? ""))
    }

    public func event(_ id: String) async throws -> StoredEvent {
        try await send("GET", "/v1/events/\(id)")
    }

    public func ack(upTo: String) async throws {
        try await sendNoContent("POST", "/v1/events/ack", body: ["upTo": upTo])
    }

    // ── transport ────────────────────────────────────────────────────────────

    private struct ErrorBody: Decodable {
        struct Detail: Decodable { let code: String; let message: String? }
        let error: Detail
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    private func send<T: Decodable>(_ method: String, _ path: String, body: (some Encodable)? = Optional<Int>.none, auth: Bool = true) async throws -> T {
        let data = try await perform(method, path, body: body, auth: auth)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func sendNoContent(_ method: String, _ path: String, body: (some Encodable)? = Optional<Int>.none) async throws {
        _ = try await perform(method, path, body: body, auth: true)
    }

    /// Performs the request and returns the response body; throws `APIError` for non-2xx.
    private func perform(_ method: String, _ path: String, body: (some Encodable)?, auth: Bool) async throws -> Data {
        guard let url = URL(string: baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.httpMethod = method
        if let body {
            req.httpBody = try JSONEncoder().encode(body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if auth, let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }

        let (data, response) = try await Self.session.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let detail = try? JSONDecoder().decode(ErrorBody.self, from: data).error
            throw APIError(status: status, code: detail?.code ?? "http_\(status)", message: detail?.message ?? "")
        }
        return data
    }
}
