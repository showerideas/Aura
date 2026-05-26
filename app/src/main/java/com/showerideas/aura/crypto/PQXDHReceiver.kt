package com.showerideas.aura.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMDecapsulator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import timber.log.Timber
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Task 47 — PQXDH receiver-side key decapsulation.
 *
 * Symmetric counterpart to [PQXDHSender.encapsulate]. Given a received [PQXDHMessage]
 * and the recipient's local key material, reproduces the same [masterSecret] that the
 * sender computed.
 *
 * ## Protocol (Signal PQXDH specification §4 — receiver side)
 * Let IK_B = recipient identity X25519 private key
 * Let SPK_B = signed prekey X25519 private key (from [SignedPreKeyStore])
 * Let OPK_B = one-time prekey X25519 + ML-KEM-768 private keys (from [OneTimePreKeyStore])
 *
 * DH1 = X25519(SPK_B_priv, IK_A_pub)   — signed prekey × sender identity
 * DH2 = X25519(IK_B_priv,  EK_A_pub)   — identity × sender ephemeral
 * DH3 = X25519(SPK_B_priv, EK_A_pub)   — signed prekey × sender ephemeral
 * DH4 = X25519(OPK_B_priv_x25519, EK_A_pub)   — one-time × sender ephemeral (or zeros)
 * KEM1 = ML-KEM-768-Decapsulate(OPK_B_priv_mlkem, kemCt) → kemSharedSecret (or zeros)
 *
 * masterSecret = HKDF-SHA256(DH1 || DH2 || DH3 || DH4 || kemSharedSecret, info="aura-pqxdh-v1")
 *
 * After decapsulation, [OPK_B.destroy] is called to zero-fill the one-time private keys.
 *
 * ## Signed prekey verification
 * Before using [SPK_B], [decapsulate] verifies the [SignedPreKey.signature] against
 * [message.senderIdentityPublicKey]. If verification fails, the operation aborts.
 *
 * See: [signal.org — PQXDH specification]
 */
object PQXDHReceiver {

    private const val HKDF_INFO      = "aura-pqxdh-v1"
    private const val MASTER_KEY_LEN = 32

    /**
     * Decapsulate the shared master secret from a received [PQXDHMessage].
     *
     * @param message           Received [PQXDHMessage] from sender.
     * @param recipientIdentityPriv Recipient's identity X25519 private key (32 bytes).
     * @param signedPreKey      The [SignedPreKey] matching [message.signedPreKeyId].
     * @param oneTimePreKey     The [OneTimePreKey] matching [message.oneTimePreKeyId], or null.
     * @return 32-byte master secret, or null if signed prekey signature verification failed.
     */
    fun decapsulate(
        message: PQXDHMessage,
        recipientIdentityPriv: ByteArray,
        signedPreKey: SignedPreKey,
        oneTimePreKey: OneTimePreKey?,
    ): ByteArray? {
        // Verify signed prekey signature before use
        val sigValid = verifySignedPreKeySignature(
            signedPreKeyPublic  = signedPreKey.publicKey,
            signature           = signedPreKey.signature,
            senderIdentityPublic = message.senderIdentityPublicKey,
        )
        if (!sigValid) {
            Timber.e("PQXDH receiver: signed prekey signature verification failed — aborting")
            return null
        }

        val ikBPriv  = X25519PrivateKeyParameters(recipientIdentityPriv)
        val spkBPriv = X25519PrivateKeyParameters(signedPreKey.privateKey)
        val ikAPub   = X25519PublicKeyParameters(message.senderIdentityPublicKey.takeLast(32))
        val ekAPub   = X25519PublicKeyParameters(message.senderEphemeralPublicKey)

        // DH1 = X25519(SPK_B, IK_A)
        val dh1 = x25519(spkBPriv, ikAPub)
        // DH2 = X25519(IK_B,  EK_A)
        val dh2 = x25519(ikBPriv,  ekAPub)
        // DH3 = X25519(SPK_B, EK_A)
        val dh3 = x25519(spkBPriv, ekAPub)

        // DH4 = X25519(OPK_B_x25519, EK_A) or zeros
        val dh4 = if (oneTimePreKey != null) {
            val opkPriv = X25519PrivateKeyParameters(oneTimePreKey.x25519PrivateKey)
            x25519(opkPriv, ekAPub)
        } else {
            ByteArray(32) { 0 }
        }

        // KEM1 = ML-KEM-768 decapsulate or zeros
        val kemSharedSecret = if (oneTimePreKey != null && message.kemCiphertext.isNotEmpty()) {
            mlkem768Decapsulate(oneTimePreKey.mlkem768PrivateKey, message.kemCiphertext)
        } else {
            ByteArray(32) { 0 }
        }

        val ikm          = dh1 + dh2 + dh3 + dh4 + kemSharedSecret
        val masterSecret = hkdfSha256(ikm, HKDF_INFO.toByteArray(), MASTER_KEY_LEN)

        // Zero-fill all intermediate DH outputs
        dh1.fill(0); dh2.fill(0); dh3.fill(0); dh4.fill(0); kemSharedSecret.fill(0)

        // Zero-fill one-time prekey private material immediately after use (PQXDH requirement)
        oneTimePreKey?.destroy()

        Timber.i(
            "PQXDH receiver: decapsulation complete — " +
            "spkId=${signedPreKey.id} otpkId=${oneTimePreKey?.id} hasOtpk=${oneTimePreKey != null}"
        )

        return masterSecret
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun x25519(priv: X25519PrivateKeyParameters, pub: X25519PublicKeyParameters): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(priv)
        val out = ByteArray(32)
        agreement.calculateAgreement(pub, out, 0)
        return out
    }

    private fun mlkem768Decapsulate(privateKeyBytes: ByteArray, ciphertext: ByteArray): ByteArray {
        return runCatching {
            val privKey     = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, privateKeyBytes)
            val decapsulator = MLKEMDecapsulator(MLKEMParameters.ml_kem_768)
            decapsulator.init(privKey)
            decapsulator.decapsulate(ciphertext)
        }.getOrElse { e ->
            Timber.e(e, "PQXDH receiver: ML-KEM-768 decapsulation failed")
            ByteArray(32) { 0 }
        }
    }

    private fun verifySignedPreKeySignature(
        signedPreKeyPublic: ByteArray,
        signature: ByteArray,
        senderIdentityPublic: ByteArray,
    ): Boolean {
        return runCatching {
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val keySpec    = java.security.spec.X509EncodedKeySpec(senderIdentityPublic)
            val publicKey  = keyFactory.generatePublic(keySpec)
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(signedPreKeyPublic)
                verify(signature)
            }
        }.getOrElse { e ->
            Timber.e(e, "PQXDH receiver: signature verification threw exception")
            false
        }
    }

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val salt    = ByteArray(32) { 0 }
        val prk     = hmacSha256(salt, ikm)
        val okm     = ByteArray(length)
        var t       = ByteArray(0)
        var offset  = 0
        var counter = 1
        while (offset < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val toCopy = minOf(t.size, length - offset)
            t.copyInto(okm, offset, 0, toCopy)
            offset += toCopy; counter++
        }
        return okm
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun ByteArray.takeLast(n: Int): ByteArray =
        if (size <= n) this else copyOfRange(size - n, size)
}
