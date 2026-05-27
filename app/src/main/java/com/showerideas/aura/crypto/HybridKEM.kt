package com.showerideas.aura.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMEncapsulator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMDecapsulator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Post-quantum hybrid KEM: ML-KEM-768 + X25519.
 *
 * Implements the standard hybrid KEM construction that combines a classical
 * elliptic-curve Diffie-Hellman (X25519) with the NIST post-quantum KEM
 * ML-KEM-768 (FIPS 203). The combined shared secret is computed as:
 *
 *   sharedSecret = HKDF-SHA256(IKM = x25519_ss || mlkem_ss, info = HYBRID_INFO)
 *
 * Security rationale: an adversary must break BOTH X25519 and ML-KEM-768 to
 * recover the shared secret. X25519 provides current-day forward secrecy;
 * ML-KEM-768 provides post-quantum harvesting resistance ("store now, decrypt
 * later" attack mitigation).
 *
 * Wire protocol v6 byte layout
 * Public key  : [0x06 version][x25519_pub(32)][mlkem768_pub(1184)]  → 1217 bytes
 * Ciphertext  : [x25519_ephemeral_pub(32)][mlkem768_ct(1088)]        → 1120 bytes
 * Shared secret: 32 bytes (HKDF output)
 *
 * Requires BouncyCastle:
 *   org.bouncycastle:bcprov-jdk18on:1.78.1
 *   org.bouncycastle:bcpqc-jdk18on:1.78.1
 */
object HybridKEM {

    /** Wire protocol version byte for hybrid KEM frames. */
    const val PROTOCOL_VERSION: Byte = 0x06

    /** HKDF context label — changes with each protocol version bump. */
    private const val HYBRID_INFO = "AURA-v6-hybrid-kem"

    private val rng = SecureRandom()

    // Public API

    /**
     * Generate a new hybrid key pair for one side of the key exchange.
     * The public key bytes can be sent to the remote peer.
     */
    fun generateKeyPair(): HybridKeyPair {
        val x25519Pair = generateX25519KeyPair()
        val mlkemPair  = generateMLKEMKeyPair()
        return HybridKeyPair(
            x25519Private = x25519Pair.first,
            x25519Public  = x25519Pair.second,
            mlkemPrivate  = mlkemPair.first,
            mlkemPublic   = mlkemPair.second,
        )
    }

    /**
     * Encapsulate (initiator side) — given the responder's public key, produce
     * a ciphertext and the shared secret that the responder can reproduce with
     * [decapsulate].
     *
     * @param recipientPublicKeyBytes Encoded public key from [HybridKeyPair.encodedPublicKey].
     * @return [EncapsulationResult] containing ciphertext and 32-byte shared secret.
     * @throws IllegalArgumentException if the public key bytes are malformed.
     */
    fun encapsulate(recipientPublicKeyBytes: ByteArray): EncapsulationResult {
        require(recipientPublicKeyBytes.size == PUBLIC_KEY_BYTES) {
            "Expected $PUBLIC_KEY_BYTES bytes for hybrid public key, got ${recipientPublicKeyBytes.size}"
        }
        require(recipientPublicKeyBytes[0] == PROTOCOL_VERSION) {
            "Unsupported hybrid KEM version byte: 0x%02x".format(recipientPublicKeyBytes[0])
        }

        // Decode recipient keys
        val x25519RecipPub = X25519PublicKeyParameters(
            recipientPublicKeyBytes, X25519_VERSION_OFFSET
        )
        val mlkemRecipPub  = MLKEMPublicKeyParameters(
            MLKEMParameters.ml_kem_768,
            recipientPublicKeyBytes.copyOfRange(
                X25519_VERSION_OFFSET + X25519_PUB_BYTES,
                X25519_VERSION_OFFSET + X25519_PUB_BYTES + MLKEM_PUB_BYTES
            )
        )

        // X25519 ephemeral key exchange
        val (x25519EphPriv, x25519EphPub) = generateX25519KeyPair()
        val x25519SharedSecret = ByteArray(32)
        val agreement = X25519Agreement().apply { init(x25519EphPriv) }
        agreement.calculateAgreement(x25519RecipPub, x25519SharedSecret, 0)

        // ML-KEM-768 encapsulation
        val encapsulator = MLKEMEncapsulator(MLKEMParameters.ml_kem_768, rng)
        encapsulator.init(mlkemRecipPub)
        val mlkemCiphertext    = ByteArray(MLKEM_CT_BYTES)
        val mlkemSharedSecret  = encapsulator.encapsulate(mlkemCiphertext, 0, mlkemCiphertext.size)

        // Combine ciphertext: [x25519_ephemeral_pub(32) || mlkem_ct(1088)]
        val ciphertext = ByteArray(CIPHERTEXT_BYTES).also { ct ->
            x25519EphPub.encode(ct, 0)
            mlkemCiphertext.copyInto(ct, X25519_PUB_BYTES)
        }

        // Combine shared secrets with HKDF
        val sharedSecret = hkdfCombine(x25519SharedSecret, mlkemSharedSecret)

        return EncapsulationResult(ciphertext, sharedSecret)
    }

    /**
     * Decapsulate (responder side) — recover the same shared secret the
     * initiator computed during [encapsulate].
     *
     * @param ciphertextBytes Ciphertext from [EncapsulationResult.ciphertext].
     * @param keyPair         This device's key pair generated before the exchange.
     * @return 32-byte shared secret matching the initiator's.
     * @throws IllegalArgumentException if ciphertext length is wrong.
     */
    fun decapsulate(ciphertextBytes: ByteArray, keyPair: HybridKeyPair): ByteArray {
        require(ciphertextBytes.size == CIPHERTEXT_BYTES) {
            "Expected $CIPHERTEXT_BYTES bytes for hybrid KEM ciphertext, got ${ciphertextBytes.size}"
        }

        // Decode X25519 ephemeral public key from ciphertext
        val x25519EphPub = X25519PublicKeyParameters(ciphertextBytes, 0)

        // X25519 ECDH
        val x25519SharedSecret = ByteArray(32)
        X25519Agreement().apply { init(keyPair.x25519Private) }
            .calculateAgreement(x25519EphPub, x25519SharedSecret, 0)

        // ML-KEM-768 decapsulation
        val mlkemCiphertext = ciphertextBytes.copyOfRange(X25519_PUB_BYTES, CIPHERTEXT_BYTES)
        val decapsulator = MLKEMDecapsulator(MLKEMParameters.ml_kem_768)
        decapsulator.init(keyPair.mlkemPrivate)
        val mlkemSharedSecret = decapsulator.decapsulate(mlkemCiphertext, 0, mlkemCiphertext.size)

        return hkdfCombine(x25519SharedSecret, mlkemSharedSecret)
    }

    // Key serialisation helpers

    /**
     * Encode a hybrid public key as bytes for transmission.
     * Format: [0x06 version][x25519_pub(32)][mlkem768_pub(1184)] → 1217 bytes.
     */
    fun encodePublicKey(keyPair: HybridKeyPair): ByteArray =
        ByteArray(PUBLIC_KEY_BYTES).also { buf ->
            buf[0] = PROTOCOL_VERSION
            keyPair.x25519Public.encode(buf, X25519_VERSION_OFFSET)
            keyPair.mlkemPublic.encoded.copyInto(buf, X25519_VERSION_OFFSET + X25519_PUB_BYTES)
        }

    // Wire sizes (public — used by serialisation layer)

    const val X25519_PUB_BYTES  = 32
    const val MLKEM_PUB_BYTES   = 1184      // ML-KEM-768 public key
    const val MLKEM_CT_BYTES    = 1088      // ML-KEM-768 ciphertext
    const val CIPHERTEXT_BYTES  = X25519_PUB_BYTES + MLKEM_CT_BYTES    // 1120
    const val PUBLIC_KEY_BYTES  = 1 + X25519_PUB_BYTES + MLKEM_PUB_BYTES // 1217

    private const val X25519_VERSION_OFFSET = 1  // skip version byte

    // Private helpers

    private fun generateX25519KeyPair(): Pair<X25519PrivateKeyParameters, X25519PublicKeyParameters> {
        val gen = X25519KeyPairGenerator().also { it.init(X25519KeyGenerationParameters(rng)) }
        val kp  = gen.generateKeyPair()
        return kp.private as X25519PrivateKeyParameters to kp.public as X25519PublicKeyParameters
    }

    private fun generateMLKEMKeyPair(): Pair<MLKEMPrivateKeyParameters, MLKEMPublicKeyParameters> {
        val gen = MLKEMKeyPairGenerator().also {
            it.init(MLKEMKeyGenerationParameters(rng, MLKEMParameters.ml_kem_768))
        }
        val kp = gen.generateKeyPair()
        return kp.private as MLKEMPrivateKeyParameters to kp.public as MLKEMPublicKeyParameters
    }

    /**
     * HKDF-SHA256: extract + expand.
     * IKM  = x25519SharedSecret || mlkemSharedSecret
     * salt = all-zeros (both inputs are uniformly random; salt is optional)
     * info = HYBRID_INFO UTF-8 bytes
     * OKM  = 32 bytes
     */
    private fun hkdfCombine(x25519Ss: ByteArray, mlkemSs: ByteArray): ByteArray {
        val ikm  = x25519Ss + mlkemSs
        val salt = ByteArray(32) // all-zeros
        val info = HYBRID_INFO.toByteArray(Charsets.UTF_8)

        // Extract: PRK = HMAC-SHA256(salt, IKM)
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }

        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01) — 32 bytes is one block
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(info)
            update(0x01.toByte())
            doFinal()
        }
    }
}

// Data classes

/**
 * A hybrid key pair for the AURA ML-KEM-768 + X25519 construction.
 * @property encodedPublicKey Wire-format public key bytes (1217 bytes) for transmission.
 */
data class HybridKeyPair(
    val x25519Private : X25519PrivateKeyParameters,
    val x25519Public  : X25519PublicKeyParameters,
    val mlkemPrivate  : MLKEMPrivateKeyParameters,
    val mlkemPublic   : MLKEMPublicKeyParameters,
) {
    val encodedPublicKey: ByteArray get() = HybridKEM.encodePublicKey(this)
}

/**
 * Result of a successful [HybridKEM.encapsulate] call.
 * @property ciphertext   1120-byte ciphertext to send to the responder.
 * @property sharedSecret 32-byte shared secret used to derive session keys.
 */
data class EncapsulationResult(
    val ciphertext   : ByteArray,
    val sharedSecret : ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is EncapsulationResult &&
        ciphertext.contentEquals(other.ciphertext) &&
        sharedSecret.contentEquals(other.sharedSecret)

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + sharedSecret.contentHashCode()
}
