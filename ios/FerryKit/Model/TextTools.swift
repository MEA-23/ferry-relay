import Foundation

/// Search and text-direction helpers for the inbox. Matching ignores case and diacritics (Arabic
/// harakat included) and treats Arabic letter variants as one — أ/إ/آ/ٱ → ا, ى/ی → ي, ة → ه —
/// so a search finds a word however it was vowelled or spelled. Arabic-Indic digits match 0–9.
/// Mirrors android/app/src/main/java/tech/thedevlab/ferry/data/TextTools.kt; keep the two in step.
public enum TextTools {
    /// The searchable form of `text`. Stored alongside each message and compared with normalized queries.
    public static func normalize(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for s in text.unicodeScalars { out.append(contentsOf: fold(s)) }
        return String(out)
    }

    /// Where `query` occurs in `text`, widened to whole characters. Empty for a blank query.
    public static func matches(in text: String, of query: String) -> [Range<String.Index>] {
        let q = Array(normalize(query.trimmingCharacters(in: .whitespacesAndNewlines)).unicodeScalars)
        guard !q.isEmpty else { return [] }

        // Each folded scalar remembers the original scalar it came from.
        var folded = [Unicode.Scalar]()
        var start = [String.Index]()
        var end = [String.Index]()
        let scalars = text.unicodeScalars
        var i = scalars.startIndex
        while i < scalars.endIndex {
            let next = scalars.index(after: i)
            for f in fold(scalars[i]) {
                folded.append(f)
                start.append(i)
                end.append(next)
            }
            i = next
        }

        var out = [Range<String.Index>]()
        var from = 0
        while from + q.count <= folded.count {
            if folded[from..<(from + q.count)].elementsEqual(q) {
                out.append(text.rangeOfComposedCharacterSequences(for: start[from]..<end[from + q.count - 1]))
                from += q.count
            } else {
                from += 1
            }
        }
        return out
    }

    /// False for senders that are names ("VODAFONE", "Amazon"), which can't receive texts. Short
    /// codes stay repliable: many take answers like STOP.
    public static func acceptsReplies(_ sender: String) -> Bool {
        !sender.contains { $0.isLetter }
    }

    /// True when the first strongly directional character is right-to-left (Arabic, Hebrew, …).
    public static func isRTL(_ text: String) -> Bool {
        for s in text.unicodeScalars {
            guard isLetter(s) else { continue } // digits, punctuation and marks don't decide
            return isRTLScript(s)
        }
        return false
    }

    private static func fold(_ s: Unicode.Scalar) -> [Unicode.Scalar] {
        switch s.value {
        case 0x0660...0x0669: return [Unicode.Scalar(0x30 + s.value - 0x0660)!]
        case 0x06F0...0x06F9: return [Unicode.Scalar(0x30 + s.value - 0x06F0)!]
        default: break
        }
        var out = [Unicode.Scalar]()
        for c in String(Character(s)).lowercased().decomposedStringWithCanonicalMapping.unicodeScalars {
            switch c.properties.generalCategory {
            case .nonspacingMark, .enclosingMark, .format: continue
            default: break
            }
            switch c.value {
            case 0x0640: continue // tatweel, a stretching stroke
            case 0x0671: out.append("\u{0627}") // ٱ → ا
            case 0x0649, 0x06CC: out.append("\u{064A}") // ى ی → ي
            case 0x06A9: out.append("\u{0643}") // ک → ك
            case 0x0629: out.append("\u{0647}") // ة → ه
            default: out.append(c)
            }
        }
        return out
    }

    private static func isLetter(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: true
        default: false
        }
    }

    private static func isRTLScript(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x0590...0x08FF, 0xFB1D...0xFDFF, 0xFE70...0xFEFF, 0x10800...0x10FFF, 0x1E800...0x1EFFF: true
        default: false
        }
    }
}
