package com.showerideas.aura.utils

import com.showerideas.aura.model.RotationCertificate
import timber.log.Timber
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Identity key rotation for AURA.
 *
 * When a user rotates their identity key:
 * 1. Generate a new P-256 key pair.
 * 2. Sign (oldPublicKeyBytes || newPublicKeyBytes) with old private key → [RotationCertificate].
 * 3. Broadcast the rotation certificate to known peers via the next exchange.
 * 4. Peers verify with [verifyRotationCertificate] and update their TOFU registry.
 */
object IdentityKeyRotator {

    private const val ALGORITHM = "EC"
    private const val CURVE     = "secp256r1"
    private const val SIGNATURE = "SHA256withECDSA"

    /** Generate a new P-256 identity key pair. Returns (privateKey, publicKey). */
    fun generateNewKeyPair(): Pair<PrivateKey, PublicKey> {
        val gen = KeyPairGenerator.getInstance(ALGORITHM).apply {
            initialize(ECGenParameterSpec(CURVE), SecureRandom())
        }
        val kp = gen.generateKeyPair()
        return kp.private to kp.public
    }

    /**
     * Create a rotation certificate proving [newPublicKey] is authorized by [oldPrivateKey].
     */
    fun createRotationCertificate(
        oldPrivateKey : PrivateKey,
        oldPublicKey  : PublicKey,
        newPublicKey  : PublicKey
    ): RotationCertificate {
        val oldPubBytes = oldPublicKey.encoded
        val newPubBytes = newPublicKey.encoded
        val sigInput    = oldPubBytes + newPubBytes
        val sig = Signature.getInstance(SIGNATURE).run {
            initSign(oldPrivateKey, SecureRandom())
            update(sigInput)
            sign()
        }
        Timber.d("IdentityKeyRotator: created rotation cert (oldKey=${oldPubBytes.size}b sig=${sig.size}b)")
        return RotationCertificate(
            oldPublicKeyBytes = oldPubBytes,
            newPublicKeyBytes = newPubBytes,
            signature         = sig
        )
    }

    /**
     * Verify a [RotationCertificate] received from a peer.
     *
     * @param cert              The certificate to verify.
     * @param knownOldPublicKey The public key we have on record for this peer (DER-encoded).
     * @return true if valid; false otherwise.
     */
    fun verifyRotationCertificate(
        cert              : RotationCertificate,
        knownOldPublicKey : ByteArray
    ): Boolean {
        if (!cert.oldPublicKeyBytes.contentEquals(knownOldPublicKey)) {
            Timber.w("IdentityKeyRotator: old key mismatch — rotation rejected")
            return false
        }
        return try {
            val oldPub = KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(cert.oldPublicKeyBytes))
            val sigInput = cert.oldPublicKeyBytes + cert.newPublicKeyBytes
            Signature.getInstance(SIGNATURE).run {
                initVerify(oldPub)
                update(sigInput)
                verify(cert.signature)
            }
        } catch (e: Exception) {
            Timber.e(e, "IdentityKeyRotator: verification failed")
            false
        }
    }
}
