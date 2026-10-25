import Foundation

/// A mute rule or a decision about one, on this iPhone only: a muted sender (its normalized number)
/// or app (its package), a muted word, a sender kept as not marketing, or a suggestion turned down.
/// `kind` is one of the `Filters` kinds.
public struct Mute: Identifiable, Hashable, Sendable {
    public let kind: String
    public let value: String
    public let label: String
    public let at: Int64

    public var id: String { "\(kind):\(value)" }
    public var target: Filters.Target { Filters.Target(kind: kind, value: value) }
}

/// Mute rules, archiving, and learning from mutes which texts look like marketing (see `Filters`).
/// A muted message is still filed; it only arrives without sound or a banner. The Android
/// counterpart is `Muting`.
extension Inbox {
    public func mutes() throws -> [Mute] {
        try open().query("SELECT * FROM mutes ORDER BY at DESC").map {
            Mute(kind: $0.text("kind")!, value: $0.text("value")!, label: $0.text("label")!, at: $0.int("at")!)
        }
    }

    /// Texts that look like marketing get a "Mute?" suggestion in the inbox (see `Filters.suggest`). Off by default.
    public var suggestMuting: Bool {
        get { (try? open().query("SELECT value FROM meta WHERE key = 'suggestMuting'").first?.text("value")) == "1" }
        nonmutating set { _ = try? open().run("INSERT OR REPLACE INTO meta (key, value) VALUES ('suggestMuting', ?)", [.text(newValue ? "1" : "0")]) }
    }

    /// Texts that score as marketing arrive muted, without asking (see `Filters.auto`). Off by default.
    public var autoMute: Bool {
        get { (try? open().query("SELECT value FROM meta WHERE key = 'autoMute'").first?.text("value")) == "1" }
        nonmutating set { _ = try? open().run("INSERT OR REPLACE INTO meta (key, value) VALUES ('autoMute', ?)", [.text(newValue ? "1" : "0")]) }
    }

    /// How a new incoming `event` on `threadKey` arrives. A muted sender or app always wins.
    /// Contacts, conversations you've replied to, senders you kept, and one-time codes are never
    /// muted by a word or a guess.
    func judge(_ event: FerryEvent, threadKey: String) throws -> (muted: Bool, suspect: Bool) {
        guard let target = Filters.target(threadKey) else { return (false, false) }
        let mutes = try mutes()
        func has(_ kind: String) -> Bool { mutes.contains { $0.kind == kind && $0.value == target.value } }
        switch event {
        case .notification: return (target.kind == Filters.app && has(Filters.app), false)
        case .call: return (has(Filters.sender), false)
        case .sms(let e):
            if has(Filters.sender) { return (true, false) }
            if try e.contactName != nil || has(Filters.keep) || Filters.looksLikeCode(e.body) || hasOutgoing(threadKey) { return (false, false) }
            if Filters.matchesWord(e.body, words: mutes.filter { $0.kind == Filters.word }.map(\.value)) { return (true, false) }
            let score = Filters.score(sender: target.value, body: e.body, model: try model(Filters.tokens(e.body)))
            let muted = autoMute && score >= Filters.auto
            return (muted, !muted && score >= Filters.suggest)
        default: return (false, false)
        }
    }

    /// Mutes a conversation's sender or app everywhere it appears. `label` is the name to list it under.
    public func mute(threadKey: String, label: String) throws {
        guard let target = Filters.target(threadKey) else { return }
        let db = try open()
        try db.transaction {
            try db.run("INSERT OR REPLACE INTO mutes (kind, value, label, at) VALUES (?, ?, ?, ?)", [.text(target.kind), .text(target.value), .text(label), .int(Self.now)])
            try db.run("DELETE FROM mutes WHERE kind = ? AND value = ?", [.text(Filters.keep), .text(target.value)])
            try setMuted(db, target, true)
        }
        if target.kind == Filters.sender { try relearn() }
    }

    public func unmute(_ target: Filters.Target) throws {
        let db = try open()
        try db.transaction {
            try db.run("DELETE FROM mutes WHERE kind = ? AND value = ?", [.text(target.kind), .text(target.value)])
            try setMuted(db, target, false)
        }
        if target.kind == Filters.sender { try relearn() }
    }

    /// The user says this sender isn't marketing: no more suggestions, and it teaches the model.
    public func keep(threadKey: String, label: String) throws {
        guard let target = Filters.target(threadKey) else { return }
        try open().run("INSERT OR REPLACE INTO mutes (kind, value, label, at) VALUES (?, ?, ?, ?)", [.text(Filters.keep), .text(target.value), .text(label), .int(Self.now)])
        try relearn()
    }

    public func addWord(_ word: String) throws {
        let label = word.trimmingCharacters(in: .whitespacesAndNewlines)
        let value = TextTools.normalize(label)
        guard !value.isEmpty else { return }
        let db = try open()
        try db.run("INSERT OR REPLACE INTO mutes (kind, value, label, at) VALUES (?, ?, ?, ?)", [.text(Filters.word), .text(value), .text(label), .int(Self.now)])
        try db.run("DELETE FROM mutes WHERE kind = ? AND value = ?", [.text(Filters.skip), .text(value)])
    }

    public func removeWord(_ value: String) throws {
        try open().run("DELETE FROM mutes WHERE kind = ? AND value = ?", [.text(Filters.word), .text(value)])
    }

    public func skipWord(_ value: String) throws {
        try open().run("INSERT OR REPLACE INTO mutes (kind, value, label, at) VALUES (?, ?, ?, ?)", [.text(Filters.skip), .text(value), .text(value), .int(Self.now)])
    }

    /// Words to offer as rules, learned from muted senders.
    public func suggestions() throws -> [String] {
        let exclude = Set(try mutes().filter { $0.kind == Filters.word || $0.kind == Filters.skip }.map(\.value))
        let rows = try open().query("SELECT * FROM filter_words")
        return Filters.suggestions(Self.model(rows), excluding: exclude)
    }

    // ── archive ──────────────────────────────────────────────────────────────

    public func archive(_ thread: String, _ archived: Bool) throws {
        if archived {
            try open().run("INSERT OR REPLACE INTO archived (thread_key, at) VALUES (?, ?)", [.text(thread), .int(Self.now)])
        } else {
            try open().run("DELETE FROM archived WHERE thread_key = ?", [.text(thread)])
        }
    }

    // ── learning ─────────────────────────────────────────────────────────────

    /// Rebuilds the word counts from the inbox and the rules, then rescores every conversation.
    public func relearn() throws {
        let mutes = try mutes()
        let muted = Set(mutes.filter { $0.kind == Filters.sender }.map(\.value))
        let kept = Set(mutes.filter { $0.kind == Filters.keep }.map(\.value))
        let db = try open()
        let rows = try db.query("SELECT row_key, thread_key, title, body, outgoing, muted FROM messages WHERE type = 'sms' ORDER BY occurred_at DESC")
        let threads = Dictionary(grouping: rows) { $0.text("thread_key")! }

        var spam = [String: Int](), ham = [String: Int]()
        var spamDocs = 0, hamDocs = 0
        for (key, messages) in threads {
            guard let sender = Filters.target(key)?.value else { continue }
            let incoming = messages.filter { $0.int("outgoing") != 1 }.prefix(Self.docMessages)
            guard let first = incoming.first else { continue }
            let replied = messages.contains { $0.int("outgoing") == 1 }
            let words = incoming.reduce(into: Set<String>()) { $0.formUnion(Filters.tokens($1.text("body") ?? "")) }
            if muted.contains(sender) {
                spamDocs += 1
                for w in words { spam[w, default: 0] += 1 }
            } else if kept.contains(sender) || replied || Self.isContact(first, sender: sender) {
                hamDocs += 1
                for w in words { ham[w, default: 0] += 1 }
            }
        }
        var model = Filters.Model(spamDocs: spamDocs, hamDocs: hamDocs, counts: [:])
        for w in Set(spam.keys).union(ham.keys) { model.counts[w] = (spam[w] ?? 0, ham[w] ?? 0) }

        // Only a conversation's newest text carries the suggestion; it's what the inbox shows.
        let suspects: [String] = threads.compactMap { key, messages in
            guard let sender = Filters.target(key)?.value, let last = messages.first(where: { $0.int("outgoing") != 1 }) else { return nil }
            let body = last.text("body") ?? ""
            let protected = muted.contains(sender) || kept.contains(sender) || messages.contains { $0.int("outgoing") == 1 }
                || Self.isContact(last, sender: sender) || last.int("muted") == 1 || Filters.looksLikeCode(body)
            return !protected && Filters.score(sender: sender, body: body, model: model) >= Filters.suggest ? last.text("row_key") : nil
        }
        try db.transaction {
            try db.run("DELETE FROM filter_words")
            try db.run("INSERT INTO filter_words (token, spam, ham) VALUES ('', ?, ?)", [.int(Int64(spamDocs)), .int(Int64(hamDocs))])
            for (w, c) in model.counts {
                try db.run("INSERT INTO filter_words (token, spam, ham) VALUES (?, ?, ?)", [.text(w), .int(Int64(c.spam)), .int(Int64(c.ham))])
            }
            try db.run("UPDATE messages SET suspect = 0 WHERE suspect = 1")
            for key in suspects { try db.run("UPDATE messages SET suspect = 1 WHERE row_key = ?", [.text(key)]) }
        }
    }

    /// Something worth hearing about brings an archived conversation back.
    func unarchive(_ thread: String) throws {
        try archive(thread, false)
    }

    private func hasOutgoing(_ thread: String) throws -> Bool {
        try !open().query("SELECT 1 FROM messages WHERE thread_key = ? AND outgoing = 1 LIMIT 1", [.text(thread)]).isEmpty
    }

    /// Every conversation with `target`, from whichever phone relayed it.
    private func setMuted(_ db: SQLite, _ target: Filters.Target, _ muted: Bool) throws {
        for r in try db.query("SELECT DISTINCT thread_key FROM messages") {
            guard let key = r.text("thread_key"), Filters.target(key) == target else { continue }
            try db.run("UPDATE messages SET muted = ?, suspect = 0 WHERE thread_key = ?", [.int(muted ? 1 : 0), .text(key)])
        }
    }

    private func model(_ tokens: Set<String>) throws -> Filters.Model {
        let all = Array(tokens) + [""]
        let rows = try open().query(
            "SELECT * FROM filter_words WHERE token IN (\(all.map { _ in "?" }.joined(separator: ",")))",
            all.map { .text($0) }
        )
        return Self.model(rows)
    }

    private static func model(_ rows: [SQLite.Row]) -> Filters.Model {
        guard let totals = rows.first(where: { $0.text("token") == "" }) else { return .empty }
        var model = Filters.Model(spamDocs: Int(totals.int("spam") ?? 0), hamDocs: Int(totals.int("ham") ?? 0), counts: [:])
        for r in rows {
            guard let t = r.text("token"), !t.isEmpty else { continue }
            model.counts[t] = (Int(r.int("spam") ?? 0), Int(r.int("ham") ?? 0))
        }
        return model
    }

    /// A text's title is the contact's name when there is one, else the number itself.
    private static func isContact(_ r: SQLite.Row, sender: String) -> Bool {
        Receiver.normalize(r.text("title") ?? "") != sender
    }

    /// How many of a sender's newest texts make up its document.
    private static let docMessages = 30

    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
