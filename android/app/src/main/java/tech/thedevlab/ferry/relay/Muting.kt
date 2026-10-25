package tech.thedevlab.ferry.relay

import androidx.room.withTransaction
import tech.thedevlab.ferry.data.FerryDb
import tech.thedevlab.ferry.data.FilterWord
import tech.thedevlab.ferry.data.Filters
import tech.thedevlab.ferry.data.Message
import tech.thedevlab.ferry.data.Mute
import tech.thedevlab.ferry.data.Store
import tech.thedevlab.ferry.data.TextTools
import tech.thedevlab.ferry.model.CallEvent
import tech.thedevlab.ferry.model.FerryEvent
import tech.thedevlab.ferry.model.NotificationEvent
import tech.thedevlab.ferry.model.SmsEvent

/**
 * Mute rules on this phone, and learning from them which texts look like marketing (see
 * [Filters]). A muted message is still filed; it only arrives without sound or a banner.
 * The iOS counterpart is `Inbox`'s muting half.
 */
class Muting(private val db: FerryDb, private val store: Store) {
    data class Verdict(val muted: Boolean, val suspect: Boolean) {
        companion object {
            val NONE = Verdict(muted = false, suspect = false)
        }
    }

    /**
     * How a new incoming [event] on [threadKey] arrives. A muted sender or app always wins.
     * Contacts, conversations you've replied to, senders you kept, and one-time codes are never
     * muted by a word or a guess.
     */
    suspend fun judge(event: FerryEvent, threadKey: String): Verdict {
        val target = Filters.target(threadKey) ?: return Verdict.NONE
        val mutes = db.mutes().list()
        fun has(kind: String) = mutes.any { it.kind == kind && it.value == target.value }
        return when (event) {
            is NotificationEvent -> Verdict(target.kind == Filters.APP && has(Filters.APP), suspect = false)
            is CallEvent -> Verdict(has(Filters.SENDER), suspect = false)
            is SmsEvent -> when {
                has(Filters.SENDER) -> Verdict(muted = true, suspect = false)
                event.contactName != null || has(Filters.KEEP) || Filters.looksLikeCode(event.body) || db.messages().hasOutgoing(threadKey) ->
                    Verdict.NONE
                Filters.matchesWord(event.body, mutes.filter { it.kind == Filters.WORD }.map { it.value }) -> Verdict(muted = true, suspect = false)
                else -> {
                    val score = Filters.score(target.value, event.body, model(Filters.tokens(event.body)))
                    val muted = store.autoMuteMarketing && score >= Filters.AUTO
                    Verdict(muted, suspect = !muted && score >= Filters.SUGGEST)
                }
            }
            else -> Verdict.NONE
        }
    }

    /** Mutes a conversation's sender or app everywhere it appears. [label] is the name to list it under. */
    suspend fun mute(threadKey: String, label: String) {
        val target = Filters.target(threadKey) ?: return
        db.withTransaction {
            db.mutes().insert(Mute(target.kind, target.value, label, System.currentTimeMillis()))
            db.mutes().delete(Filters.KEEP, target.value)
            db.messages().setMuted(threadsOf(target), true)
        }
        if (target.kind == Filters.SENDER) relearn()
    }

    suspend fun unmute(target: Filters.Target) {
        db.withTransaction {
            db.mutes().delete(target.kind, target.value)
            db.messages().setMuted(threadsOf(target), false)
        }
        if (target.kind == Filters.SENDER) relearn()
    }

    /** The user says this sender isn't marketing: no more suggestions, and it teaches the model. */
    suspend fun keep(threadKey: String, label: String) {
        val target = Filters.target(threadKey) ?: return
        db.mutes().insert(Mute(Filters.KEEP, target.value, label, System.currentTimeMillis()))
        relearn()
    }

    suspend fun addWord(word: String) {
        val value = TextTools.normalize(word.trim()).ifEmpty { return }
        db.mutes().insert(Mute(Filters.WORD, value, word.trim(), System.currentTimeMillis()))
        db.mutes().delete(Filters.SKIP, value)
    }

    suspend fun removeWord(value: String) = db.mutes().delete(Filters.WORD, value)

    suspend fun skipWord(value: String) = db.mutes().insert(Mute(Filters.SKIP, value, value, System.currentTimeMillis()))

    /** Words to offer as rules, learned from muted senders. */
    suspend fun suggestions(): List<String> {
        val mutes = db.mutes().list()
        val exclude = mutes.filter { it.kind == Filters.WORD || it.kind == Filters.SKIP }.map { it.value }.toSet()
        return Filters.suggestions(model(db.filterWords().all()), exclude)
    }

    /** Rebuilds the word counts from the inbox and the rules, then rescores every conversation. */
    suspend fun relearn() {
        val mutes = db.mutes().list()
        val muted = mutes.filter { it.kind == Filters.SENDER }.map { it.value }.toSet()
        val kept = mutes.filter { it.kind == Filters.KEEP }.map { it.value }.toSet()
        val threads = db.messages().texts().groupBy { it.threadKey }

        val spam = HashMap<String, Int>()
        val ham = HashMap<String, Int>()
        var spamDocs = 0
        var hamDocs = 0
        for ((key, messages) in threads) {
            val sender = Filters.target(key)?.value ?: continue
            val incoming = messages.filter { !it.outgoing }.take(DOC_MESSAGES)
            if (incoming.isEmpty()) continue
            val counts = when {
                sender in muted -> spam.also { spamDocs++ }
                sender in kept || messages.any { it.outgoing } || isContact(incoming.first(), sender) -> ham.also { hamDocs++ }
                else -> continue
            }
            incoming.flatMapTo(HashSet()) { Filters.tokens(it.body) }.forEach { counts.merge(it, 1, Int::plus) }
        }
        val rows = (spam.keys + ham.keys).map { FilterWord(it, spam[it] ?: 0, ham[it] ?: 0) } + FilterWord("", spamDocs, hamDocs)
        val model = model(rows)

        // Only a conversation's newest text carries the suggestion; it's what the inbox shows.
        val suspects = threads.mapNotNull { (key, messages) ->
            val sender = Filters.target(key)?.value ?: return@mapNotNull null
            val last = messages.firstOrNull { !it.outgoing } ?: return@mapNotNull null
            val protected = sender in muted || sender in kept || messages.any { it.outgoing } || isContact(last, sender) || last.muted
            last.rowKey.takeIf { !protected && !Filters.looksLikeCode(last.body) && Filters.score(sender, last.body, model) >= Filters.SUGGEST }
        }
        db.withTransaction {
            db.filterWords().clear()
            rows.chunked(500).forEach { db.filterWords().insert(it) }
            db.messages().clearSuspects()
            suspects.chunked(500).forEach { db.messages().markSuspects(it) }
        }
    }

    /** Every conversation with [target], from whichever phone relayed it. */
    private suspend fun threadsOf(target: Filters.Target) = db.messages().threadKeys().filter { Filters.target(it) == target }

    private suspend fun model(tokens: Set<String>): Filters.Model = model(db.filterWords().find(tokens.toList() + ""))

    private fun model(rows: List<FilterWord>): Filters.Model {
        val totals = rows.firstOrNull { it.token.isEmpty() } ?: return Filters.Model.EMPTY
        return Filters.Model(totals.spam, totals.ham, rows.filter { it.token.isNotEmpty() }.associate { it.token to (it.spam to it.ham) })
    }

    companion object {
        /** How many of a sender's newest texts make up its document. */
        private const val DOC_MESSAGES = 30

        /** A text's title is the contact's name when there is one, else the number itself. */
        fun isContact(m: Message, sender: String) = Receiver.normalizeNumber(m.title) != sender
    }
}
