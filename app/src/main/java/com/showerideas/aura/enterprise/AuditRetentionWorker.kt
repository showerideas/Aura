package com.showerideas.aura.enterprise

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.showerideas.aura.data.ExchangeAuditRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Phase 7.4 / H1 — WorkManager periodic worker that prunes stale exchange
 * audit log entries from Room.
 *
 * The retention window is keyed on [EnterprisePolicy.auditLogRetentionDays]:
 *   • Default (no MDM): 90 days
 *   • MDM-managed: whatever the admin sets via `audit_log_retention_days`
 *
 * Runs daily. Any entry whose [ExchangeAuditEntry.timestampMs] is older than
 * (now − retention_days) is deleted via [ExchangeAuditRepository.pruneOldEntries].
 *
 * ## Scheduling
 * Call [enqueue] from `Application.onCreate` (after Hilt is ready).
 * [ExistingPeriodicWorkPolicy.UPDATE] makes repeated calls idempotent.
 *
 * ## Compliance note
 * MDM-configured retention limits are a common enterprise security requirement
 * (SOC 2 Type II, ISO 27001). This worker ensures AURA respects the admin's
 * data-retention policy without manual intervention.
 */
@HiltWorker
class AuditRetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val auditRepository: ExchangeAuditRepository,
    private val policy: EnterprisePolicy
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME      = "aura_audit_retention_cleanup"
        private const val INTERVAL_HOURS = 24L
        private const val FLEX_HOURS     = 2L

        /**
         * Enqueue (or update) the periodic audit retention worker.
         *
         * Safe to call multiple times — WorkManager deduplicates by [WORK_NAME].
         * Typically called from [com.showerideas.aura.AuraApplication.onCreate].
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<AuditRetentionWorker>(
                repeatInterval     = INTERVAL_HOURS, TimeUnit.HOURS,
                flexTimeInterval   = FLEX_HOURS,     TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Timber.d("AuditRetentionWorker: scheduled (every %dh ± %dh)",
                INTERVAL_HOURS, FLEX_HOURS)
        }

        /**
         * Cancel the periodic audit retention worker.
         * Called if MDM disables the feature or during account reset.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.d("AuditRetentionWorker: cancelled")
        }
    }

    override suspend fun doWork(): Result {
        val retentionDays = policy.auditLogRetentionDays

        Timber.i("AuditRetentionWorker: pruning entries older than %d days", retentionDays)

        return try {
            auditRepository.pruneOldEntries(retentionDays)
            Timber.i("AuditRetentionWorker: pruning complete (retention=%d days)", retentionDays)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AuditRetentionWorker: pruning failed — will retry")
            Result.retry()
        }
    }
}
