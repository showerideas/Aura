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
 * repository that backs the TOFU (Trust On First Use) endpoint registry.
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
     * sealed result so callers can distinguish three states:
     * - [Found]    — record exists and key decoded successfully.
     * - [NotFound] — endpoint is genuinely new (TOFU first-use path).
     * - [Corrupt]  — record exists but key bytes are malformed.
     *
     * Prior to this change [getIdentityKey] returned null for both NotFound
     * and Corrupt. The service interpreted null as first-use, meaning a
     * corrupt DB row silently caused the attacker's new key to be trusted
     * and persisted — a TOFU bypass.
     */
    sealed class IdentityKeyResult {
        data class Found(val key: PublicKey) : IdentityKeyResult()
        object NotFound : IdentityKeyResult()
        data class Corrupt(val endpointId: String, val cause: Exception) : IdentityKeyResult()
    }

    /**
     * Return an [IdentityKeyResult] for [endpointId]:
     * - [IdentityKeyResult.NotFound] if the endpoint has never been seen.
     * - [IdentityKeyResult.Found] with the decoded key on success.
     * - [IdentityKeyResult.Corrupt] if the stored bytes fail to decode.
     */
    suspend fun getIdentityKey(endpointId: String): IdentityKeyResult {
        val record = knownPeerDao.get(endpointId) ?: return IdentityKeyResult.NotFound
        return try {
            val bytes = Base64.getDecoder().decode(record.identityPublicKeyBase64)
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            IdentityKeyResult.Found(key)
        } catch (e: Exception) {
            IdentityKeyResult.Corrupt(endpointId, e)
        }
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

    /**
     * Retrieve the last-seen profile version for [endpointId], or 0 if unknown.
     *
     * Used immediately before saving an incoming contact to decide whether a
     * "Card updated" Snackbar should be shown ([Contact.profileVersion] > stored version).
     */
    suspend fun getLastSeenProfileVersion(endpointId: String): Int =
        knownPeerDao.get(endpointId)?.lastSeenProfileVersion ?: 0

    /**
     * Persist the most recently received [profileVersion] for [endpointId].
     *
     * Call after every successful exchange so the next exchange can detect bumps.
     * No-op if the endpoint is not yet in the TOFU registry (unknown peer).
     */
    suspend fun updateLastSeenProfileVersion(endpointId: String, profileVersion: Int) =
        knownPeerDao.updateLastSeenProfileVersion(endpointId, profileVersion)

    // -------------------------------------------------------------------------
    // Encoding helpers
    // -------------------------------------------------------------------------

    private fun encodePublicKey(key: PublicKey): String =
        Base64.getEncoder().encodeToString(key.encoded)
}
