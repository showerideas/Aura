package com.showerideas.aura.enterprise

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 7.4 — Enterprise / MDM policy reader.
 *
 * Reads managed app restrictions delivered via the Android [RestrictionsManager]
 * (set by an EMM/MDM through `android.app.action.APPLICATION_RESTRICTIONS_CHANGED`
 * or the Device Policy Controller). All callers should treat the policy as
 * immutable for the lifetime of the current Activity — re-read on CONFIGURATION_CHANGED
 * or when [ACTION_APPLICATION_RESTRICTIONS_CHANGED] is broadcast.
 *
 * ## Supported restriction keys (set in app_restrictions.xml):
 *
 * | Key                        | Type    | Default | Description                         |
 * |----------------------------|---------|---------|-------------------------------------|
 * | `max_gesture_attempts`     | integer | 5       | Max auth attempts before lock-out   |
 * | `require_sas_verification` | bool    | false   | Block exchange without SAS confirm  |
 * | `disable_backup`           | bool    | false   | Hide and block encrypted backup     |
 * | `audit_log_retention_days` | integer | 90      | Days to keep ExchangeAuditRecord    |
 * | `disable_tor_proxy`        | bool    | false   | Hide Tor/Orbot toggle in Settings   |
 * | `enforce_pin_lock`         | bool    | false   | Require device PIN before exchange  |
 */
@Singleton
class EnterprisePolicy @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // Restriction keys — must match app_restrictions.xml entries
        const val KEY_MAX_GESTURE_ATTEMPTS     = "max_gesture_attempts"
        const val KEY_REQUIRE_SAS              = "require_sas_verification"
        const val KEY_DISABLE_BACKUP           = "disable_backup"
        const val KEY_AUDIT_RETENTION_DAYS     = "audit_log_retention_days"
        const val KEY_DISABLE_TOR              = "disable_tor_proxy"
        const val KEY_ENFORCE_PIN_LOCK         = "enforce_pin_lock"

        // Defaults (no MDM enrolled)
        private const val DEFAULT_MAX_ATTEMPTS     = 5
        private const val DEFAULT_RETENTION_DAYS   = 90
    }

    /** Raw restrictions bundle; null if device is not managed or no restrictions set. */
    private val restrictions: Bundle?
        get() = context.getSystemService<RestrictionsManager>()
            ?.applicationRestrictions
            .also { Timber.d("EnterprisePolicy: restrictions=$it") }

    // -------------------------------------------------------------------------
    // Policy accessors
    // -------------------------------------------------------------------------

    /** Maximum number of gesture authentication attempts before lock-out. */
    val maxGestureAttempts: Int
        get() = restrictions?.getInt(KEY_MAX_GESTURE_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)
            ?: DEFAULT_MAX_ATTEMPTS

    /** If true, exchange must not complete without SAS code confirmation. */
    val requireSasVerification: Boolean
        get() = restrictions?.getBoolean(KEY_REQUIRE_SAS, false) ?: false

    /** If true, the encrypted backup feature is hidden and blocked. */
    val backupDisabled: Boolean
        get() = restrictions?.getBoolean(KEY_DISABLE_BACKUP, false) ?: false

    /** Number of days to retain exchange audit records in Room. */
    val auditLogRetentionDays: Int
        get() = restrictions?.getInt(KEY_AUDIT_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            ?: DEFAULT_RETENTION_DAYS

    /** If true, the Tor/Orbot proxy toggle is hidden and disabled. */
    val torProxyDisabled: Boolean
        get() = restrictions?.getBoolean(KEY_DISABLE_TOR, false) ?: false

    /** If true, the user must unlock the device PIN gate before each exchange. */
    val enforcePinLock: Boolean
        get() = restrictions?.getBoolean(KEY_ENFORCE_PIN_LOCK, false) ?: false

    /** True if ANY managed restriction is active (device is MDM-enrolled). */
    val isManagedDevice: Boolean
        get() = restrictions?.isEmpty == false
}
