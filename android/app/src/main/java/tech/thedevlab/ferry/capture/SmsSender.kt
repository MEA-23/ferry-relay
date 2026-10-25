package tech.thedevlab.ferry.capture

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.launchAsync
import tech.thedevlab.ferry.model.SmsStatusEvent

/** Sends a text for a §6.6 command. The radio's answers arrive at [SmsStatusReceiver]. */
object SmsSender {
    const val ACTION_SENT = "tech.thedevlab.ferry.SMS_SENT"
    const val ACTION_DELIVERED = "tech.thedevlab.ferry.SMS_DELIVERED"
    const val EXTRA_COMMAND = "commandId"

    fun send(context: Context, commandId: String, to: String, body: String, subscriptionId: Int?) {
        val sms = manager(context, subscriptionId)
        val parts = sms.divideMessage(body)
        // Reports ride on the last part: it's sent once the whole text is.
        val sent = pending(context, ACTION_SENT, commandId)
        val delivered = pending(context, ACTION_DELIVERED, commandId)
        if (parts.size <= 1) {
            sms.sendTextMessage(to, null, body, sent, delivered)
        } else {
            val sentList = ArrayList<PendingIntent?>(List(parts.size) { if (it == parts.lastIndex) sent else null })
            val deliveredList = ArrayList<PendingIntent?>(List(parts.size) { if (it == parts.lastIndex) delivered else null })
            sms.sendMultipartTextMessage(to, null, parts, sentList, deliveredList)
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(context: Context, subscriptionId: Int?): SmsManager = when {
        Build.VERSION.SDK_INT >= 31 -> context.getSystemService(SmsManager::class.java).let { m ->
            subscriptionId?.let(m::createForSubscriptionId) ?: m
        }
        subscriptionId != null -> SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        else -> SmsManager.getDefault()
    }

    private fun pending(context: Context, action: String, commandId: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action + commandId).hashCode(),
        Intent(context, SmsStatusReceiver::class.java).setAction(action).putExtra(EXTRA_COMMAND, commandId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
    )
}

/** The radio's answer for a text Ferry sent: reported to the receivers as an `sms_status` event (§4.8). */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(SmsSender.EXTRA_COMMAND) ?: return
        val ok = resultCode == Activity.RESULT_OK
        val (status, reason) = when (intent.action) {
            SmsSender.ACTION_SENT -> if (ok) "sent" to null else "failed" to "radio"
            SmsSender.ACTION_DELIVERED -> if (ok) "delivered" to null else return
            else -> return
        }
        launchAsync(context) {
            val g = context.graph
            g.db.sent().setStatus(id, reason?.let { "$status:$it" } ?: status)
            g.relay.submit(SmsStatusEvent(System.currentTimeMillis(), id, status, reason))
        }
    }
}
