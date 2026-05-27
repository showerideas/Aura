package com.showerideas.aura.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * persisted TOFU (Trust On First Use) record for a remote endpoint.
 *
 * The previous implementation kept the endpoint-identity-key registry in
 * [com.showerideas.aura.service.NearbyExchangeService]'s companion-object
 * memory. When the service was killed and restarted the registry was empty,
 * meaning an attacker could force a restart to bypass endpoint-substitution
 * detection entirely.
 *
 * This entity persists the mapping to Room so the TOFU check survives across
 * service restarts, process deaths, and device reboots.
 *
 * The identity key is stored Base64-encoded (X.509 SubjectPublicKeyInfo bytes)
 * rather than as a raw blob because TEXT columns make manual debugging easier
 * and the Base64 overhead is negligible for EC public keys (~124 chars).
 */
@Entity(tableName = "known_peers")
data class KnownPeer(
    @PrimaryKey val endpointId: String,
    /** Base64-encoded X.509 SubjectPublicKeyInfo bytes of the peer's device identity public key. */
    val identityPublicKeyBase64: String,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    /**
     * Raw DER bytes of a key-rotation certificate — the peer's new identity
     * public key signed by their old identity private key. Null if the peer
     * has never performed a key rotation.
     *
     * Added in DB v7 (MIGRATION_6_7). Room stores ByteArray? as BLOB natively.
     * Verified by [IdentityRotationDetector] on next exchange.
     */
    @ColumnInfo(name = "rotation_certificate")
    val rotationCertificate: ByteArray? = null,
    /**
     * The last [Profile.version] value received from this peer during an exchange.
     * On next exchange, if the incoming version is higher, a "Card updated" banner
     * is shown via [ContactMergeBottomSheet].
     * Added in DB v8 (MIGRATION_7_8). DEFAULT 0 so existing rows show a banner
     * on first exchange after upgrade (correct — user has never seen the current version).
     */
    @ColumnInfo(name = "last_seen_profile_version")
    val lastSeenProfileVersion: Int = 0
)
