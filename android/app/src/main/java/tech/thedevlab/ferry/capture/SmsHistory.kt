package tech.thedevlab.ferry.capture

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms
import kotlinx.coroutines.launch
import tech.thedevlab.ferry.granted
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.model.Sim
import tech.thedevlab.ferry.model.SmsEvent

/**
 * Reads the phone's SMS store (needs READ_SMS): the history import and outgoing messages
 * sent from this phone (§4.2).
 *
 * What counts as history, so nothing is relayed twice:
 * - incoming SMS received before live capture started ([Store.liveSince]);
 * - sent SMS up to the outgoing watermark ([Store.outgoingSeenId]) — newer ones are relayed live.
 * Repeated imports only cover dates older than the last one ([Store.historyImportedSince]).
 */
object SmsHistory {
    private const val TYPE_INBOX = Sms.MESSAGE_TYPE_INBOX
    private const val TYPE_SENT = Sms.MESSAGE_TYPE_SENT
    private val PROJECTION = arrayOf(Sms._ID, Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.SUBSCRIPTION_ID)

    fun canRead(context: Context) = context.granted(Manifest.permission.READ_SMS)

    /** Starts the outgoing watermark at the newest sent SMS, once. */
    fun ensureOutgoingWatermark(context: Context) {
        val store = context.graph.store
        if (store.outgoingSeenId >= 0 || !canRead(context)) return
        store.outgoingSeenId = maxId(context, TYPE_SENT)
    }

    private fun maxId(context: Context, type: Int): Long = context.contentResolver.query(
        Sms.CONTENT_URI, arrayOf("MAX(${Sms._ID})"), "${Sms.TYPE} = ?", arrayOf(type.toString()), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else 0 } ?: 0

    /** The history selection for messages dated on or after [since]. */
    private fun historySelection(context: Context, since: Long): Pair<String, Array<String>> {
        val store = context.graph.store
        val until = store.historyImportedSince.takeIf { it > 0 } ?: Long.MAX_VALUE
        val where = "${Sms.DATE} >= ? AND ${Sms.DATE} < ? AND (" +
            "(${Sms.TYPE} = $TYPE_INBOX AND ${Sms.DATE} < ?) OR " +
            "(${Sms.TYPE} = $TYPE_SENT AND ${Sms._ID} <= ?))"
        return where to arrayOf(since.toString(), until.toString(), store.liveSince.toString(), store.outgoingSeenId.toString())
    }

    /** How many messages an import from [since] would add. */
    fun count(context: Context, since: Long): Int {
        if (!canRead(context)) return 0
        ensureOutgoingWatermark(context)
        val (where, args) = historySelection(context, since)
        return context.contentResolver.query(Sms.CONTENT_URI, arrayOf("COUNT(*)"), where, args, null)
            ?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
    }

    /** Streams history from [since] in pages of [pageSize], newest first. */
    suspend fun import(context: Context, since: Long, pageSize: Int = 200, onPage: suspend (List<SmsEvent>) -> Unit) {
        ensureOutgoingWatermark(context)
        val (where, args) = historySelection(context, since)
        val sims = SimCache(context)
        val names = HashMap<String, String?>()
        context.contentResolver.query(Sms.CONTENT_URI, PROJECTION, where, args, "${Sms.DATE} DESC")?.use { c ->
            val page = ArrayList<SmsEvent>(pageSize)
            while (c.moveToNext()) {
                val address = c.getString(1) ?: continue
                page += SmsEvent(
                    occurredAt = c.getLong(3),
                    sim = sims[c.getInt(5)],
                    from = address,
                    body = c.getString(2).orEmpty(),
                    contactName = names.getOrPut(address) { Contacts.name(context, address) },
                    direction = if (c.getInt(4) == TYPE_SENT) "out" else "in",
                    imported = true,
                )
                if (page.size == pageSize) {
                    onPage(page.toList())
                    page.clear()
                }
            }
            if (page.isNotEmpty()) onPage(page)
        }
    }

    /** Sent SMS newer than the watermark, which then moves past them. */
    fun takeNewOutgoing(context: Context): List<SmsEvent> {
        if (!canRead(context)) return emptyList()
        ensureOutgoingWatermark(context)
        val store = context.graph.store
        val sims = SimCache(context)
        val events = ArrayList<SmsEvent>()
        var newest = store.outgoingSeenId
        context.contentResolver.query(
            Sms.CONTENT_URI, PROJECTION, "${Sms.TYPE} = ? AND ${Sms._ID} > ?",
            arrayOf(TYPE_SENT.toString(), newest.toString()), "${Sms._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                newest = maxOf(newest, c.getLong(0))
                val address = c.getString(1) ?: continue
                events += SmsEvent(
                    occurredAt = c.getLong(3),
                    sim = sims[c.getInt(5)],
                    from = address,
                    body = c.getString(2).orEmpty(),
                    contactName = Contacts.name(context, address),
                    direction = "out",
                )
            }
        }
        if (newest != store.outgoingSeenId) store.outgoingSeenId = newest
        return events
    }

    /**
     * Subscription → SIM, resolved once per run. Unlike live capture, an unknown subscription
     * (a SIM that's since been removed) stays unknown instead of guessing the current SIM.
     */
    private class SimCache(context: Context) {
        private val byId = Sims.active(context).associate {
            it.subscriptionId to Sim(it.simSlotIndex, it.subscriptionId, it.carrierName?.toString()?.takeIf(String::isNotBlank))
        }

        operator fun get(subscriptionId: Int): Sim? = byId[subscriptionId]
    }
}

/** Relays SMS sent from this phone (any SMS app) as they land in the SMS store. */
class OutgoingSmsObserver(private val context: Context) : ContentObserver(Handler(Looper.getMainLooper())) {

    fun register() {
        if (!SmsHistory.canRead(context)) return
        SmsHistory.ensureOutgoingWatermark(context)
        context.contentResolver.registerContentObserver(Sms.CONTENT_URI, true, this)
    }

    fun unregister() = context.contentResolver.unregisterContentObserver(this)

    override fun onChange(selfChange: Boolean) {
        val g = context.graph
        g.scope.launch { g.relay.submitAll(SmsHistory.takeNewOutgoing(context)) }
    }
}
