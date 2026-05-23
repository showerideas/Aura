package com.showerideas.aura.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PR-14: a Nearby Connections endpoint ID that the user has chosen to
 * permanently block. When the [com.showerideas.aura.service.NearbyExchangeService]
 * sees an incoming connection initiation from an endpoint in this table,
 * it rejects the connection before any handshake.
 *
 * Endpoint IDs themselves are short-lived Nearby identifiers, so this
 * blocklist is most useful in combination with PR-13's identity registry —
 * a future PR can promote this to {identityKeyHash -> note} once we're
 * confident the identity key is the right stable anchor.
 */
@Entity(tableName = "blocked_endpoints")
data class BlockedEndpoint(
    @PrimaryKey val endpointId: String,
    val blockedAt: Long = System.currentTimeMillis(),
    /** Optional free-form note the user attaches at block time. */
    val note: String = "",
    /**
     * FIX-5: SHA-256 hash of the peer's identity public key (Base64-encoded).
     * Nullable for backward compat with existing rows (MIGRATION_3_4 adds
     * the column with DEFAULT NULL). When non-null, the blocklist check in
     * [com.showerideas.aura.service.NearbyExchangeService] uses this hash
     * to reject the device even after it reconnects with a new endpoint ID.
     */
    val identityKeyHash: String? = null
)
