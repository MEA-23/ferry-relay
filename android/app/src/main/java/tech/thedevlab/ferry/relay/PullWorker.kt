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
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.crypto.FerryCrypto
import tech.thedevlab.ferry.data.HeldEvent
import tech.thedevlab.ferry.data.Role
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.model.FerryJson
import tech.thedevlab.ferry.net.Api
import tech.thedevlab.ferry.net.ApiException
import tech.thedevlab.ferry.net.StoredEvent
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
            val updates = mutableListOf<Receiver.Outcome>()
            var cursor = store.pullCursor
            do {
                val page = api.pull(cursor)
                for (e in page.events) {
                    if (e.sourceDeviceId == store.deviceId) continue
                    ingest(e)?.let(updates::add)
                }
                cursor = page.next
                store.pullCursor = cursor
                if (page.events.isNotEmpty()) api.ack(cursor)
            } while (page.hasMore)
            val held = g.db.held().count() > 0
            if (updates.isNotEmpty() || held) syncDevices(api, updates)
            val now = System.currentTimeMillis()
            g.db.seen().prune(now - Receiver.SEEN_RETENTION_MS)
            g.db.held().prune(now - Receiver.SEEN_RETENTION_MS)
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

    /** Files one event and notifies; holds it if its node isn't trusted yet. Returns a node's profile or introduction. */
    private suspend fun ingest(e: StoredEvent): Receiver.Outcome? {
        val g = applicationContext.graph
        val outcome = try {
            g.receiver.ingest(e)
        } catch (_: UnknownNodeException) {
            g.db.held().insert(HeldEvent(e.id, FerryJson.encodeToString(StoredEvent.serializer(), e), e.receivedAt))
            return null
        } catch (_: FerryCrypto.DecryptionException) {
            return null
        }
        return handled(e, outcome)
    }

    private fun handled(e: StoredEvent, outcome: Receiver.Outcome): Receiver.Outcome? = when (outcome) {
        is Receiver.Outcome.New -> {
            Notifications.message(applicationContext, outcome.message, applicationContext.graph.store.deviceNames[e.sourceDeviceId])
            null
        }
        is Receiver.Outcome.Status -> {
            val store = applicationContext.graph.store
            if ((store.remoteStatuses[e.sourceDeviceId]?.occurredAt ?: 0) < outcome.event.occurredAt) {
                store.remoteStatuses += e.sourceDeviceId to outcome.event
            }
            null
        }
        is Receiver.Outcome.Profile, is Receiver.Outcome.Introduced -> outcome
        else -> null
    }

    /**
     * Writes nodes' new names into their profiles (§4.6) and trusts the nodes they introduced (§4.7),
     * refreshes which nodes are trusted, then retries events that were held for want of a profile
     * (§3.7), until nothing more comes free.
     */
    private suspend fun syncDevices(api: Api, outcomes: List<Receiver.Outcome>) {
        val g = applicationContext.graph
        var updates = outcomes
        while (true) {
            val introduced = mutableListOf<Receiver.Outcome.Introduced>()
            for (u in updates) {
                runCatching {
                    when (u) {
                        is Receiver.Outcome.Profile -> g.devices.updateProfile(api, u.deviceId) { current ->
                            if (current?.nodePk == null || (current.namedAt ?: 0) >= u.event.occurredAt) return@updateProfile null
                            current.copy(name = u.event.name, sims = u.event.sims, namedAt = u.event.occurredAt)
                        }
                        is Receiver.Outcome.Introduced ->
                            if (g.devices.acceptIntroduction(api, u.by, u.event.deviceId, u.event.nodePk)) introduced += u
                        else -> {}
                    }
                }
            }
            g.devices.refresh(api)
            for (u in introduced) {
                Notifications.introduced(applicationContext, g.store.deviceNames[u.by] ?: "A relaying phone", u.event.deviceId)
            }
            updates = retryHeld()
            if (updates.isEmpty()) return
        }
    }

    private suspend fun retryHeld(): List<Receiver.Outcome> {
        val g = applicationContext.graph
        return g.db.held().all().mapNotNull { h ->
            val e = FerryJson.decodeFromString(StoredEvent.serializer(), h.json)
            val outcome = try {
                g.receiver.ingest(e)
            } catch (_: UnknownNodeException) {
                return@mapNotNull null
            } catch (_: FerryCrypto.DecryptionException) {
                null
            }
            g.db.held().delete(h.id)
            outcome?.let { handled(e, it) }
        }
    }

    /** Only used on Android 11 and older, where an expedited pull runs as a short foreground job. */
    override suspend fun getForegroundInfo() = ForegroundInfo(Notifications.ID_SYNC, Notifications.syncing(applicationContext))

    companion object {
        /** Unique name of the on-demand pull, to watch it finish. */
        const val NOW = "pull-now"

        /** Pulls now and waits, at most 15 seconds, for it to finish. */
        suspend fun runAndWait(context: Context) {
            runNow(context).await()
            withTimeoutOrNull(15_000) {
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW).first { infos -> infos.all { it.state.isFinished } }
            }
        }

        /**
         * While the app is on screen, a long poll notices events the moment they're stored (§6.5),
         * sent and delivered updates included, which never come as a push. The pull stays this worker's.
         */
        suspend fun live(context: Context) {
            val store = context.graph.store
            while (currentCoroutineContext().isActive) {
                if (!store.has(Role.RECEIVER) || store.revoked) {
                    delay(5_000)
                    continue
                }
                try {
                    val start = System.currentTimeMillis()
                    val page = context.graph.api().pull(store.pullCursor, limit = 1, wait = 25)
                    if (page.events.isNotEmpty()) {
                        runAndWait(context)
                        // Paces the loop should a pull keep failing and leave the cursor where it was.
                        delay(1_000)
                    } else if (System.currentTimeMillis() - start < 1_000) {
                        // A server without live pull answers at once; don't spin on it.
                        delay(15_000)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(5_000)
                }
            }
        }

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
