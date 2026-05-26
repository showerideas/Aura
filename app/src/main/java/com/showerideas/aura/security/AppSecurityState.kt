package com.showerideas.aura.security

import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 65 — Application-wide security state singleton.
 *
 * Single source of truth for the current [AuraSecurityProfile]. All services and
 * ViewModels inject this and read [profile] to determine transport availability,
 * SAS requirements, and gesture attempt limits.
 *
 * ## Lifecycle
 * - Initialized in [AuraApplication.onCreate] via [AdvancedProtectionIntegration.applyHardening]
 * - Re-evaluated on [ACTION_APPLICATION_RESTRICTIONS_CHANGED] broadcast (MDM policy change)
 * - Re-evaluated when Advanced Protection state changes (WorkManager one-time task)
 *
 * ## Usage in services
 * ```kotlin
 * @Inject lateinit var securityState: AppSecurityState
 *
 * fun onStartExchange() {
 *     val profile = securityState.profile.value
 *     if (!profile.qrRelayEnabled) skipQrRelay()
 *     if (profile.sasRequired) enforceSas()
 * }
 * ```
 */
@Singleton
class AppSecurityState @Inject constructor(
    private val apIntegration: AdvancedProtectionIntegration
) {
    private val _profile = MutableStateFlow(AuraSecurityProfile.DEFAULT)

    /** Current merged security profile. Observe this StateFlow for reactive updates. */
    val profile: StateFlow<AuraSecurityProfile> = _profile.asStateFlow()

    /**
     * Initialize or refresh the security profile.
     * Call from [AuraApplication.onCreate] and from the MDM policy change WorkManager task.
     *
     * @param enterpriseMaxAttempts MDM max gesture attempts (null = not managed).
     * @param enterpriseRequiresSas MDM SAS enforcement (null = not managed).
     * @param enterpriseAllowedTransports MDM transport whitelist (empty = all allowed).
     */
    fun refresh(
        enterpriseMaxAttempts: Int? = null,
        enterpriseRequiresSas: Boolean? = null,
        enterpriseAllowedTransports: Set<TransportType> = emptySet(),
    ) {
        val newProfile = apIntegration.applyHardening(
            enterpriseMaxAttempts = enterpriseMaxAttempts,
            enterpriseRequiresSas = enterpriseRequiresSas,
            enterpriseAllowedTransports = enterpriseAllowedTransports,
        )
        _profile.value = newProfile
        Timber.i("AppSecurityState: refreshed — ap=${newProfile.advancedProtectionActive} constraints=${newProfile.hasActiveConstraints}")
    }

    /**
     * True when a given [TransportType] is currently permitted.
     * When [AuraSecurityProfile.allowedTransports] is empty, all transports are permitted.
     */
    fun isTransportAllowed(transport: TransportType): Boolean {
        val p = _profile.value
        if (p.allowedTransports.isEmpty()) {
            // No whitelist — check specific flags
            return when (transport) {
                TransportType.QR_RELAY -> p.qrRelayEnabled
                TransportType.LORA -> p.loraEnabled
                else -> true
            }
        }
        return transport in p.allowedTransports
    }
}
