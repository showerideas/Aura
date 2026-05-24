package com.showerideas.aura.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single entry in the local exchange audit log.
 *
 * Captures a privacy-preserving record of every completed or failed exchange
 * attempt.  No plaintext names or profile data are stored — only the
 * [peerIdentityKeyHash] (SHA-256 of the peer's public key, Base64-encoded)
 * so the log can correlate events to a specific device without revealing PII.
 *
 * The log is stored in the unencrypted Room database and is therefore visible
 * to device-level file system access (rooted devices, ADB backups).  Sensitive
 * fields (names, phone numbers, emails) MUST NOT be added here.
 *
 * Intended uses:
 *  - Surface an exchange history screen so users can review recent activity.
 *  - Detect anomalies (many failed attempts from the same identity key).
 *  - Support eventual export/share-log feature in Settings.
 */
@Entity(tableName = "exchange_audit_log")
data class ExchangeAuditEntry(
    @PrimaryKey val id: String,
    /** Epoch milliseconds when this exchange event occurred. */
    val timestampMs: Long = System.currentTimeMillis(),
    /**
     * SHA-256 hash of the peer's identity public key, Base64-encoded.
     * Matches [Contact.identityKeyHash] for successful exchanges.
     * Null if the exchange failed before the peer's identity was established.
     */
    val peerIdentityKeyHash: String? = null,
    /**
     * Direction of the exchange from this device's perspective.
     * "SENT"     — we initiated and sent our profile.
     * "RECEIVED" — we received a peer's profile.
     * "BOTH"     — bidirectional (normal P2P exchange).
     */
    val direction: String = "BOTH",
    /**
     * Outcome of the exchange attempt.
     * "SUCCESS"  — profile exchanged and saved.
     * "FAILED"   — terminated with an error.
     * "BLOCKED"  — rejected because the peer is on the blocklist.
     * "SPOOF"    — liveness check or identity rotation detected.
     * "TIMEOUT"  — session timed out before completion.
     */
    val outcome: String,
    /**
     * Short machine-readable error code for FAILED/SPOOF outcomes.
     * Null for SUCCESS/BLOCKED/TIMEOUT.
     * Examples: "MITM_DETECTED", "KEY_ROTATION", "LIVENESS_FAIL",
     *           "PAYLOAD_INVALID", "CRYPTO_ERROR".
     */
    val errorCode: String? = null,
    /**
     * Exchange channel used. Use the [CHANNEL_*][companion] constants.
     * "NEARBY"     — Nearby Connections (BLE + Wi-Fi P2P, transport auto-selected).
     * "NFC"        — tap-to-pair NFC key bootstrap followed by Nearby.
     * "QR"         — QR code scan + relay POST (stub path).
     * "ROOM_HOST"  — multi-peer room session, this device was the host.
     * "ROOM_GUEST" — multi-peer room session, this device was a guest.
     */
    val channel: String = CHANNEL_NEARBY
) {
    companion object {
        // Outcome constants — use these rather than raw strings
        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_FAILED  = "FAILED"
        const val OUTCOME_BLOCKED = "BLOCKED"
        const val OUTCOME_SPOOF   = "SPOOF"
        const val OUTCOME_TIMEOUT = "TIMEOUT"

        // Direction constants
        const val DIR_SENT     = "SENT"
        const val DIR_RECEIVED = "RECEIVED"
        const val DIR_BOTH     = "BOTH"

        // Error code constants
        const val ERR_MITM_DETECTED   = "MITM_DETECTED"
        const val ERR_KEY_ROTATION    = "KEY_ROTATION"
        const val ERR_LIVENESS_FAIL   = "LIVENESS_FAIL"
        const val ERR_PAYLOAD_INVALID = "PAYLOAD_INVALID"
        const val ERR_CRYPTO_ERROR    = "CRYPTO_ERROR"
        const val ERR_SAS_MISMATCH    = "SAS_MISMATCH"

        // Channel constants — use these rather than raw strings
        const val CHANNEL_NEARBY     = "NEARBY"
        const val CHANNEL_NFC        = "NFC"
        const val CHANNEL_QR         = "QR"
        const val CHANNEL_ROOM_HOST  = "ROOM_HOST"
        const val CHANNEL_ROOM_GUEST = "ROOM_GUEST"
    }
}
