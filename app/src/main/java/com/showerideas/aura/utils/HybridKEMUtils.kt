package com.showerideas.aura.utils

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * Phase 8.1 — Hybrid post-quantum key encapsulation mechanism (KEM).
 *
 * ## Construction (hybrid for forward secrecy + quantum resistance)
 * ```
 * sharedSecret = HKDF-SHA256(
 *     IKM  = ecdh_shared_secret || kem_shared_secret,
 *     info = "AURA-v6-hybrid-kem"
 * )
 * ```
 *
 * ## Current state
 * This class provides the scaffolding for the hybrid KEM with full ECDH support
 * (P-256) and a KEM placeholder pending BouncyCastle 1.78+ / ML-KEM availability
 * on Android. The wire protocol v6 HELLO handshake negotiation is added in
 * NearbyExchangeService. Once ML-KEM is integrated, replace `kemEncapsulate()`
 * and `kemDecapsulate()` with real ML-KEM-768 operations verified against
 * NIST FIPS 203 KAT vectors.
 *
 * ## Backwards compatibility
 * Peers advertising wire protocol < v6 receive v5 ECDH-only key agreement.
 * The negotiation happens in the HELLO frame of the Nearby exchange handshake.
 *
 * @see docs/SECURITY.md for the hybrid construction rationale and algorithm choices.
 */
object HybridKEMUtils {

    const val WIRE_PROTOCOL_VERSION = 6
    const val WIRE_PROTOCOL_V5 = 5

    data class HybridKeyPair(
        val ecdhKeyPair: KeyPair,
        /** Serialized public key bytes for the KEM component (placeholder = ECDH public key bytes) */
        val kemPublicKeyBytes: ByteArray
    )

    data class EncapsulateResult(
        /** Ciphertext to send to peer */
        val kemCiphertext: ByteArray,
        /** Combined hybrid shared secret (ECDH || KEM) */
        val sharedSecret: ByteArray
    )

    /**
     * Generate a hybrid key pair for wire protocol v6.
     * Returns a P-256 ECDH key pair + KEM public key bytes for the HELLO frame.
     */
    fun generateHybridKEMKeyPair(): HybridKeyPair {
        val ecGen = KeyPairGenerator.getInstance("EC")
        ecGen.initialize(ECGenParameterSpec("secp256r1"))
        val ecdhKp = ecGen.generateKeyPair()
        // Placeholder: KEM uses ECDH public key bytes until ML-KEM is available
        val kemPubKeyBytes = ecdhKp.public.encoded
        return HybridKeyPair(ecdhKp, kemPubKeyBytes)
    }

    /**
     * Encapsulate a shared secret for the peer's hybrid key.
     * In the full ML-KEM implementation, this produces a ciphertext and shared secret.
     * Currently: derives ECDH shared secret and uses it as both components.
     *
     * @param peerEcdhPublicKey  Peer's P-256 public key
     * @param ourKeyPair         Our hybrid key pair from [generateHybridKEMKeyPair]
     * @return [EncapsulateResult] with ciphertext to send + derived shared secret
     */
    fun encapsulate(peerEcdhPublicKey: java.security.PublicKey, ourKeyPair: HybridKeyPair): EncapsulateResult {
        val ecdhSecret = computeECDH(ourKeyPair.ecdhKeyPair.private, peerEcdhPublicKey)
        // KEM placeholder: reuse ECDH bytes (replace with ML-KEM.encapsulate() when available)
        val kemSecret = ecdhSecret.copyOf()
        val combined = ecdhSecret + kemSecret
        val derived = deriveWithHKDF(combined, "AURA-v6-hybrid-kem".toByteArray())
        return EncapsulateResult(
            kemCiphertext = ourKeyPair.kemPublicKeyBytes, // placeholder ciphertext
            sharedSecret  = derived
        )
    }

    /**
     * Decapsulate the peer's KEM ciphertext to recover the shared secret.
     */
    fun decapsulate(
        kemCiphertext: ByteArray,
        ourHybridKeyPair: HybridKeyPair,
        peerEcdhPublicKey: java.security.PublicKey
    ): ByteArray {
        val ecdhSecret = computeECDH(ourHybridKeyPair.ecdhKeyPair.private, peerEcdhPublicKey)
        val kemSecret  = ecdhSecret.copyOf() // placeholder
        val combined   = ecdhSecret + kemSecret
        return deriveWithHKDF(combined, "AURA-v6-hybrid-kem".toByteArray())
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun computeECDH(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    private fun deriveWithHKDF(ikm: ByteArray, info: ByteArray): ByteArray {
        // HKDF-SHA256 extract-then-expand (simple implementation)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        // Extract
        mac.init(javax.crypto.spec.SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand (1 block = 32 bytes, sufficient for AES-256 key)
        mac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        return mac.doFinal().copyOf(32)
    }
}
