package com.showerideas.aura.wear

import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Task 50 — Health Connect HRV RMSSD reader.
 *
 * Reads Heart Rate Variability (HRV) RMSSD from Android Health Connect.
 * Health Connect became the canonical cross-app health data platform on Android 14+
 * (Wear OS 4+). On Wear OS 7, wrist sensor data flows through Health Connect directly.
 *
 * ## Why HRV matters for AURA
 * Gesture biometric quality degrades under high physiological stress (low HRV = high fatigue).
 * The HRV reading surfaces as a passive contextual signal on the tile; in future T53 work,
 * the gesture ML model can use HRV percentile as a quality-adjustment factor.
 *
 * ## Permissions
 * Requires `android.permission.health.READ_HEART_RATE_VARIABILITY` in Wear OS manifest.
 * Permission is requested via HealthConnectClient permission contract on first launch.
 *
 * ## Fallback
 * Returns `null` on any error (permission denied, HC unavailable, no data in last 24 h).
 * The tile simply omits the HRV row in that case.
 *
 * See: [developer.android.com/health-and-fitness/guides/health-connect]
 * See: [developer.android.com/reference/kotlin/androidx/health/connect/client]
 */
object HealthConnectHrvReader {

    private const val HC_PACKAGE = "com.google.android.apps.healthdata"

    /**
     * Read the most recent HRV RMSSD measurement within the last 24 hours.
     * @return RMSSD in milliseconds, or `null` if unavailable.
     */
    suspend fun readLatestHrvRmssd(context: Context): Double? {
        if (!isHealthConnectAvailable(context)) {
            Timber.d("HealthConnect not available on this device")
            return null
        }
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val now = Instant.now()
            val request = ReadRecordsRequest(
                recordType = HeartRateVariabilityRmssdRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    startTime = now.minus(24, ChronoUnit.HOURS),
                    endTime   = now
                )
            )
            val response = client.readRecords(request)
            val latest = response.records.maxByOrNull { it.time }
            latest?.heartRateVariabilityMillis?.also {
                Timber.d("HealthConnect HRV RMSSD = $it ms")
            }
        } catch (e: Exception) {
            Timber.w(e, "HealthConnect HRV read failed — no data shown")
            null
        }
    }

    /**
     * Check if Health Connect is installed and the API is available.
     * SDK_INT >= 26 and HC app installed are both required.
     */
    fun isHealthConnectAvailable(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(HC_PACKAGE, 0)
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Returns the list of permissions AURA needs from Health Connect.
     * Used in the permission request contract on Wear OS first-launch.
     */
    fun requiredPermissions(): Set<String> = setOf(
        "android.permission.health.READ_HEART_RATE_VARIABILITY"
    )
}
