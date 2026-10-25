package tech.thedevlab.ferry.data

import kotlin.math.abs
import kotlin.math.ln

/**
 * Deciding which incoming texts look like marketing, from fixed signals and from what the user
 * muted. Everything runs on this phone. Mirrored by ios/FerryKit/Model/Filters.swift; keep the two
 * in step, so a text scores the same on either.
 *
 * The learned part is a small naive Bayes over words and word pairs. Each muted sender is one
 * "marketing" document and each conversation that clearly matters (a contact, one you replied to,
 * one you kept) is one "wanted" document; [Model] holds how many of each used every word.
 */
object Filters {
    /**
     * At or above this, with suggestions on, a conversation gets a "Mute?" suggestion. Above what
     * a sender's shape, a link and a percentage add up to, so those alone never suggest.
     */
    const val SUGGEST = 4.0

    /** At or above this, and with automatic muting on, a text arrives muted. */
    const val AUTO = 5.0

    /** Word counts per document class, and how many documents of each there are. */
    class Model(val spamDocs: Int, val hamDocs: Int, val counts: Map<String, Pair<Int, Int>>) {
        companion object {
            val EMPTY = Model(0, 0, emptyMap())
        }
    }

    /** What a thread key names: a sender (`p:<device>:<number>`) or an app (`n:<device>:<package>`). */
    data class Target(val kind: String, val value: String)

    fun target(threadKey: String): Target? {
        val parts = threadKey.split(':', limit = 3)
        if (parts.size < 3) return null
        return when (parts[0]) {
            "p" -> Target(SENDER, parts[2])
            "n" -> Target(APP, parts[2])
            else -> null
        }
    }

    const val SENDER = "sender"
    const val APP = "app"
    const val WORD = "word"

    /** A sender the user said isn't marketing: never suggested or muted by a guess. */
    const val KEEP = "keep"

    /** A suggested word the user turned down. */
    const val SKIP = "skip"

    /** Normalized words of [text] (letters and digits, two or more, not just digits) and each adjacent pair. */
    fun tokens(text: String): Set<String> {
        val words = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            val w = sb.toString()
            sb.clear()
            if (w.codePointCount(0, w.length) >= 2 && !w.all { it in '0'..'9' }) words += w
        }
        val folded = TextTools.normalize(text)
        var i = 0
        while (i < folded.length && words.size < MAX_WORDS) {
            val cp = folded.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(cp) else flush()
            i += Character.charCount(cp)
        }
        if (words.size < MAX_WORDS) flush()
        return buildSet {
            addAll(words)
            for (j in 1 until words.size) add(words[j - 1] + " " + words[j])
        }
    }

    /** Score from fixed signals: opt-out wording, links, percentages, and senders that aren't phone numbers. */
    fun signals(sender: String, body: String): Double {
        val text = TextTools.normalize(body)
        var score = 0.0
        if (OPT_OUT.any { it in text }) score += 4
        if (LINKS.any { it in text }) score += 1
        if ('%' in text || '٪' in text) score += 1
        if (sender.any { it.isLetter() }) {
            score += 1
        } else if (sender.filterNot { it in " -().+" }.length in 1..6) {
            score += 1 // a short code
        }
        return score
    }

    /** A one-time code, which is never muted however it scores: four to eight digits and a word like "code". */
    fun looksLikeCode(body: String): Boolean {
        if (!CODE_DIGITS.containsMatchIn(TextTools.normalize(body))) return false
        val words = tokens(body)
        return CODE_WORDS.any { it in words }
    }

    /**
     * The learned score of [tokens]: the sum of the strongest twelve word scores, each at most
     * ±[WORD_CAP], clamped to ±6. A word counts towards marketing only once two or more muted
     * senders used it, so one sender's wording doesn't spread to everyone else.
     */
    fun learned(tokens: Set<String>, model: Model): Double {
        if (model.spamDocs < 2 || model.hamDocs < 2) return 0.0
        val scored = tokens.mapNotNull { t ->
            val (s, h) = model.counts[t] ?: return@mapNotNull null
            if (s + h == 0) return@mapNotNull null
            val r = ln((s + 1.0) / (model.spamDocs + 2.0)) - ln((h + 1.0) / (model.hamDocs + 2.0))
            if (r > 0 && s < 2) return@mapNotNull null
            t to r.coerceIn(-WORD_CAP, WORD_CAP)
        }
        val top = scored.sortedWith(compareByDescending<Pair<String, Double>> { abs(it.second) }.thenBy { it.first }).take(12)
        return top.sumOf { it.second }.coerceIn(-6.0, 6.0)
    }

    fun score(sender: String, body: String, model: Model): Double = signals(sender, body) + learned(tokens(body), model)

    /** Whether a word rule matches [body]. */
    fun matchesWord(body: String, words: Collection<String>): Boolean {
        if (words.isEmpty()) return false
        val text = TextTools.normalize(body)
        return words.any { it.isNotEmpty() && it in text }
    }

    /**
     * Words worth offering as rules: used by two or more muted senders and by nothing wanted,
     * at least three characters, the most used first.
     */
    fun suggestions(model: Model, exclude: Set<String>, limit: Int = 8): List<String> =
        model.counts.entries
            .filter { (t, c) -> c.first >= 2 && c.second == 0 && t.codePointCount(0, t.length) >= 3 && t !in exclude }
            .sortedWith(compareByDescending<Map.Entry<String, Pair<Int, Int>>> { it.value.first }.thenBy { it.key })
            .take(limit)
            .map { it.key }

    private const val MAX_WORDS = 60

    /** The most one word can add or take away. */
    private const val WORD_CAP = 1.5

    /** Opt-out wording, normalized: English and Arabic. */
    private val OPT_OUT = listOf(
        "reply stop", "text stop", "send stop", "txt stop", "stop to", "unsubscribe", "opt out", "opt-out", "optout",
        "الغاء الاشتراك", "لايقاف الرسائل", "ايقاف الرسائل", "الرسائل الاعلانيه", "رسائل اعلانيه",
    )

    private val LINKS = listOf("http://", "https://", "www.", "bit.ly")

    private val CODE_DIGITS = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")

    private val CODE_WORDS = listOf(
        "code", "otp", "verification", "verify", "password", "passcode", "pin",
        "رمز", "كود", "التحقق", "كلمه المرور", "تفعيل",
    )
}
