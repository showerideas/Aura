package com.showerideas.aura.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * a Nearby Connections endpoint ID that the user has chosen to
 * permanently block. When the [com.showerideas.aura.service.NearbyExchangeService]
 * sees an incoming connection initiation from an endpoint in this table,
 * it rejects the connection before any handshake.
 *
 * Endpoint IDs themselves are short-lived Nearby identifiers, so 
 * added an [identityKeyHash] column so blocks survive reconnects with a
 * fresh ephemeral endpoint ID. Both the endpoint ID and the identity hash
 * are checked by [com.showerideas.aura.data.BlocklistRepository].
 */
@Entity(tableName = "blocked_endpoints")
data class BlockedEndpoint(
    @PrimaryKey val endpointId: String,
    val blockedAt: Long = System.currentTimeMillis(),
    /** Optional free-form note the user attaches at block time. */
    val note: String = "",
    /**
     * SHA-256 hash of the peer's identity public key (Base64-encoded).
     * Nullable for backward compat with existing rows (MIGRATION_3_4 adds
     * the column with DEFAULT NULL). When non-null, the blocklist check in
     * [com.showerideas.aura.service.NearbyExchangeService] uses this hash
     * to reject the device even after it reconnects with a new endpoint ID.
     */
    val identityKeyHash: String? = null
)
