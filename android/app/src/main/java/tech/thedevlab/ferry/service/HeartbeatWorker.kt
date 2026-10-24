package tech.thedevlab.ferry.service

import android.Manifest
import android.content.Context
import android.os.BatteryManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tech.thedevlab.ferry.BuildConfig
import tech.thedevlab.ferry.capture.Sims
import tech.thedevlab.ferry.granted
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.model.SimStatus
import tech.thedevlab.ferry.model.StatusEvent
import java.util.concurrent.TimeUnit

/** Silent status event every 6 hours (§4.5) so receivers can tell a quiet phone from a dead one. */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val battery = ctx.getSystemService(BatteryManager::class.java)
        ctx.graph.relay.submit(
            StatusEvent(
                occurredAt = System.currentTimeMillis(),
                battery = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                charging = battery.isCharging,
                sims = Sims.active(ctx).map {
                    SimStatus(it.simSlotIndex, it.subscriptionId, it.carrierName?.toString(), active = true)
                },
                permissions = mapOf(
                    "sms" to ctx.granted(Manifest.permission.RECEIVE_SMS),
                    "phone" to ctx.granted(Manifest.permission.READ_PHONE_STATE),
                    "notifications" to (ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)),
                ),
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "heartbeat",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(6, TimeUnit.HOURS).build(),
        )
    }
}
