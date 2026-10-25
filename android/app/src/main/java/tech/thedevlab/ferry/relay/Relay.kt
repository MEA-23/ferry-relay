package tech.thedevlab.ferry.relay

import android.content.Context
import androidx.room.withTransaction
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.Activity
import tech.thedevlab.ferry.data.FerryDb
import tech.thedevlab.ferry.data.KnownNumber
import tech.thedevlab.ferry.data.OutboxItem
import tech.thedevlab.ferry.data.PendingEvent
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.data.Store
import tech.thedevlab.ferry.model.CallEvent
import tech.thedevlab.ferry.model.FerryEvent
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.model.IntroduceEvent
import tech.thedevlab.ferry.model.NotificationEvent
import tech.thedevlab.ferry.model.ProfileEvent
import tech.thedevlab.ferry.model.SmsEvent
import tech.thedevlab.ferry.model.SmsStatusEvent
import tech.thedevlab.ferry.model.StatusEvent
import tech.thedevlab.ferry.net.StoredEvent
import tech.thedevlab.ferry.uuidV7

/** An event from a node this receiver doesn't trust yet: keep it and retry after the next device refresh (§3.7). */
class UnknownNodeException(deviceId: String) : Exception("no trusted profile for $deviceId")

/** Source side: encrypt captured events into the outbox. Receiver side: decrypt pulled events for [Receiver]. */
class Relay(private val context: Context, private val store: Store, private val db: FerryDb) {

    suspend fun submit(event: FerryEvent) = submitAll(listOf(event))

    /**
     * Encrypts [events] into the outbox in one transaction and schedules a single upload. A node
     * that has no key yet keeps them as they are until it does (§5.6 flow A); see [drainPending].
     */
    suspend fun submitAll(events: List<FerryEvent>) {
        if (events.isEmpty() || !store.has(Role.SOURCE)) return
        val seal = sealer() ?: if (store.awaitingKey) null else return
        val now = System.currentTimeMillis()
        db.withTransaction {
            for (event in events) {
                val eventId = uuidV7()
                val plaintext = FerryJson.encodeToString(FerryEvent.serializer(), event.truncated())
                val imported = event is SmsEvent && event.imported
                // Nothing to alert anyone about: status, profile, history, and texts this phone sent itself.
                val quiet = event is StatusEvent || event is ProfileEvent || event is SmsStatusEvent || imported ||
                    (event is SmsEvent && event.direction == "out")
                val kind = if (quiet) "silent" else "alert"
                if (seal != null) {
                    val sealed = seal(eventId, plaintext.toByteArray())
                    db.outbox().insert(OutboxItem(eventId, kind, sealed.keyId, sealed.nonce, sealed.ct, now))
                } else {
                    db.pending().insert(PendingEvent(eventId, kind, plaintext, now))
                }
                if (!imported) {
                    summarize(event)?.let { (title, body) ->
                        db.activity().insert(Activity(0, eventId, typeOf(event), title, body, simSlot(event), event.occurredAt, false, null))
                    }
                }
            }
            db.activity().prune(KEEP_ACTIVITY)
            // Numbers receivers may text back without the SMS store's say-so (§6.6).
            val numbers = events.mapNotNull { e ->
                when (e) {
                    is SmsEvent -> KnownNumber(e.from, e.occurredAt)
                    is CallEvent -> e.from?.let { KnownNumber(it, e.occurredAt) }
                    else -> null
                }
            }
            if (numbers.isNotEmpty()) db.knownNumbers().insert(numbers)
        }
        if (seal != null) FlushWorker.enqueue(context)
    }

    /** Encrypts with the UMK on a phone that also receives, or with the node's own root (§3.7). */
    private fun sealer(): ((String, ByteArray) -> FerryCrypto.Sealed)? {
        val userId = store.userId ?: return null
        val deviceId = store.deviceId ?: return null
        store.currentUmk?.let { umk -> return { id, plain -> FerryCrypto.encryptEvent(umk, userId, deviceId, id, plain) } }
        store.nodeRoot?.let { root -> return { id, plain -> FerryCrypto.encryptNodeEvent(root, userId, deviceId, id, plain) } }
        return null
    }

    /** Once a node has its key: encrypts what it kept while it waited, in the order it was captured. */
    suspend fun drainPending() {
        val seal = sealer() ?: return
        db.withTransaction {
            val pending = db.pending().all()
            for (p in pending) {
                val sealed = seal(p.id, p.plaintext.toByteArray())
                db.outbox().insert(OutboxItem(p.id, p.kind, sealed.keyId, sealed.nonce, sealed.ct, p.createdAt))
            }
            db.pending().delete(pending.map { it.id })
        }
        FlushWorker.enqueue(context)
    }

    /** "$nodePk:$keyId" → root, so each node's X25519 runs once per process. */
    private val roots = HashMap<String, ByteArray>()

    /**
     * Decrypts a pulled event. Returns null (and logs nothing) for unknown event types (§10).
     * Throws [UnknownNodeException] for a node with no trusted profile yet.
     */
    fun decrypt(e: StoredEvent): FerryEvent? {
        val sealed = FerryCrypto.Sealed(e.keyId, e.nonce, e.ct)
        val umks = store.umks
        val plain = if (e.keyId in umks) {
            FerryCrypto.decryptEvent(umks, store.userId!!, e.sourceDeviceId, e.id, sealed)
        } else {
            FerryCrypto.decryptNodeEvent(nodeRoot(e, umks.values), store.userId!!, e.sourceDeviceId, e.id, sealed)
        }
        return runCatching { FerryJson.decodeFromString(FerryEvent.serializer(), String(plain)) }.getOrNull()
    }

    private fun nodeRoot(e: StoredEvent, umks: Collection<ByteArray>): ByteArray {
        val nodePk = store.trustedNodes[e.sourceDeviceId] ?: throw UnknownNodeException(e.sourceDeviceId)
        if (nodePk in store.removedNodeKeys) throw FerryCrypto.DecryptionException("node ${e.sourceDeviceId} was removed")
        roots["$nodePk:${e.keyId}"]?.let { return it }
        val pk = FerryCrypto.fromB64u(nodePk)
        for (umk in umks) {
            val root = FerryCrypto.nodeRootForReceiver(umk, pk)
            if (FerryCrypto.nodeKeyId(root) == e.keyId) return root.also { roots["$nodePk:${e.keyId}"] = it }
        }
        // A key this profile doesn't explain, e.g. the node re-paired and its new profile isn't here yet.
        throw UnknownNodeException(e.sourceDeviceId)
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
            is ProfileEvent -> "profile"
            is IntroduceEvent -> "introduce"
            is SmsStatusEvent -> "sms_status"
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
            is StatusEvent, is ProfileEvent, is IntroduceEvent, is SmsStatusEvent -> null
        }
    }
}
