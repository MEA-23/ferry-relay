package tech.thedevlab.ferry.relay

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tech.thedevlab.ferry.Notifications
import tech.thedevlab.ferry.graph
import tech.thedevlab.ferry.net.ApiException
import tech.thedevlab.ferry.net.userMessage
import tech.thedevlab.ferry.net.Envelope
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Uploads the outbox in batches until it is empty (§6.2). Retries with backoff on network errors. */
class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        val store = g.store
        if (!store.isPaired) return Result.success()
        val api = g.api()
        var batchSize = BATCH

        while (true) {
            val batch = g.db.outbox().take(batchSize)
            if (batch.isEmpty()) break
            try {
                val result = api.upload(batch.map { Envelope(it.id, it.keyId, it.nonce, it.ct, it.kind) })
                g.db.outbox().delete(result.accepted + result.duplicate)
                store.lastUploadAt = System.currentTimeMillis()
                store.lastError = null
                batchSize = BATCH
            } catch (e: ApiException) {
                when {
                    e.status == 401 -> {
                        g.wipeAfterRevocation(applicationContext)
                        Notifications.alert(applicationContext, "Ferry stopped relaying", "This phone was removed from your account.")
                        return Result.failure()
                    }
                    e.code == "key_rotated" -> {
                        store.lastError = e.userMessage()
                        return Result.failure()
                    }
                    // A rejected batch: retry one by one so a single bad event can't block the queue.
                    e.status == 400 || e.status == 413 -> {
                        if (batch.size == 1) g.db.outbox().delete(listOf(batch[0].id)) else batchSize = 1
                    }
                    else -> {
                        store.lastError = e.userMessage()
                        return Result.retry()
                    }
                }
            } catch (e: IOException) {
                store.lastError = e.userMessage()
                return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val BATCH = 50

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("flush", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
