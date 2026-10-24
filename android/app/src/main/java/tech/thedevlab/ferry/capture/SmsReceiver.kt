package tech.thedevlab.ferry.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.launchAsync
import tech.thedevlab.ferry.model.SmsEvent

/** Captures incoming SMS (§4.2). Multipart messages arrive as one broadcast and are joined here. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val subscriptionId = Sims.subscriptionId(intent)

        launchAsync(context) {
            val sim = Sims.resolve(context, subscriptionId)
            for ((from, messages) in parts.groupBy { it.displayOriginatingAddress ?: "unknown" }) {
                context.graph.relay.submit(
                    SmsEvent(
                        occurredAt = messages.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        sim = sim,
                        from = from,
                        body = messages.joinToString("") { it.displayMessageBody.orEmpty() },
                        contactName = Contacts.name(context, from),
                    ),
                )
            }
        }
    }
}
