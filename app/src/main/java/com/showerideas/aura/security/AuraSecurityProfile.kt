package com.showerideas.aura.security

/**
 * Task 65 — Merged security profile produced by [AdvancedProtectionIntegration].
 *
 * Priority order (highest wins for each field):
 *   Advanced Protection overrides → MDM policy → user preferences
 *
 * This is the single source of truth for runtime security constraints.
 * All services and ViewModels read from [AppSecurityState] which wraps this.
 *
 * See: [blog.google/security/whats-new-in-android-security-privacy-2026/]
 */
data class AuraSecurityProfile(
    /** Maximum gesture authentication attempts before lock-out. */
    val maxGestureAttempts: Int = 3,
    /** When true, SAS verification is required for every exchange. Cannot be disabled by user. */
    val sasRequired: Boolean = false,
    /** When false, QR relay transport is blocked (AP removes server-assisted metadata). */
    val qrRelayEnabled: Boolean = true,
    /** When false, LoRa transport is blocked (AP removes unverified underlay channel). */
    val loraEnabled: Boolean = true,
    /** Whitelist of allowed transports. Empty set = all transports allowed. */
    val allowedTransports: Set<TransportType> = emptySet(),
    /** True when Android 16 Advanced Protection was the source of any constraint in this profile. */
    val advancedProtectionActive: Boolean = false,
) {
    /**
     * True when this profile has any restriction more stringent than the default.
     * Used to decide whether to show the security-status banner.
     */
    val hasActiveConstraints: Boolean
        get() = advancedProtectionActive ||
                !qrRelayEnabled ||
                !loraEnabled ||
                sasRequired ||
                maxGestureAttempts < 3 ||
                allowedTransports.isNotEmpty()

    /** Effective max gesture attempts (never negative). */
    val effectiveMaxAttempts: Int get() = maxGestureAttempts.coerceAtLeast(1)

    companion object {
        /** Permissive baseline — no constraints beyond AURA defaults. */
        val DEFAULT = AuraSecurityProfile()

        /**
         * Hardened profile applied when Advanced Protection is active and no MDM policy
         * specifies stricter values. MDM can override with even stricter constraints.
         */
        val ADVANCED_PROTECTION_BASELINE = AuraSecurityProfile(
            maxGestureAttempts = 2,
            sasRequired = true,
            qrRelayEnabled = false,
            loraEnabled = false,
            advancedProtectionActive = true,
        )
    }
}

/**
 * Transport types that can appear in [AuraSecurityProfile.allowedTransports].
 * When the whitelist is non-empty, only listed transports are active.
 */
enum class TransportType {
    NFC,
    BLE,
    WIFI_DIRECT,
    NEARBY,
    QR_RELAY,
    LORA,
    UWB,
}
