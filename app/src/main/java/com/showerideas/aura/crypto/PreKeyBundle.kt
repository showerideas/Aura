package com.showerideas.aura.crypto

/**
 * Task 47 — PQXDH prekey bundle data types.
 *
 * A [PreKeyBundle] represents one AURA user's public key material needed by a
 * sender to perform an asynchronous post-quantum key exchange (PQXDH) without
 * the recipient being online.
 *
 * ## Bundle structure
 * - [identityPublicKey]     — long-term P-256 identity public key (encoded, 65 bytes uncompressed)
 * - [signedPreKeyPublic]    — X25519 signed prekey public key (32 bytes)
 * - [signedPreKeySignature] — P-256 ECDSA signature over [signedPreKeyPublic] by [identityPublicKey]
 * - [signedPreKeyId]        — stable identifier for the signed prekey (for lookup in sender's cache)
 * - [oneTimePreKeyPublicX25519]  — one-time X25519 prekey public key (32 bytes; null if pool exhausted)
 * - [oneTimePreKeyPublicMLKEM]   — one-time ML-KEM-768 public key (1184 bytes; null if pool exhausted)
 * - [oneTimePreKeyId]            — stable identifier for the one-time prekey (for server-side deletion)
 *
 * A bundle without [oneTimePreKeyPublicX25519] / [oneTimePreKeyPublicMLKEM] still provides
 * forward secrecy via [signedPreKeyPublic] but not per-packet forward secrecy. Sender should
 * retry later to find a populated one-time prekey.
 *
 * See: [signal.org — PQXDH specification]
 * See: [github.com/signalapp/libsignal]
 */
data class PreKeyBundle(
    /** Sender's or recipient's P-256 identity public key (X.509 DER encoded, 91 bytes). */
    val identityPublicKey: ByteArray,
    /** X25519 signed prekey public key (32 bytes raw). */
    val signedPreKeyPublic: ByteArray,
    /** P-256 ECDSA signature of [signedPreKeyPublic] by [identityPublicKey]. */
    val signedPreKeySignature: ByteArray,
    /** Stable signed prekey ID (rotated every 7 days). */
    val signedPreKeyId: Int,
    /** One-time X25519 prekey (32 bytes raw); null if pool empty. */
    val oneTimePreKeyPublicX25519: ByteArray?,
    /** One-time ML-KEM-768 prekey public key (1184 bytes); null if pool empty. */
    val oneTimePreKeyPublicMLKEM: ByteArray?,
    /** One-time prekey ID; null when one-time keys absent. */
    val oneTimePreKeyId: Int?,
) {
    val hasOneTimePreKey: Boolean
        get() = oneTimePreKeyPublicX25519 != null && oneTimePreKeyPublicMLKEM != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreKeyBundle) return false
        return identityPublicKey.contentEquals(other.identityPublicKey) &&
               signedPreKeyPublic.contentEquals(other.signedPreKeyPublic) &&
               signedPreKeySignature.contentEquals(other.signedPreKeySignature) &&
               signedPreKeyId == other.signedPreKeyId &&
               oneTimePreKeyPublicX25519.contentEquals2(other.oneTimePreKeyPublicX25519) &&
               oneTimePreKeyPublicMLKEM.contentEquals2(other.oneTimePreKeyPublicMLKEM) &&
               oneTimePreKeyId == other.oneTimePreKeyId
    }

    override fun hashCode(): Int {
        var result = identityPublicKey.contentHashCode()
        result = 31 * result + signedPreKeyPublic.contentHashCode()
        result = 31 * result + signedPreKeyId
        return result
    }

    private fun ByteArray?.contentEquals2(other: ByteArray?): Boolean =
        if (this == null && other == null) true
        else this?.contentEquals(other ?: return false) ?: false
}

/**
 * One-time prekey keypair stored in [OneTimePreKeyStore].
 * Both classical and PQ components are bundled together — they are used together in PQXDH.
 */
data class OneTimePreKey(
    val id: Int,
    /** X25519 key pair. */
    val x25519PublicKey: ByteArray,
    val x25519PrivateKey: ByteArray,
    /** ML-KEM-768 key pair. */
    val mlkem768PublicKey: ByteArray,
    val mlkem768PrivateKey: ByteArray,
) {
    /** Zero-fill both private keys — call immediately after use. */
    fun destroy() {
        x25519PrivateKey.fill(0)
        mlkem768PrivateKey.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OneTimePreKey) return false
        return id == other.id && x25519PublicKey.contentEquals(other.x25519PublicKey)
    }

    override fun hashCode(): Int = id
}

/**
 * Signed prekey keypair stored in [SignedPreKeyStore].
 */
data class SignedPreKey(
    val id: Int,
    /** X25519 key pair. */
    val publicKey: ByteArray,
    val privateKey: ByteArray,
    /** P-256 ECDSA signature of [publicKey] by the identity key. */
    val signature: ByteArray,
    /** Epoch ms when this key was created. Expires after 7 days. */
    val createdAtMs: Long,
) {
    val expiresAtMs: Long get() = createdAtMs + ROTATION_TTL_MS
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs

    /** Zero-fill private key. */
    fun destroy() = privateKey.fill(0)

    companion object {
        /** Signed prekey rotation interval: 7 days in milliseconds. */
        const val ROTATION_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
