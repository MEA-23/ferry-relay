package tech.thedevlab.ferry.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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

/** Silent status event every hour (§4.5) so receivers can tell a quiet phone from a dead one. */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val (battery, charging) = battery(ctx)
        val permissions = permissions(ctx)
        ctx.graph.relay.submit(
            StatusEvent(
                occurredAt = System.currentTimeMillis(),
                battery = battery,
                charging = charging,
                sims = Sims.active(ctx).map {
                    SimStatus(it.simSlotIndex, it.subscriptionId, it.carrierName?.toString(), active = true)
                },
                permissions = permissions,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
        ctx.graph.store.reportedPermissions = permissions
        return Result.success()
    }

    companion object {
        /**
         * Level and charging from the last battery broadcast, which the status bar reads too. Some
         * older phones answer 0 to BatteryManager's capacity property, so that's only a fallback,
         * and a level that can't be right is left out rather than reported.
         */
        private fun battery(ctx: Context): Pair<Int?, Boolean?> {
            val sticky = ContextCompat.registerReceiver(ctx, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                val status = sticky!!.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                return level * 100 / scale to (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
            val manager = ctx.getSystemService(BatteryManager::class.java)
            val capacity = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 1..100 }
            return capacity to capacity?.let { manager.isCharging }
        }

        /** What receivers list as missing. Notification access only counts once apps are picked to forward. */
        fun permissions(ctx: Context): Map<String, Boolean> = buildMap {
            put("sms", ctx.granted(Manifest.permission.RECEIVE_SMS))
            put("phone", ctx.granted(Manifest.permission.READ_PHONE_STATE))
            if (ctx.graph.store.allowedPackages.isNotEmpty()) {
                put("notifications", ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx))
            }
        }

        /** Reports now if a permission changed since the last report, instead of waiting for the next heartbeat. */
        fun reportIfChanged(context: Context) {
            if (permissions(context) == context.graph.store.reportedPermissions) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                "heartbeat-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HeartbeatWorker>().build(),
            )
        }

        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "heartbeat",
            // UPDATE, not KEEP, so phones still on an older schedule move to this one.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(1, TimeUnit.HOURS).build(),
        )
    }
}
