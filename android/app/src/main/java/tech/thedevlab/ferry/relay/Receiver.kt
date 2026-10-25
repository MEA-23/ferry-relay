package tech.thedevlab.ferry.relay

import androidx.room.withTransaction
import tech.thedevlab.ferry.data.FerryDb
import tech.thedevlab.ferry.data.Message
import tech.thedevlab.ferry.data.Seen
import tech.thedevlab.ferry.data.TextTools
import tech.thedevlab.ferry.model.CallEvent
import tech.thedevlab.ferry.model.FerryEvent
import tech.thedevlab.ferry.model.IntroduceEvent
import tech.thedevlab.ferry.model.NotificationEvent
import tech.thedevlab.ferry.model.ProfileEvent
import tech.thedevlab.ferry.model.SmsEvent
import tech.thedevlab.ferry.model.SmsStatusEvent
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

        /** A relaying phone's heartbeat (§4.5), for the caller to keep as its latest status. */
        data class Status(val deviceId: String, val event: StatusEvent) : Outcome

        /** A node's new name and SIM labels (§4.6), for the caller to write into its profile. */
        data class Profile(val deviceId: String, val event: ProfileEvent) : Outcome

        /** A trusted node added another one (§4.7), for the caller to check and trust. */
        data class Introduced(val by: String, val event: IntroduceEvent) : Outcome
    }

    /**
     * Throws [tech.thedevlab.ferry.crypto.FerryCrypto.DecryptionException] if the event can't be
     * decrypted, and [UnknownNodeException] if it's from a node that isn't trusted yet.
     */
    suspend fun ingest(e: StoredEvent): Outcome {
        if (db.seen().exists(e.id)) return Outcome.Duplicate
        val event = relay.decrypt(e)
        // Marked seen only after a successful decrypt, so a failure is retried by the next pull.
        return db.withTransaction {
            if (db.seen().insert(Seen(e.id, e.receivedAt)) == -1L) return@withTransaction Outcome.Duplicate
            if (event is ProfileEvent) return@withTransaction Outcome.Profile(e.sourceDeviceId, event)
            if (event is IntroduceEvent) return@withTransaction Outcome.Introduced(e.sourceDeviceId, event)
            if (event is StatusEvent) return@withTransaction Outcome.Status(e.sourceDeviceId, event)
            if (event is SmsStatusEvent) {
                db.messages().updateStatus(commandRow(event.commandId), event.reason?.let { "${event.status}:$it" } ?: event.status)
                return@withTransaction Outcome.Quiet
            }
            val message = event?.let { toMessage(e, it) } ?: return@withTransaction Outcome.Quiet
            if ((event as? SmsEvent)?.commandId != null) {
                db.messages().saveSent(message)
                return@withTransaction Outcome.Filed
            }
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

        /** The row of a text sent through a node (§6.6): the one queued here, then the node's copy. */
        fun commandRow(commandId: String) = "cmd:$commandId"

        fun toMessage(e: StoredEvent, event: FerryEvent): Message? {
            val src = e.sourceDeviceId
            val (title, body) = when (event) {
                is SmsEvent -> (event.contactName ?: event.from) to event.body
                is StatusEvent, is ProfileEvent, is IntroduceEvent, is SmsStatusEvent -> return null
                else -> Relay.summarize(event) ?: return null
            }
            val (threadKey, number) = when (event) {
                is SmsEvent -> "p:$src:${normalizeNumber(event.from)}" to event.from
                is CallEvent -> "p:$src:${normalizeNumber(event.from ?: "unknown")}" to event.from
                is NotificationEvent -> "n:$src:${event.packageName}" to null
                is StatusEvent, is ProfileEvent, is IntroduceEvent, is SmsStatusEvent -> return null
            }
            val sms = event as? SmsEvent
            val quiet = sms != null && (sms.imported || sms.direction == "out")
            return Message(
                rowKey = (event as? CallEvent)?.let { "call:$src:${it.callKey}" } ?: sms?.commandId?.let(::commandRow) ?: e.id,
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
                status = sms?.commandId?.let { "sending" },
                subscriptionId = sms?.sim?.subscriptionId,
            )
        }

        /** Same as iOS, so a number reads the same however the phone formatted it. */
        fun normalizeNumber(number: String) = number.filterNot { it in " -()." }.lowercase()
    }
}
