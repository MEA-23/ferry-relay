package tech.thedevlab.ferry.relay

import android.Manifest
import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tech.thedevlab.ferry.capture.Contacts
import tech.thedevlab.ferry.capture.Sims
import tech.thedevlab.ferry.capture.SmsHistory
import tech.thedevlab.ferry.capture.SmsSender
import tech.thedevlab.ferry.capture.samePhoneNumber
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.SentSms
import tech.thedevlab.ferry.granted
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.model.CommandPayload
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.model.SmsEvent
import tech.thedevlab.ferry.model.SmsStatusEvent
import tech.thedevlab.ferry.net.ApiException
import tech.thedevlab.ferry.net.StoredCommand
import java.io.IOException

/**
 * A node's side of §6.6: long-polls for commands from receivers, checks each one, sends the text
 * and reports back with an outgoing `sms` event and `sms_status` events.
 */
class Commands(private val context: Context) {

    /** Polls until cancelled or this phone stops being a keyed node. Runs inside the relay service. */
    suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val g = context.graph
            if (!g.store.isPaired || g.store.nodeRoot == null) return
            try {
                val api = g.api()
                val commands = api.commands(WAIT_S)
                if (commands.isNotEmpty()) {
                    commands.forEach { handle(it) }
                    api.ackCommands(commands.map { it.id })
                    g.db.sent().prune(System.currentTimeMillis() - KEEP_SENT_MS)
                }
                backoff = MIN_BACKOFF_MS
            } catch (e: ApiException) {
                // Removed, or no longer a node: uploads notice and wipe; there's nothing to poll for.
                if (e.status == 401 || e.status == 403) return
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            } catch (_: IOException) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Checks a command (§6.6) and sends its text, or reports why not. Returns once it's recorded, before the radio is done. */
    suspend fun handle(c: StoredCommand) {
        val g = context.graph
        val store = g.store
        val root = store.nodeRoot ?: return
        val plain = try {
            FerryCrypto.decryptCommand(root, store.userId!!, store.deviceId!!, c.id, FerryCrypto.Sealed(c.keyId, c.nonce, c.ct))
        } catch (_: FerryCrypto.DecryptionException) {
            return
        }
        val cmd = runCatching { FerryJson.decodeFromString(CommandPayload.serializer(), String(plain)) }.getOrNull() ?: return
        if (cmd.t != "send_sms") return
        // A replay: the first copy was handled and reported already.
        if (g.db.sent().exists(c.id)) return

        val now = System.currentTimeMillis()
        val sim = cmd.subscriptionId?.let { id -> Sims.active(context).find { it.subscriptionId == id } }
        val reason = when {
            cmd.expiresAt <= now || cmd.expiresAt > now + MAX_EXPIRY_MS -> "expired"
            !context.granted(Manifest.permission.SEND_SMS) -> "no_permission"
            cmd.subscriptionId != null && sim == null -> "no_sim"
            g.db.sent().dispatchedSince(now - 60_000) >= PER_MINUTE || g.db.sent().dispatchedSince(now - 86_400_000) >= PER_DAY -> "rate_limited"
            !store.allowNewNumbers && !known(cmd.to) -> "unknown_number"
            else -> null
        }
        // Recorded before anything is sent, so a crash or a replay can't send it twice.
        val status = reason?.let { "failed:$it" } ?: "sending"
        if (g.db.sent().insert(SentSms(c.id, cmd.to, cmd.body, now, status, dispatched = reason == null)) == -1L) return
        if (reason != null) {
            g.relay.submit(SmsStatusEvent(now, c.id, "failed", reason))
            return
        }
        g.relay.submit(
            SmsEvent(
                occurredAt = now,
                sim = Sims.resolve(context, cmd.subscriptionId),
                from = cmd.to,
                body = cmd.body,
                contactName = Contacts.name(context, cmd.to),
                direction = "out",
                commandId = c.id,
            ),
        )
        try {
            SmsSender.send(context, c.id, cmd.to, cmd.body, cmd.subscriptionId)
        } catch (e: Exception) {
            // No SIM, airplane mode, or a number the radio won't take.
            g.db.sent().setStatus(c.id, "failed:radio")
            g.relay.submit(SmsStatusEvent(System.currentTimeMillis(), c.id, "failed", "radio"))
        }
    }

    /** A number this phone has exchanged texts or calls with: relayed by Ferry, or in the SMS store. */
    private suspend fun known(number: String): Boolean =
        context.graph.db.knownNumbers().all().any { samePhoneNumber(context, it, number) } || SmsHistory.knows(context, number)

    private companion object {
        const val WAIT_S = 30
        const val MIN_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_EXPIRY_MS = 10 * 60_000L
        const val PER_MINUTE = 10
        const val PER_DAY = 100
        const val KEEP_SENT_MS = 30L * 86_400_000
    }
}
