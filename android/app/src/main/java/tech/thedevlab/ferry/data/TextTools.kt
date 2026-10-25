package tech.thedevlab.ferry.data

import java.text.Normalizer
import java.util.Locale

/**
 * Search and text-direction helpers for the inbox. Matching ignores case and diacritics (Arabic
 * harakat included) and treats Arabic letter variants as one — أ/إ/آ/ٱ → ا, ى/ی → ي, ة → ه —
 * so a search finds a word however it was vowelled or spelled. Arabic-Indic digits match 0–9.
 * Mirrored by ios/FerryKit/Model/TextTools.swift; keep the two in step.
 */
object TextTools {
    /** The searchable form of [text]. Stored alongside each message and compared with [normalize]d queries. */
    fun normalize(text: String): String = fold(text).text

    /** Where [query] occurs in [text], as index ranges into [text]. Empty for a blank query. */
    fun matches(text: String, query: String): List<IntRange> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        val folded = fold(text)
        val out = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val i = folded.text.indexOf(q, from)
            if (i < 0) break
            out += folded.start[i] until folded.end[i + q.length - 1]
            from = i + q.length
        }
        return out
    }

    /**
     * False for senders that are names ("VODAFONE", "Amazon"), which can't receive texts. Short
     * codes stay repliable: many take answers like STOP.
     */
    fun acceptsReplies(sender: String): Boolean = sender.none { it.isLetter() }

    /** True when the first strongly directional character is right-to-left (Arabic, Hebrew, …). */
    fun isRtl(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
            i += Character.charCount(cp)
        }
        return false
    }

    /** Folded text, with the range of the original character each folded char came from. */
    private class Folded(val text: String, val start: IntArray, val end: IntArray)

    private fun fold(text: String): Folded {
        val sb = StringBuilder(text.length)
        val start = ArrayList<Int>(text.length)
        val end = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            for (c in foldCodePoint(cp)) {
                sb.append(c)
                start += i
                end += i + n
            }
            i += n
        }
        return Folded(sb.toString(), start.toIntArray(), end.toIntArray())
    }

    private fun foldCodePoint(cp: Int): String {
        when (cp) {
            in 0x0660..0x0669 -> return ('0' + (cp - 0x0660)).toString()
            in 0x06F0..0x06F9 -> return ('0' + (cp - 0x06F0)).toString()
        }
        val lower = String(Character.toChars(cp)).lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (c in Normalizer.normalize(lower, Normalizer.Form.NFD)) {
            when (Character.getType(c).toByte()) {
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT -> continue
            }
            sb.append(
                when (c) {
                    'ـ' -> continue // tatweel, a stretching stroke
                    'ٱ' -> 'ا'
                    'ى', 'ی' -> 'ي'
                    'ک' -> 'ك'
                    'ة' -> 'ه'
                    else -> c
                },
            )
        }
        return sb.toString()
    }
}
