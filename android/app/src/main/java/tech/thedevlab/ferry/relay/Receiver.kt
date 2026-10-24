package tech.thedevlab.ferry.relay

import androidx.room.withTransaction
import tech.thedevlab.ferry.data.FerryDb
import tech.thedevlab.ferry.data.Message
import tech.thedevlab.ferry.data.Seen
import tech.thedevlab.ferry.data.TextTools
import tech.thedevlab.ferry.model.CallEvent
import tech.thedevlab.ferry.model.FerryEvent
import tech.thedevlab.ferry.model.NotificationEvent
import tech.thedevlab.ferry.model.SmsEvent
import tech.thedevlab.ferry.model.StatusEvent
import tech.thedevlab.ferry.net.StoredEvent

/** Files decrypted events into the inbox. The Android counterpart of iOS `Receiver`. */
class Receiver(private val db: FerryDb, private val relay: Relay) {
    sealed interface Outcome {
        /** A new message worth a notification. */
        data class New(val message: Message) : Outcome

        /** Filed as read, without a notification: sent texts and imported history (§4.2). */
        data object Filed : Outcome

        /** Already handled, or an older state of a call that's already stored. */
        data object Duplicate : Outcome

        /** A status heartbeat or unknown event type (§10): nothing to show. */
        data object Quiet : Outcome
    }

    /** Throws [tech.thedevlab.ferry.crypto.FerryCrypto.DecryptionException] if the event can't be decrypted. */
    suspend fun ingest(e: StoredEvent): Outcome {
        if (db.seen().exists(e.id)) return Outcome.Duplicate
        val event = relay.decrypt(e)
        // Marked seen only after a successful decrypt, so a failure is retried by the next pull.
        return db.withTransaction {
            if (db.seen().insert(Seen(e.id, e.receivedAt)) == -1L) return@withTransaction Outcome.Duplicate
            val message = event?.let { toMessage(e, it) } ?: return@withTransaction Outcome.Quiet
            when {
                !db.messages().save(message) -> Outcome.Duplicate
                message.unread -> Outcome.New(message)
                else -> Outcome.Filed
            }
        }
    }

    companion object {
        /** How long seen ids are kept: longer than the server's 30-day retention (§3.4). */
        const val SEEN_RETENTION_MS = 45L * 24 * 3600 * 1000

        fun toMessage(e: StoredEvent, event: FerryEvent): Message? {
            val src = e.sourceDeviceId
            val (title, body) = when (event) {
                is SmsEvent -> (event.contactName ?: event.from) to event.body
                is StatusEvent -> return null
                else -> Relay.summarize(event) ?: return null
            }
            val (threadKey, number) = when (event) {
                is SmsEvent -> "p:$src:${normalizeNumber(event.from)}" to event.from
                is CallEvent -> "p:$src:${normalizeNumber(event.from ?: "unknown")}" to event.from
                is NotificationEvent -> "n:$src:${event.packageName}" to null
                is StatusEvent -> return null
            }
            val sms = event as? SmsEvent
            val quiet = sms != null && (sms.imported || sms.direction == "out")
            return Message(
                rowKey = (event as? CallEvent)?.let { "call:$src:${it.callKey}" } ?: e.id,
                eventId = e.id,
                sourceDeviceId = src,
                type = Relay.typeOf(event),
                threadKey = threadKey,
                title = title,
                body = body,
                number = number,
                simSlot = (event as? SmsEvent)?.sim?.slot ?: (event as? CallEvent)?.sim?.slot,
                occurredAt = event.occurredAt,
                receivedAt = e.receivedAt,
                outgoing = sms?.direction == "out",
                unread = !quiet,
                searchText = TextTools.normalize(body),
            )
        }

        /** Same as iOS, so a number reads the same however the phone formatted it. */
        fun normalizeNumber(number: String) = number.filterNot { it in " -()." }.lowercase()
    }
}
