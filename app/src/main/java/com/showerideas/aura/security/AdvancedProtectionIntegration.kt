package com.showerideas.aura.security

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 65 — Android 16 Advanced Protection API integration.
 *
 * Android 16 (API 36) introduces [android.app.admin.AdvancedProtectionManager], allowing
 * third-party apps to detect when the user has enrolled in Google's Advanced Protection
 * Program and apply their own security hardening automatically.
 *
 * When Advanced Protection is active on a device:
 * - The OS blocks unknown-source installs and USB data.
 * - Theft protection is at maximum level.
 * - AURA applies the [AuraSecurityProfile.ADVANCED_PROTECTION_BASELINE] constraints:
 *   • maxGestureAttempts reduced 3 → 2
 *   • SAS verification forced on
 *   • QR relay disabled (server-assisted path leaks relay-server metadata)
 *   • LoRa disabled (unverified underlay channel)
 *
 * ## MDM intersection rule
 * Advanced Protection overrides are intersected with MDM policy — the most restrictive
 * value always wins. E.g., MDM allows_transports=[NFC,BLE,QR] + AP active → {NFC,BLE}
 * (QR removed by AP). This is document in docs/SECURITY.md.
 *
 * ## API-level guard
 * [AdvancedProtectionManager] is API 36 only. On older API levels, [isEnabled] always
 * returns false without throwing. The system NEVER assumes AP is enabled.
 *
 * See: [blog.google/security/whats-new-in-android-security-privacy-2026/]
 * See: [developer.android.com/reference/android/app/admin/AdvancedProtectionManager]
 */
@Singleton
class AdvancedProtectionIntegration @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * True when Android Advanced Protection Program is active on this device.
     *
     * Requires API 36+. Returns false safely on older API levels.
     * This check is synchronous and cheap — reads a cached system flag.
     */
    fun isEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < AP_MIN_API) {
            Timber.d("AdvancedProtection: API ${Build.VERSION.SDK_INT} < $AP_MIN_API — returning false")
            return false
        }
        return runCatching {
            @Suppress("NewApi")
            val manager = context.getSystemService(
                android.app.admin.AdvancedProtectionManager::class.java
            )
            manager?.isAdvancedProtectionEnabled ?: false
        }.getOrElse { e ->
            Timber.w(e, "AdvancedProtection: failed to query AdvancedProtectionManager")
            false
        }
    }

    /**
     * Merge Advanced Protection constraints with the current [enterpriseConfig].
     *
     * Priority: Advanced Protection overrides → MDM policy → user preferences.
     * "Most restrictive wins" for every field.
     *
     * @param enterpriseMaxAttempts MDM-configured max gesture attempts (or null = use default).
     * @param enterpriseRequiresSas MDM-configured SAS requirement (or null = use default).
     * @param enterpriseAllowedTransports MDM transport whitelist (empty = all allowed).
     * @return Merged [AuraSecurityProfile] reflecting all active constraints.
     */
    fun applyHardening(
        enterpriseMaxAttempts: Int? = null,
        enterpriseRequiresSas: Boolean? = null,
        enterpriseAllowedTransports: Set<TransportType> = emptySet(),
    ): AuraSecurityProfile {
        val apActive = isEnabled()
        Timber.i("AdvancedProtection: apActive=$apActive")

        val apBaseline = if (apActive) {
            AuraSecurityProfile.ADVANCED_PROTECTION_BASELINE
        } else {
            AuraSecurityProfile.DEFAULT
        }

        // Most restrictive wins for every field
        val maxAttempts = minOf(
            apBaseline.maxGestureAttempts,
            enterpriseMaxAttempts ?: apBaseline.maxGestureAttempts
        )
        val sasRequired = apBaseline.sasRequired || (enterpriseRequiresSas ?: false)

        // Transport intersection: AP removes QR+LoRa; MDM may restrict further
        val apTransportBlock = if (apActive) setOf(TransportType.QR_RELAY, TransportType.LORA) else emptySet()
        val effectiveTransports: Set<TransportType> = when {
            // Both AP and MDM specify restrictions → intersect (most restrictive)
            apActive && enterpriseAllowedTransports.isNotEmpty() ->
                enterpriseAllowedTransports - apTransportBlock
            // Only MDM specifies restrictions
            enterpriseAllowedTransports.isNotEmpty() ->
                enterpriseAllowedTransports
            // Only AP restricts
            apActive ->
                TransportType.entries.toSet() - apTransportBlock
            // No restrictions
            else -> emptySet()
        }

        val profile = AuraSecurityProfile(
            maxGestureAttempts = maxAttempts,
            sasRequired = sasRequired,
            qrRelayEnabled = TransportType.QR_RELAY !in apTransportBlock &&
                    (effectiveTransports.isEmpty() || TransportType.QR_RELAY in effectiveTransports),
            loraEnabled = TransportType.LORA !in apTransportBlock &&
                    (effectiveTransports.isEmpty() || TransportType.LORA in effectiveTransports),
            allowedTransports = effectiveTransports,
            advancedProtectionActive = apActive,
        )

        Timber.i(
            "AdvancedProtection: profile — maxAttempts=${profile.maxGestureAttempts} " +
            "sas=${profile.sasRequired} qr=${profile.qrRelayEnabled} lora=${profile.loraEnabled} " +
            "transports=${profile.allowedTransports} ap=$apActive"
        )
        return profile
    }

    companion object {
        /** Minimum API level for AdvancedProtectionManager. */
        const val AP_MIN_API = 36
    }
}
