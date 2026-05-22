package com.showerideas.aura.data

import com.showerideas.aura.data.local.KnownPeerDao
import com.showerideas.aura.model.KnownPeer
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FIX-2: repository that backs the TOFU (Trust On First Use) endpoint registry.
 *
 * Converts between [java.security.PublicKey] objects (used by the service's
 * crypto layer) and the Base64-encoded X.509 strings stored in Room.
 *
 * Injected into [com.showerideas.aura.service.NearbyExchangeService] as a
 * replacement for the previous in-memory [peerIdentityRegistry] map.
 */
@Singleton
class KnownPeerRepository @Inject constructor(
    private val knownPeerDao: KnownPeerDao
) {

    /**
     * Return the persisted [PublicKey] for [endpointId], or null if this
     * endpoint has never been seen before.
     */
    suspend fun getIdentityKey(endpointId: String): PublicKey? {
        val record = knownPeerDao.get(endpointId) ?: return null
        return decodePublicKey(record.identityPublicKeyBase64)
    }

    /**
     * Persist the identity key for [endpointId]. On a repeat visit the row
     * is replaced with an updated [KnownPeer.lastSeenAt] timestamp.
     */
    suspend fun upsertIdentityKey(endpointId: String, key: PublicKey) {
        val existing = knownPeerDao.get(endpointId)
        knownPeerDao.upsert(
            KnownPeer(
                endpointId = endpointId,
                identityPublicKeyBase64 = encodePublicKey(key),
                firstSeenAt = existing?.firstSeenAt ?: System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis()
            )
        )
    }

    /** Remove the record for [endpointId] (e.g. user forgets/resets a device). */
    suspend fun delete(endpointId: String) = knownPeerDao.delete(endpointId)

    // -------------------------------------------------------------------------
    // Encoding helpers
    // -------------------------------------------------------------------------

    private fun encodePublicKey(key: PublicKey): String =
        Base64.getEncoder().encodeToString(key.encoded)

    private fun decodePublicKey(base64: String): PublicKey? = try {
        val bytes = Base64.getDecoder().decode(base64)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    } catch (e: Exception) {
        null
    }
}
