package tech.thedevlab.ferry.relay

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tech.thedevlab.ferry.capture.SmsHistory
import tech.thedevlab.ferry.graph

/**
 * Copies SMS history into the outbox as silent, `imported` events (§4.2). Encrypting is fast;
 * the upload itself is the normal [FlushWorker], so the import survives restarts and going offline.
 */
class HistoryImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = ctx.graph.store
        if (!SmsHistory.canRead(ctx)) return Result.failure()
        val since = inputData.getLong(KEY_SINCE, 0)
        val total = SmsHistory.count(ctx, since)
        var done = 0
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to total))

        // Pages arrive newest first, so after each one everything from its oldest message up to
        // live capture is covered — a cancelled import resumes without duplicates.
        SmsHistory.import(ctx, since) { page ->
            if (isStopped) return@import
            ctx.graph.relay.submitAll(page)
            store.historyImportedSince = page.minOf { it.occurredAt }.coerceAtLeast(1)
            store.historyImportedCount += page.size
            done += page.size
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        }
        if (isStopped) return Result.failure()

        // Everything from `since` up to live capture is now covered.
        store.historyImportedSince = since.coerceAtLeast(1)
        return Result.success(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
    }

    companion object {
        const val NAME = "history-import"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val KEY_SINCE = "since"

        fun start(context: Context, since: Long) = WorkManager.getInstance(context).enqueueUniqueWork(
            NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<HistoryImportWorker>().setInputData(workDataOf(KEY_SINCE to since)).build(),
        )

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
