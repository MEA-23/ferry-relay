import Foundation

/// A decrypted, displayable event. Calls sharing a `callKey` collapse into one row (§4.3).
public struct Message: Identifiable, Hashable, Sendable {
    public let id: String
    public let eventId: String
    public let sourceDeviceId: String
    public let type: String
    public let threadKey: String
    public let title: String
    public let body: String
    public let simSlot: Int?
    public let occurredAt: Int64
    public let receivedAt: Int64
    public let unread: Bool
    public let event: FerryEvent?

    public var date: Date { Date(timeIntervalSince1970: TimeInterval(occurredAt) / 1000) }

    /// A text sent from the source phone (§4.2).
    public var isOutgoing: Bool { if case .sms(let e) = event { e.isOutgoing } else { false } }
}

public struct ThreadSummary: Identifiable, Hashable, Sendable {
    public var id: String { threadKey }
    public let threadKey: String
    public let last: Message
    public let unreadCount: Int
}

/// Cached device list with decrypted profiles, so the extension can name devices and SIMs offline.
public struct DeviceRecord: Identifiable, Hashable, Sendable {
    public let id: String
    public let roles: [String]
    public let platform: String
    public let appVersion: String?
    public let createdAt: Int64
    public let lastSeenAt: Int64
    public let profileVersion: Int64?
    public let profile: DeviceProfile?

    public init(id: String, roles: [String], platform: String, appVersion: String?, createdAt: Int64, lastSeenAt: Int64, profileVersion: Int64?, profile: DeviceProfile?) {
        self.id = id
        self.roles = roles
        self.platform = platform
        self.appVersion = appVersion
        self.createdAt = createdAt
        self.lastSeenAt = lastSeenAt
        self.profileVersion = profileVersion
        self.profile = profile
    }

    public var displayName: String {
        if let name = profile?.name, !name.isEmpty { return name }
        return platform == "ios" ? "iPhone" : "Android phone"
    }

    public func simLabel(_ slot: Int) -> String {
        profile?.sims[String(slot)].flatMap { $0.isEmpty ? nil : $0 } ?? "SIM \(slot + 1)"
    }
}

/// Shared store in the App Group container, written by both the app and the extension.
/// Encrypted with SQLCipher under a device-only Keychain key and kept out of backups (§9.1).
public struct Inbox: Sendable {
    public static let shared = Inbox(url: Ferry.sharedContainer.appendingPathComponent("inbox.sqlite"))

    /// Receivers keep seen ids at least as long as the server's retention (§3.4).
    static let seenRetentionMs: Int64 = 45 * 24 * 3600 * 1000

    let url: URL

    init(url: URL) {
        self.url = url
    }

    private func open() throws -> SQLite {
        guard let key = Keychain.inboxKey() else {
            throw SQLite.SQLiteError(description: "The inbox is locked until this iPhone is unlocked once after restarting.")
        }
        let db: SQLite
        do {
            db = try SQLite(path: url.path, key: key)
        } catch let error as SQLite.SQLiteError where error.isNotADatabase {
            // An inbox from before encryption, or under a key that's gone: start fresh. The next
            // sync fetches whatever the server still holds.
            removeFiles()
            db = try SQLite(path: url.path, key: key)
        }
        excludeFromBackup()
        try db.exec("""
            PRAGMA journal_mode = DELETE;
            CREATE TABLE IF NOT EXISTS seen (id TEXT PRIMARY KEY, at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS messages (
              row_key TEXT PRIMARY KEY, event_id TEXT NOT NULL, source_device_id TEXT NOT NULL,
              type TEXT NOT NULL, thread_key TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
              sim_slot INTEGER, occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL,
              unread INTEGER NOT NULL DEFAULT 1, payload BLOB, search_text TEXT);
            CREATE INDEX IF NOT EXISTS messages_thread ON messages (thread_key, occurred_at);
            CREATE TABLE IF NOT EXISTS devices (
              id TEXT PRIMARY KEY, roles TEXT NOT NULL, platform TEXT NOT NULL, app_version TEXT,
              created_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, profile_version INTEGER, profile BLOB);
            CREATE TABLE IF NOT EXISTS statuses (device_id TEXT PRIMARY KEY, occurred_at INTEGER NOT NULL, payload BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            """)
        try migrate(db)
        return db
    }

    /// Version 1 adds `search_text`, the normalized body that search matches against, and fills it in.
    private func migrate(_ db: SQLite) throws {
        guard (try db.query("PRAGMA user_version").first?.int("user_version") ?? 0) < 1 else { return }
        try db.transaction {
            // The app and the extension may both get here; whichever is second finds the column there.
            let columns = try db.query("PRAGMA table_info(messages)").compactMap { $0.text("name") }
            if !columns.contains("search_text") { try db.exec("ALTER TABLE messages ADD COLUMN search_text TEXT") }
            for r in try db.query("SELECT row_key, body FROM messages WHERE search_text IS NULL") {
                try db.run("UPDATE messages SET search_text = ? WHERE row_key = ?", [.text(TextTools.normalize(r.text("body") ?? "")), .text(r.text("row_key"))])
            }
            try db.exec("PRAGMA user_version = 1")
        }
    }

    // ── events ───────────────────────────────────────────────────────────────

    /// Marks `eventId` as seen. Returns false if it was seen before (push and pull both deliver, §7.3).
    public func markSeen(_ eventId: String, at: Int64) throws -> Bool {
        try open().run("INSERT OR IGNORE INTO seen (id, at) VALUES (?, ?)", [.text(eventId), .int(at)]) > 0
    }

    public func isSeen(_ eventId: String) throws -> Bool {
        try !open().query("SELECT 1 FROM seen WHERE id = ?", [.text(eventId)]).isEmpty
    }

    func insert(_ m: Message, mergeKey: String?, payload: Data) throws {
        let db = try open()
        try db.transaction {
            let rowKey = mergeKey ?? m.eventId
            let existing = try db.query("SELECT occurred_at FROM messages WHERE row_key = ?", [.text(rowKey)]).first
            if let existing, (existing.int("occurred_at") ?? 0) > m.occurredAt {
                return // a later state of this call already arrived
            }
            try db.run(
                """
                INSERT OR REPLACE INTO messages
                  (row_key, event_id, source_device_id, type, thread_key, title, body, sim_slot, occurred_at, received_at, unread, payload, search_text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [.text(rowKey), .text(m.eventId), .text(m.sourceDeviceId), .text(m.type), .text(m.threadKey), .text(m.title),
                 .text(m.body), .int(m.simSlot), .int(m.occurredAt), .int(m.receivedAt), .int(m.unread ? 1 : 0), .blob(payload),
                 .text(TextTools.normalize(m.body))]
            )
        }
    }

    public func threads() throws -> [ThreadSummary] {
        let rows = try open().query("""
            SELECT m.*, t.unread_count FROM messages m
            JOIN (SELECT thread_key, MAX(occurred_at) AS last_at, SUM(unread) AS unread_count FROM messages GROUP BY thread_key) t
              ON m.thread_key = t.thread_key AND m.occurred_at = t.last_at
            GROUP BY m.thread_key
            ORDER BY m.occurred_at DESC
            """)
        return rows.map { ThreadSummary(threadKey: $0.text("thread_key")!, last: message($0), unreadCount: Int($0.int("unread_count") ?? 0)) }
    }

    public func messages(thread: String) throws -> [Message] {
        try open().query("SELECT * FROM messages WHERE thread_key = ? ORDER BY occurred_at", [.text(thread)]).map(message)
    }

    /// Messages whose body contains `query`, however it's accented or cased, newest first.
    /// Conversation names are matched separately, from the thread list.
    public func search(_ query: String, limit: Int = 300) throws -> [Message] {
        let q = TextTools.normalize(query.trimmingCharacters(in: .whitespacesAndNewlines))
        guard !q.isEmpty else { return [] }
        let escaped = q.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "%", with: "\\%").replacingOccurrences(of: "_", with: "\\_")
        return try open().query(
            "SELECT * FROM messages WHERE search_text LIKE ? ESCAPE '\\' ORDER BY occurred_at DESC LIMIT ?",
            [.text("%\(escaped)%"), .int(Int64(limit))]
        ).map(message)
    }

    public func markRead(thread: String) throws {
        try open().run("UPDATE messages SET unread = 0 WHERE thread_key = ? AND unread = 1", [.text(thread)])
    }

    public func deleteThread(_ thread: String) throws {
        try open().run("DELETE FROM messages WHERE thread_key = ?", [.text(thread)])
    }

    public func unreadCount() throws -> Int {
        Int(try open().query("SELECT COUNT(*) AS n FROM messages WHERE unread = 1").first?.int("n") ?? 0)
    }

    private func message(_ r: SQLite.Row) -> Message {
        Message(
            id: r.text("row_key")!, eventId: r.text("event_id")!, sourceDeviceId: r.text("source_device_id")!,
            type: r.text("type")!, threadKey: r.text("thread_key")!, title: r.text("title")!, body: r.text("body")!,
            simSlot: r.int("sim_slot").map(Int.init), occurredAt: r.int("occurred_at")!, receivedAt: r.int("received_at")!,
            unread: r.int("unread") == 1, event: r.blob("payload").flatMap { try? FerryEvent.decode($0) }
        )
    }

    // ── statuses (§4.5) ──────────────────────────────────────────────────────

    func saveStatus(deviceId: String, occurredAt: Int64, payload: Data) throws {
        try open().run(
            "INSERT INTO statuses (device_id, occurred_at, payload) VALUES (?, ?, ?) ON CONFLICT (device_id) DO UPDATE SET occurred_at = excluded.occurred_at, payload = excluded.payload WHERE excluded.occurred_at > statuses.occurred_at",
            [.text(deviceId), .int(occurredAt), .blob(payload)]
        )
    }

    public func statuses() throws -> [String: StatusEvent] {
        var out = [String: StatusEvent]()
        for r in try open().query("SELECT * FROM statuses") {
            if let data = r.blob("payload"), case .status(let s) = try? FerryEvent.decode(data) { out[r.text("device_id")!] = s }
        }
        return out
    }

    // ── devices ──────────────────────────────────────────────────────────────

    public func saveDevices(_ devices: [DeviceRecord]) throws {
        let db = try open()
        let enc = JSONEncoder()
        try db.transaction {
            try db.run("DELETE FROM devices")
            for d in devices {
                try db.run(
                    "INSERT INTO devices (id, roles, platform, app_version, created_at, last_seen_at, profile_version, profile) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    [.text(d.id), .text(d.roles.joined(separator: ",")), .text(d.platform), .text(d.appVersion), .int(d.createdAt),
                     .int(d.lastSeenAt), d.profileVersion.map { .int($0) } ?? .null, (try? d.profile.map(enc.encode)).flatMap { $0 }.map { .blob($0) } ?? .null]
                )
            }
        }
    }

    public func devices() throws -> [DeviceRecord] {
        try open().query("SELECT * FROM devices ORDER BY created_at").map { r in
            DeviceRecord(
                id: r.text("id")!, roles: r.text("roles")!.split(separator: ",").map(String.init), platform: r.text("platform")!,
                appVersion: r.text("app_version"), createdAt: r.int("created_at")!, lastSeenAt: r.int("last_seen_at")!,
                profileVersion: r.int("profile_version"), profile: r.blob("profile").flatMap { try? JSONDecoder().decode(DeviceProfile.self, from: $0) }
            )
        }
    }

    public func device(_ id: String) -> DeviceRecord? {
        try? devices().first { $0.id == id }
    }

    // ── sync state ───────────────────────────────────────────────────────────

    public var watermark: String? {
        get { try? open().query("SELECT value FROM meta WHERE key = 'watermark'").first?.text("value") }
        nonmutating set {
            guard let newValue else { return }
            _ = try? open().run("INSERT OR REPLACE INTO meta (key, value) VALUES ('watermark', ?)", [.text(newValue)])
        }
    }

    public func pruneSeen(now: Int64) throws {
        try open().run("DELETE FROM seen WHERE at < ?", [.int(now - Self.seenRetentionMs)])
    }

    /// Removes every decrypted message, cached device and the inbox key. Used when leaving the
    /// account, when the server says this device was revoked, and on a fresh install.
    public func wipe() {
        removeFiles()
        Keychain.deleteInboxKey()
    }

    private func removeFiles() {
        for suffix in ["", "-journal", "-wal", "-shm"] {
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix))
        }
    }

    /// iCloud and computer backups skip the inbox; its key couldn't be restored anyway. The file
    /// stays readable after the first unlock so the extension can save messages on a locked phone.
    private func excludeFromBackup() {
        var file = url
        guard (try? file.resourceValues(forKeys: [.isExcludedFromBackupKey]))?.isExcludedFromBackup != true else { return }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? file.setResourceValues(values)
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    }
}
