package com.showerideas.aura.security

/**
 * Task 46 — Hardware security level of a key stored in the Android Keystore.
 *
 * Matches the security levels returned by [android.security.keystore.KeyInfo.securityLevel]:
 * - [STRONGBOX]: Dedicated secure microcontroller (Titan M, SE050, etc.) isolated from SoC.
 * - [TEE]: Trusted Execution Environment embedded in the main SoC. Secure but not physically isolated.
 * - [SOFTWARE]: Key material stored in normal process memory with OS-level protection only.
 *
 * AURA logs this level to [ExchangeAuditLog.keySecurityLevel] and shows a warning banner
 * in [SecurityStatusFragment] when the identity key falls back to [SOFTWARE].
 *
 * See: [source.android.com/docs/security/best-practices/hardware]
 */
enum class KeySecurityLevel {
    /** Dedicated secure microcontroller — highest physical tamper resistance. */
    STRONGBOX,
    /** ARM TrustZone TEE in the main SoC — current minimum bar for production keys. */
    TEE,
    /** Software key store — acceptable for testing only; warns user in production. */
    SOFTWARE,
    /** Security level could not be determined (key does not exist or error reading KeyInfo). */
    UNKNOWN;

    /** True when the key is in hardware (StrongBox or TEE). */
    val isHardwareBacked: Boolean get() = this == STRONGBOX || this == TEE

    companion object {
        /**
         * Map an [android.security.keystore.KeyInfo.securityLevel] integer to [KeySecurityLevel].
         * Uses raw int constants to avoid API-level minimum constraints:
         *   0 = SOFTWARE, 1 = TEE, 2 = STRONGBOX (SECURITY_LEVEL_* constants in KeyProperties API 31+).
         */
        fun fromInt(level: Int): KeySecurityLevel = when (level) {
            2    -> STRONGBOX
            1    -> TEE
            0    -> SOFTWARE
            else -> UNKNOWN
        }
    }
}
