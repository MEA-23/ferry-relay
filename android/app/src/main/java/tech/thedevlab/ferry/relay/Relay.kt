package tech.thedevlab.ferry.relay

import android.content.Context
import androidx.room.withTransaction
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.Activity
import tech.thedevlab.ferry.data.FerryDb
import tech.thedevlab.ferry.data.OutboxItem
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.data.Store
import tech.thedevlab.ferry.model.CallEvent
import tech.thedevlab.ferry.model.FerryEvent
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.model.NotificationEvent
import tech.thedevlab.ferry.model.SmsEvent
import tech.thedevlab.ferry.model.StatusEvent
import tech.thedevlab.ferry.net.StoredEvent
import tech.thedevlab.ferry.uuidV7

/** Source side: encrypt captured events into the outbox. Receiver side: decrypt pulled events for [Receiver]. */
class Relay(private val context: Context, private val store: Store, private val db: FerryDb) {

    suspend fun submit(event: FerryEvent) = submitAll(listOf(event))

    /** Encrypts [events] into the outbox in one transaction and schedules a single upload. */
    suspend fun submitAll(events: List<FerryEvent>) {
        if (events.isEmpty() || !store.has(Role.SOURCE)) return
        val umk = store.currentUmk ?: return
        val userId = store.userId!!
        val deviceId = store.deviceId!!
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (event in events) {
                val eventId = uuidV7()
                val plaintext = FerryJson.encodeToString(FerryEvent.serializer(), event.truncated()).toByteArray()
                val sealed = FerryCrypto.encryptEvent(umk, userId, deviceId, eventId, plaintext)
                val imported = event is SmsEvent && event.imported
                // Nothing to alert anyone about: status, history, and texts this phone sent itself.
                val quiet = event is StatusEvent || imported || (event is SmsEvent && event.direction == "out")
                val kind = if (quiet) "silent" else "alert"
                db.outbox().insert(OutboxItem(eventId, kind, sealed.keyId, sealed.nonce, sealed.ct, now))
                if (!imported) {
                    summarize(event)?.let { (title, body) ->
                        db.activity().insert(Activity(0, eventId, typeOf(event), title, body, simSlot(event), event.occurredAt, false, null))
                    }
                }
            }
            db.activity().prune(KEEP_ACTIVITY)
        }
        FlushWorker.enqueue(context)
    }

    /** Decrypts a pulled event. Returns null (and logs nothing) for unknown event types (§10). */
    fun decrypt(e: StoredEvent): FerryEvent? {
        val sealed = FerryCrypto.Sealed(e.keyId, e.nonce, e.ct)
        val plain = FerryCrypto.decryptEvent(store.umks, store.userId!!, e.sourceDeviceId, e.id, sealed)
        return runCatching { FerryJson.decodeFromString(FerryEvent.serializer(), String(plain)) }.getOrNull()
    }

    companion object {
        private const val KEEP_ACTIVITY = 500

        /** Keeps the padded plaintext under 256 KiB (§4.6): 50k chars is ≤ 200 KB of UTF-8. */
        private const val MAX_TEXT = 50_000

        private fun String.cut() = if (length > MAX_TEXT) take(MAX_TEXT) else this

        private fun FerryEvent.truncated(): FerryEvent = when (this) {
            is SmsEvent -> if (body.length > MAX_TEXT) copy(body = body.cut(), truncated = true) else this
            is NotificationEvent ->
                if ((text?.length ?: 0) > MAX_TEXT || (title?.length ?: 0) > MAX_TEXT) {
                    copy(title = title?.cut(), text = text?.cut(), truncated = true)
                } else {
                    this
                }
            else -> this
        }

        fun typeOf(e: FerryEvent) = when (e) {
            is SmsEvent -> "sms"
            is CallEvent -> "call"
            is NotificationEvent -> "notification"
            is StatusEvent -> "status"
        }

        private fun simSlot(e: FerryEvent) = when (e) {
            is SmsEvent -> e.sim?.slot
            is CallEvent -> e.sim?.slot
            else -> null
        }

        /** Title and body for the activity list and the inbox; null for events not worth showing. */
        fun summarize(e: FerryEvent): Pair<String, String>? = when (e) {
            is SmsEvent -> (e.contactName ?: e.from) to (if (e.direction == "out") "You: ${e.body}" else e.body)
            is CallEvent -> (e.contactName ?: e.from ?: "Unknown number") to when (e.state) {
                "ringing" -> "Incoming call"
                "missed" -> "Missed call"
                "rejected" -> "Declined call"
                else -> "Call · ${e.durationSec / 60}:${"%02d".format(e.durationSec % 60)}"
            }
            is NotificationEvent -> listOfNotNull(e.appName, e.title).joinToString(" · ") to (e.text ?: "")
            is StatusEvent -> null
        }
    }
}
