package com.showerideas.aura.data

import com.showerideas.aura.data.local.BlockedEndpointDao
import com.showerideas.aura.model.BlockedEndpoint
import com.showerideas.aura.utils.CryptoUtils
import kotlinx.coroutines.flow.Flow
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR-14: thin wrapper around [BlockedEndpointDao] so the service and UI
 * layers depend on a repository contract instead of a DAO directly.
 *
 * FIX-5: blocklist now keys on stable identity-key hash in addition to
 * the ephemeral Nearby endpoint ID. A blocked device that reconnects
 * with a new endpoint ID is still rejected via [isBlockedByKeyHash].
 */
@Singleton
class BlocklistRepository @Inject constructor(
    private val dao: BlockedEndpointDao
) {
    fun observeAll(): Flow<List<BlockedEndpoint>> = dao.observeAll()

    /** Legacy block by endpoint ID only (kept for backward compat). */
    suspend fun block(endpointId: String, note: String = "") =
        dao.block(BlockedEndpoint(endpointId = endpointId, note = note))

    /**
     * FIX-5: block by both endpoint ID and stable identity key hash.
     * Prevents the device from bypassing the block on reconnect with a
     * fresh Nearby endpoint ID.
     */
    suspend fun blockByIdentity(endpointId: String, identityKeyHash: String?, note: String = "") =
        dao.block(BlockedEndpoint(
            endpointId = endpointId,
            identityKeyHash = identityKeyHash,
            note = note
        ))

    suspend fun unblock(endpoint: BlockedEndpoint) = dao.unblock(endpoint)

    suspend fun isBlocked(endpointId: String): Boolean = dao.isBlocked(endpointId)

    /**
     * FIX-5: check whether a public key's hash appears in the blocklist.
     * Use this after decoding the peer's identity key to catch reconnects
     * that arrive with a new ephemeral endpoint ID.
     */
    suspend fun isBlockedByKeyHash(publicKey: PublicKey): Boolean =
        dao.isBlockedByKeyHash(CryptoUtils.identityKeyHash(publicKey))

    /**
     * FIX-5: convenience overload that accepts a pre-computed hash string
     * (e.g. from a Contact's [com.showerideas.aura.model.Contact.identityKeyHash] field).
     */
    suspend fun isBlockedByKeyHash(identityKeyHash: String): Boolean =
        dao.isBlockedByKeyHash(identityKeyHash)
}
