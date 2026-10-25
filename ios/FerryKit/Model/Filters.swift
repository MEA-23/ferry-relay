import Foundation

/// Deciding which incoming texts look like marketing, from fixed signals and from what the user
/// muted. Everything runs on this iPhone. Mirrors android/app/src/main/java/tech/thedevlab/ferry/data/Filters.kt;
/// keep the two in step, so a text scores the same on either.
///
/// The learned part is a small naive Bayes over words and word pairs. Each muted sender is one
/// "marketing" document and each conversation that clearly matters (a contact, one you replied to,
/// one you kept) is one "wanted" document; `Model` holds how many of each used every word.
public enum Filters {
    /// At or above this, with suggestions on, a conversation gets a "Mute?" suggestion. Above what
    /// a sender's shape, a link and a percentage add up to, so those alone never suggest.
    public static let suggest = 4.0
    /// At or above this, and with automatic muting on, a text arrives muted.
    public static let auto = 5.0

    public static let sender = "sender"
    public static let app = "app"
    public static let word = "word"
    /// A sender the user said isn't marketing: never suggested or muted by a guess.
    public static let keep = "keep"
    /// A suggested word the user turned down.
    public static let skip = "skip"

    /// Word counts per document class, and how many documents of each there are.
    public struct Model: Sendable {
        public var spamDocs: Int
        public var hamDocs: Int
        public var counts: [String: (spam: Int, ham: Int)]

        public static let empty = Model(spamDocs: 0, hamDocs: 0, counts: [:])
    }

    /// What a thread key names: a sender (`p:<device>:<number>`) or an app (`n:<device>:<package>`).
    public struct Target: Hashable, Sendable {
        public let kind: String
        public let value: String

        public init(kind: String, value: String) {
            self.kind = kind
            self.value = value
        }
    }

    public static func target(_ threadKey: String) -> Target? {
        let parts = threadKey.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        switch parts[0] {
        case "p": return Target(kind: sender, value: String(parts[2]))
        case "n": return Target(kind: app, value: String(parts[2]))
        default: return nil
        }
    }

    /// Normalized words of `text` (letters and digits, two or more, not just digits) and each adjacent pair.
    public static func tokens(_ text: String) -> Set<String> {
        var words = [String]()
        var current = String.UnicodeScalarView()
        func flush() {
            let w = String(current)
            current = String.UnicodeScalarView()
            if w.unicodeScalars.count >= 2, !w.unicodeScalars.allSatisfy({ ("0"..."9").contains($0) }) { words.append(w) }
        }
        for s in TextTools.normalize(text).unicodeScalars {
            if words.count >= maxWords { break }
            if isLetterOrDigit(s) { current.append(s) } else { flush() }
        }
        if words.count < maxWords { flush() }
        var out = Set(words)
        for j in words.indices.dropFirst() { out.insert(words[j - 1] + " " + words[j]) }
        return out
    }

    /// Score from fixed signals: opt-out wording, links, percentages, and senders that aren't phone numbers.
    public static func signals(sender: String, body: String) -> Double {
        let text = TextTools.normalize(body)
        var score = 0.0
        if optOut.contains(where: { text.contains($0) }) { score += 4 }
        if links.contains(where: { text.contains($0) }) { score += 1 }
        if text.contains("%") || text.contains("٪") { score += 1 }
        if sender.contains(where: \.isLetter) {
            score += 1
        } else if (1...6).contains(sender.filter { !" -().+".contains($0) }.count) {
            score += 1 // a short code
        }
        return score
    }

    /// A one-time code, which is never muted however it scores: four to eight digits and a word like "code".
    public static func looksLikeCode(_ body: String) -> Bool {
        // Runs of digits, split on everything else: one of four to eight is a code's shape.
        let runs = TextTools.normalize(body).unicodeScalars.split { !("0"..."9").contains($0) }
        guard runs.contains(where: { (4...8).contains($0.count) }) else { return false }
        let words = tokens(body)
        return codeWords.contains(where: words.contains)
    }

    /// The learned score of `tokens`: the sum of the strongest twelve word scores, each at most
    /// ±`wordCap`, clamped to ±6. A word counts towards marketing only once two or more muted
    /// senders used it, so one sender's wording doesn't spread to everyone else.
    public static func learned(_ tokens: Set<String>, model: Model) -> Double {
        guard model.spamDocs >= 2, model.hamDocs >= 2 else { return 0 }
        let scored: [(String, Double)] = tokens.compactMap { t in
            guard let c = model.counts[t], c.spam + c.ham > 0 else { return nil }
            let r = log((Double(c.spam) + 1) / (Double(model.spamDocs) + 2)) - log((Double(c.ham) + 1) / (Double(model.hamDocs) + 2))
            if r > 0 && c.spam < 2 { return nil }
            return (t, min(max(r, -wordCap), wordCap))
        }
        let top = scored.sorted { abs($0.1) != abs($1.1) ? abs($0.1) > abs($1.1) : $0.0 < $1.0 }.prefix(12)
        return min(max(top.reduce(0) { $0 + $1.1 }, -6), 6)
    }

    public static func score(sender: String, body: String, model: Model) -> Double {
        signals(sender: sender, body: body) + learned(tokens(body), model: model)
    }

    /// Whether a word rule matches `body`.
    public static func matchesWord(_ body: String, words: [String]) -> Bool {
        guard !words.isEmpty else { return false }
        let text = TextTools.normalize(body)
        return words.contains { !$0.isEmpty && text.contains($0) }
    }

    /// Words worth offering as rules: used by two or more muted senders and by nothing wanted,
    /// at least three characters, the most used first.
    public static func suggestions(_ model: Model, excluding exclude: Set<String>, limit: Int = 8) -> [String] {
        model.counts
            .filter { t, c in c.spam >= 2 && c.ham == 0 && t.unicodeScalars.count >= 3 && !exclude.contains(t) }
            .sorted { $0.value.spam != $1.value.spam ? $0.value.spam > $1.value.spam : $0.key < $1.key }
            .prefix(limit)
            .map(\.key)
    }

    private static let maxWords = 60

    /// The most one word can add or take away.
    private static let wordCap = 1.5

    /// Opt-out wording, normalized: English and Arabic.
    private static let optOut = [
        "reply stop", "text stop", "send stop", "txt stop", "stop to", "unsubscribe", "opt out", "opt-out", "optout",
        "الغاء الاشتراك", "لايقاف الرسائل", "ايقاف الرسائل", "الرسائل الاعلانيه", "رسائل اعلانيه",
    ]

    private static let links = ["http://", "https://", "www.", "bit.ly"]

    private static let codeWords = [
        "code", "otp", "verification", "verify", "password", "passcode", "pin",
        "رمز", "كود", "التحقق", "كلمه المرور", "تفعيل",
    ]

    private static func isLetterOrDigit(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: true
        default: false
        }
    }
}
