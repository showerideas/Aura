package com.showerideas.aura.security

import timber.log.Timber
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

/**
 * Certificate pin validation utilities.
 *
 * Implements two-pin configuration (primary + backup) and pin expiry detection.
 * Works alongside the existing SPKI pinning in RelayClient.
 */
object PinValidator {

    private const val EXPIRY_WARNING_DAYS = 30L

    /**
     * Days until [cert] expires. Negative means already expired.
     */
    fun daysUntilExpiry(cert: X509Certificate): Long {
        val now      = System.currentTimeMillis()
        val expiryMs = cert.notAfter.time
        return TimeUnit.MILLISECONDS.toDays(expiryMs - now)
    }

    /** @return true if the certificate expires within 30 days. */
    fun isNearingExpiry(cert: X509Certificate): Boolean =
        daysUntilExpiry(cert) <= EXPIRY_WARNING_DAYS

    /**
     * Validate an SPKI hash against primary and backup pins.
     *
     * @param spkiHash   SHA-256 of certificate SubjectPublicKeyInfo DER bytes (base64).
     * @param primaryPin Primary SPKI pin (base64 SHA-256).
     * @param backupPin  Backup SPKI pin. May be empty/blank if not configured.
     * @return [PinResult.MATCH_PRIMARY], [PinResult.MATCH_BACKUP], or [PinResult.MISMATCH].
     */
    fun validatePin(spkiHash: String, primaryPin: String, backupPin: String): PinResult {
        return when {
            spkiHash == primaryPin -> {
                Timber.d("PinValidator: primary pin matched")
                PinResult.MATCH_PRIMARY
            }
            backupPin.isNotBlank() && spkiHash == backupPin -> {
                Timber.i("PinValidator: backup pin matched — consider rotating primary")
                PinResult.MATCH_BACKUP
            }
            else -> {
                Timber.e("PinValidator: SPKI MISMATCH observed=$spkiHash")
                PinResult.MISMATCH
            }
        }
    }

    enum class PinResult { MATCH_PRIMARY, MATCH_BACKUP, MISMATCH }
}
