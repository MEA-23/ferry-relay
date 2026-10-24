package tech.thedevlab.ferry.relay

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.net.ApiException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Receiver mode on Android: pull, decrypt, notify, ack (§6.3–6.4). Runs right away when a push
 * (FCM or UnifiedPush) says something arrived, when the app opens, and periodically: every 15
 * minutes without push, every 4 hours with it.
 */
class PullWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val store = g.store
        if (!store.has(Role.RECEIVER)) return Result.success()
        val api = g.api()
        try {
            var cursor = store.pullCursor
            do {
                val page = api.pull(cursor)
                for (e in page.events) {
                    if (e.sourceDeviceId == store.deviceId) continue
                    val outcome = try {
                        g.receiver.ingest(e)
                    } catch (_: FerryCrypto.DecryptionException) {
                        continue
                    }
                    if (outcome is Receiver.Outcome.New) {
                        Notifications.message(applicationContext, outcome.message, store.deviceNames[e.sourceDeviceId])
                    }
                }
                cursor = page.next
                store.pullCursor = cursor
                if (page.events.isNotEmpty()) api.ack(cursor)
            } while (page.hasMore)
            g.db.seen().prune(System.currentTimeMillis() - Receiver.SEEN_RETENTION_MS)
        } catch (e: ApiException) {
            if (e.status == 401) {
                g.wipeAfterRevocation(applicationContext)
                Notifications.alert(applicationContext, "Ferry stopped receiving", "This phone was removed from your account.")
                return Result.failure()
            }
            return Result.retry()
        } catch (_: IOException) {
            return Result.retry()
        }
        return Result.success()
    }

    /** Only used on Android 11 and older, where an expedited pull runs as a short foreground job. */
    override suspend fun getForegroundInfo() = ForegroundInfo(Notifications.ID_SYNC, Notifications.syncing(applicationContext))

    companion object {
        /** Unique name of the on-demand pull, to watch it finish. */
        const val NOW = "pull-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Pulls now. [expedited] asks Android to run it immediately, for pushes. */
        fun runNow(context: Context, expedited: Boolean = false) = WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            // A push that lands mid-pull queues one more pull, so its event isn't missed.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PullWorker>().setConstraints(network).apply {
                if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build(),
        )

        /**
         * Every 15 minutes while there's no push; with push, only a slow safety net for a lost one.
         * Called again whenever the push registration changes.
         */
        fun schedule(context: Context): Operation {
            val minutes = if (context.graph.store.pushProvider == null) 15L else SAFETY_NET_MINUTES
            return WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "pull",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PullWorker>(minutes, TimeUnit.MINUTES).setConstraints(network).build(),
            )
        }

        private const val SAFETY_NET_MINUTES = 4 * 60L
    }
}
