package tech.thedevlab.ferry.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telephony.TelephonyManager
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tech.thedevlab.ferry.data.CurrentCall
import tech.thedevlab.ferry.granted
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.launchAsync
import tech.thedevlab.ferry.model.CallEvent
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Incoming calls (§4.3): a `ringing` event in real time, then the outcome (missed / rejected /
 * ended) from the call log once the call is over. Both carry the same `callKey`.
 */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val store = context.graph.store
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                // With READ_CALL_LOG the broadcast fires twice; only the second copy has the number.
                if (number == null && context.granted(Manifest.permission.READ_CALL_LOG)) return
                val subscriptionId = Sims.subscriptionId(intent)
                launchAsync(context) { onRinging(context, number, subscriptionId) }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> store.currentCall?.let { store.currentCall = it.copy(answered = true) }
            TelephonyManager.EXTRA_STATE_IDLE -> if (store.currentCall != null) CallOutcomeWorker.enqueue(context)
        }
    }

    private suspend fun onRinging(context: Context, number: String?, subscriptionId: Int?) {
        val store = context.graph.store
        val now = System.currentTimeMillis()
        val current = store.currentCall
        if (current != null && current.number == number && now - current.startedAt < 120_000) return

        val sim = Sims.resolve(context, subscriptionId)
        val call = CurrentCall(UUID.randomUUID().toString(), number, sim, now)
        store.currentCall = call
        context.graph.relay.submit(
            CallEvent(now, sim, number, "ringing", call.callKey, contactName = Contacts.name(context, number)),
        )
    }
}

/** Reads the call log shortly after the call ends — the provider writes the entry asynchronously. */
class CallOutcomeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val call = g.store.currentCall ?: return Result.success()
        g.store.currentCall = null

        val (state, duration) = fromCallLog(call) ?: ((if (call.answered) "ended" else "missed") to 0)
        g.relay.submit(
            CallEvent(
                occurredAt = call.startedAt,
                sim = call.sim,
                from = call.number,
                state = state,
                callKey = call.callKey,
                durationSec = duration,
                contactName = Contacts.name(applicationContext, call.number),
            ),
        )
        return Result.success()
    }

    private fun fromCallLog(call: CurrentCall): Pair<String, Int>? {
        if (!applicationContext.granted(Manifest.permission.READ_CALL_LOG)) return null
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION)
        val digits = call.number?.filter(Char::isDigit)?.takeLast(9)
        return runCatching {
            applicationContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((call.startedAt - 60_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0)?.filter(Char::isDigit)
                    if (digits != null && number?.endsWith(digits) != true) continue
                    val state = when (c.getInt(1)) {
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "rejected"
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ended"
                        else -> continue
                    }
                    return@use state to c.getInt(2)
                }
                null
            }
        }.getOrNull()
    }

    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<CallOutcomeWorker>().setInitialDelay(3, TimeUnit.SECONDS).build(),
        )
    }
}
