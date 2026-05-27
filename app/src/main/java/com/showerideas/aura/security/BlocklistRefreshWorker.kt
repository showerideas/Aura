package com.showerideas.aura.security

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that refreshes the remote blocklist.
 *
 * Scheduled as a [PeriodicWorkRequest] every 24 hours, constrained to run
 * only when the device has network connectivity. On each run it calls
 * [TransparencyLogClient.refresh] and logs the outcome.
 *
 * Scheduling
 * Call [enqueue] from `Application.onCreate()` or the Hilt
 * `ApplicationComponent` initializer. WorkManager deduplicates by [WORK_NAME]
 * so multiple calls are safe.
 */
@HiltWorker
class BlocklistRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val logClient: TransparencyLogClient
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME            = "aura_blocklist_refresh"
        private const val INTERVAL_HOURS       = 24L
        private const val FLEX_HOURS           = 1L

        /**
         * Enqueue (or update) the periodic blocklist refresh worker.
         * Uses [ExistingPeriodicWorkPolicy.UPDATE] so a version update can
         * change the interval without leaving stale workers running.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BlocklistRefreshWorker>(
                repeatInterval = INTERVAL_HOURS, TimeUnit.HOURS,
                flexTimeInterval = FLEX_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Timber.d("BlocklistRefreshWorker: enqueued (${INTERVAL_HOURS}h interval)")
        }
    }

    override suspend fun doWork(): Result {
        Timber.i("BlocklistRefreshWorker: running refresh")
        val success = logClient.refresh()
        return if (success) {
            Timber.i("BlocklistRefreshWorker: refresh succeeded")
            Result.success()
        } else {
            Timber.w("BlocklistRefreshWorker: refresh failed — will retry at next interval")
            Result.retry()
        }
    }
}
