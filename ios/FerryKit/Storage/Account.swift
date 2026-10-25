import Foundation
import Security

public enum Ferry {
    public static let appGroup = "group.tech.thedevlab.ferry"
    public static let appId = "tech.thedevlab.ferry"
    public static let defaultServer = "https://ferry.thedevlab.cloud"

    public static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    /// HTTPS everywhere (§2), except servers on the local network: a dev server on a Mac,
    /// reached as localhost, `name.local` or a private IPv4 address.
    public static func isAllowedServer(_ url: URL) -> Bool {
        guard let host = url.host()?.lowercased() else { return false }
        if url.scheme == "https" { return true }
        guard url.scheme == "http" else { return false }
        if host == "localhost" || host == "::1" || host.hasSuffix(".local") { return true }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        let octets = parts.compactMap { UInt8($0) }
        guard parts.count == 4, octets.count == 4 else { return false }
        return octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && (16...31).contains(octets[1]))
    }

    /// Turns what someone typed ("ferry.example.com", "ferry-dev.local:3999/") into a usable server URL,
    /// or nil if it isn't one Ferry may use.
    public static func normalizeServer(_ input: String) -> URL? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        guard !s.isEmpty, !s.contains(" ") else { return nil }
        if !s.contains("://") {
            let host = s.split(separator: ":").first.map(String.init)?.lowercased() ?? ""
            let local = host == "localhost" || host.hasSuffix(".local") || URL(string: "http://\(host)").map(isAllowedServer) == true
            s = (local ? "http://" : "https://") + s
        }
        guard let url = URL(string: s), url.host() != nil, isAllowedServer(url) else { return nil }
        return url
    }

    /// App Group container shared by the app and the Notification Service Extension.
    public static var sharedContainer: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) { return url }
        // Unsigned simulator builds have no app group; fall back to the process's own sandbox.
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

/// Credentials and keys for this device (§3.1, §5.1). Lives only in the shared Keychain group.
public struct Account: Codable, Sendable, Equatable {
    public var server: String
    public var userId: String
    public var deviceId: String
    public var token: String
    public var currentKeyId: String
    /// keyId → b64u UMK. Old keys stay so events from before a rotation remain readable (§5.5).
    public var keys: [String: String]

    public init(server: String, userId: String, deviceId: String, token: String, umk: Data) {
        let keyId = FerryCrypto.keyId(umk)
        self.server = server
        self.userId = userId
        self.deviceId = deviceId
        self.token = token
        self.currentKeyId = keyId
        self.keys = [keyId: umk.base64URL]
    }

    public var umks: [String: Data] { keys.compactMapValues { Data(base64URL: $0) } }

    public var currentUmk: Data { umks[currentKeyId]! }

    public var api: API { API(baseURL: URL(string: server)!, token: token) }
}

public enum Keychain {
    private static let service = Ferry.appId
    private static let item = "account"
    private static let inboxItem = "inbox-key"

    public static func load() -> Account? {
        guard case .found(let data) = read(item) else { return nil }
        return try? JSONDecoder().decode(Account.self, from: data)
    }

    public static func save(_ account: Account) throws {
        let data = try JSONEncoder().encode(account)
        delete()
        let status = add(data, item)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
    }

    public static func delete() { remove(item) }

    /// The key for the encrypted inbox (§9.1): 32-byte key ‖ 16-byte salt, made on first use.
    /// Like the account it is `ThisDeviceOnly`, so it never leaves this iPhone in a backup.
    /// Returns nil while the Keychain is unavailable (before the first unlock after boot).
    public static func inboxKey() -> Data? {
        switch read(inboxItem) {
        case .found(let key): return key
        case .unavailable: return nil
        case .missing:
            var key = Data(count: 48)
            guard key.withUnsafeMutableBytes({ SecRandomCopyBytes(kSecRandomDefault, 48, $0.baseAddress!) }) == errSecSuccess else { return nil }
            switch add(key, inboxItem) {
            case errSecSuccess: return key
            // The app and the notification extension raced; use the one that won.
            case errSecDuplicateItem: if case .found(let existing) = read(inboxItem) { return existing } else { return nil }
            default: return nil
            }
        }
    }

    public static func deleteInboxKey() { remove(inboxItem) }

    // ── generic password items in the shared group ───────────────────────────

    private enum ReadResult { case found(Data), missing, unavailable }

    private static func read(_ item: String) -> ReadResult {
        var query = base(item)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        var status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecMissingEntitlement {
            query.removeValue(forKey: kSecAttrAccessGroup as String)
            status = SecItemCopyMatching(query as CFDictionary, &out)
        }
        switch status {
        case errSecSuccess: return (out as? Data).map { .found($0) } ?? .missing
        case errSecItemNotFound: return .missing
        default: return .unavailable
        }
    }

    private static func add(_ data: Data, _ item: String) -> OSStatus {
        var attrs = base(item)
        attrs[kSecValueData as String] = data
        attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        var status = SecItemAdd(attrs as CFDictionary, nil)
        if status == errSecMissingEntitlement {
            attrs.removeValue(forKey: kSecAttrAccessGroup as String)
            status = SecItemAdd(attrs as CFDictionary, nil)
        }
        return status
    }

    private static func remove(_ item: String) {
        var query = base(item)
        SecItemDelete(query as CFDictionary)
        query.removeValue(forKey: kSecAttrAccessGroup as String)
        SecItemDelete(query as CFDictionary)
    }

    private static func base(_ item: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: item,
            kSecAttrAccessGroup as String: Ferry.appGroup,
        ]
    }
}

/// `ferry://pair?v=1&s=<server>&u=<userId>&c=<code>&k=<UMK>` (§5.3). Contains the key — handle with care.
public struct PairingLink: Sendable {
    public let server: String
    public let userId: String
    public let code: String
    public let umk: Data

    public init(server: String, userId: String, code: String, umk: Data) {
        self.server = server
        self.userId = userId
        self.code = code
        self.umk = umk
    }

    public var uri: String {
        var c = URLComponents()
        c.scheme = "ferry"
        c.host = "pair"
        c.queryItems = [
            .init(name: "v", value: "1"),
            .init(name: "s", value: server),
            .init(name: "u", value: userId),
            .init(name: "c", value: code),
            .init(name: "k", value: umk.base64URL),
        ]
        // URLComponents leaves ':' and '/' unescaped in queries; the spec asks for a URL-encoded server.
        c.percentEncodedQueryItems = c.queryItems?.map {
            .init(name: $0.name, value: $0.value?.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(.init(charactersIn: "-._~"))))
        }
        return c.string!
    }

    public static func parse(_ text: String) -> PairingLink? {
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme == "ferry", c.host == "pair" else { return nil }
        func q(_ name: String) -> String? { c.queryItems?.first { $0.name == name }?.value }
        guard q("v") == "1", let s = q("s"), let u = q("u"), let code = q("c"),
              let k = q("k"), let umk = Data(base64URL: k), umk.count == FerryCrypto.umkBytes,
              let url = URL(string: s), Ferry.isAllowedServer(url)
        else { return nil }
        return PairingLink(server: s, userId: u, code: code, umk: umk)
    }
}

/// `ferry://node?v=2&s=…&u=…&d=<nodeDeviceId>&c=…&p=<nodePk>&x=<secret>` (§5.6 flow A): a node waiting
/// for its first receiver. It holds no key; `secret` proves the receiver's answer.
public struct NodeLink: Sendable {
    public let server: String
    public let userId: String
    public let deviceId: String
    public let code: String
    public let nodePk: Data
    public let secret: Data
}

/// `ferry://intro?v=2&s=…&u=…&c=…&a=<acctPk>&x=<secret>` (§5.6 flow B): adds the Android phone that
/// scans it as a node. Whoever scans it first joins the account, so only show it to your own phones.
public struct IntroLink: Sendable {
    public let server: String
    public let userId: String
    public let code: String
    public let acctPk: Data
    public let secret: Data

    public init(server: String, userId: String, code: String, acctPk: Data, secret: Data) {
        self.server = server
        self.userId = userId
        self.code = code
        self.acctPk = acctPk
        self.secret = secret
    }

    public var uri: String {
        v2URI("intro", server: server, userId: userId, code: code, extra: [("a", acctPk.base64URL), ("x", secret.base64URL)])
    }
}

/// Any Ferry pairing code this iPhone might scan, paste or open.
public enum ScannedCode: Sendable {
    /// §5.3: another receiver's code, with the UMK.
    case receiver(PairingLink)
    /// §5.6 flow A: a new node waiting for its first receiver.
    case node(NodeLink)
    /// §5.6 flow B: for Android phones that relay, not for this iPhone.
    case intro(IntroLink)

    public var server: String {
        switch self {
        case .receiver(let l): l.server
        case .node(let l): l.server
        case .intro(let l): l.server
        }
    }

    public var code: String {
        switch self {
        case .receiver(let l): l.code
        case .node(let l): l.code
        case .intro(let l): l.code
        }
    }

    public static func parse(_ text: String) -> ScannedCode? {
        if let link = PairingLink.parse(text) { return .receiver(link) }
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)), c.scheme == "ferry" else { return nil }
        func q(_ name: String) -> String? { c.queryItems?.first { $0.name == name }?.value }
        func key(_ name: String) -> Data? { q(name).flatMap { Data(base64URL: $0) }.flatMap { $0.count == FerryCrypto.keyBytes ? $0 : nil } }
        guard q("v") == "2", let s = q("s"), let url = URL(string: s), Ferry.isAllowedServer(url), let u = q("u"), let code = q("c"), let x = key("x")
        else { return nil }
        switch c.host {
        case "node":
            guard let d = q("d"), let p = key("p") else { return nil }
            return .node(NodeLink(server: s, userId: u, deviceId: d, code: code, nodePk: p, secret: x))
        case "intro":
            guard let a = key("a") else { return nil }
            return .intro(IntroLink(server: s, userId: u, code: code, acctPk: a, secret: x))
        default:
            return nil
        }
    }
}

private func v2URI(_ kind: String, server: String, userId: String, code: String, extra: [(String, String)]) -> String {
    var c = URLComponents()
    c.scheme = "ferry"
    c.host = kind
    let items = [("v", "2"), ("s", server), ("u", userId), ("c", code)] + extra
    // Encoded by hand, as in PairingLink: URLComponents leaves ':' and '/' alone in queries.
    c.percentEncodedQueryItems = items.map {
        .init(name: $0.0, value: $0.1.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(.init(charactersIn: "-._~"))))
    }
    return c.string!
}
