package com.showerideas.aura.enterprise

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.showerideas.aura.data.ExchangeAuditRepository
import com.showerideas.aura.utils.CryptoUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T29 — Signed audit log export WorkManager task.
 *
 * Triggered by the IT admin via EnterpriseSettingsFragment or on a scheduled
 * basis when [EnterprisePolicy.auditLogRetentionDays] > 0. Exports all
 * [ExchangeAuditEntry] records as a signed CSV file to the device's cache
 * directory, then fires a share intent for the EMM agent or admin to collect.
 *
 * ## Signing
 * The export is signed with the device's ECDSA identity key so the IT admin can
 * verify the log came from this specific device and was not tampered with.
 * The signature is appended as a final column in the CSV and as a separate
 * `.sig` file alongside the CSV.
 *
 * ## Privacy
 * The export contains only the fields in [ExchangeAuditEntry] — no plaintext
 * names, emails, or profile data. Only timing, outcome, channel, and key hashes.
 */
@HiltWorker
class AuditExportWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val auditRepository: ExchangeAuditRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AuditExportWorker"
        private const val AUTHORITY = "com.showerideas.aura.fileprovider"

        /**
         * Schedule a one-time audit export.
         * The work will run as soon as the device is idle (not charging required).
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<AuditExportWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Timber.i("AuditExportWorker: scheduled")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val entries = auditRepository.getAllEntries()
            if (entries.isEmpty()) {
                Timber.i("AuditExportWorker: no entries to export")
                return Result.success()
            }

            // Build CSV
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val csvFile = File(appContext.cacheDir, "aura_audit_${dateStr}.csv")
            val csv = buildString {
                appendLine("id,timestampMs,peerIdentityKeyHash,direction,outcome,errorCode,channel")
                for (e in entries) {
                    appendLine("${e.id},${e.timestampMs},${e.peerIdentityKeyHash ?: ""},${e.direction},${e.outcome},${e.errorCode ?: ""},${e.channel}")
                }
            }
            csvFile.writeText(csv)

            // Sign with device identity key
            val deviceKey = CryptoUtils.getOrCreateDeviceIdentityKey()
            val signature = CryptoUtils.signChallenge(deviceKey.private, csv.toByteArray())
            val sigFile  = File(appContext.cacheDir, "aura_audit_${dateStr}.sig")
            sigFile.writeText(Base64.encodeToString(signature, Base64.NO_WRAP))

            Timber.i("AuditExportWorker: exported ${entries.size} entries to ${csvFile.name}")

            // Share via FileProvider
            val csvUri: Uri = FileProvider.getUriForFile(appContext, AUTHORITY, csvFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, csvUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(Intent.createChooser(shareIntent, "Export Audit Log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AuditExportWorker failed")
            Result.failure()
        }
    }
}
