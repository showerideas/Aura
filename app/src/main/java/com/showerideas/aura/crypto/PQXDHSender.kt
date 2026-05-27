package com.showerideas.aura.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import timber.log.Timber
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Task 47 — PQXDH sender-side key encapsulation.
 *
 * Implements the PQXDH sender protocol: given a recipient's [PreKeyBundle], computes
 * a shared master secret combining 4 X25519 DH operations and 1 ML-KEM-768 encapsulation.
 *
 * ## Protocol (Signal PQXDH specification §4)
 * Let IK_A = sender identity key, EK_A = ephemeral X25519 key pair (generated fresh)
 * Let IK_B = recipient identity key, SPK_B = recipient signed prekey, OPK_B = one-time prekey
 *
 * DH1 = X25519(IK_A_priv, SPK_B_pub)   — sender identity × recipient signed prekey
 * DH2 = X25519(EK_A_priv, IK_B_pub)    — sender ephemeral × recipient identity
 * DH3 = X25519(EK_A_priv, SPK_B_pub)   — sender ephemeral × recipient signed prekey
 * DH4 = X25519(EK_A_priv, OPK_B_pub_x25519)  — sender ephemeral × one-time prekey (or zeros)
 * KEM1 = ML-KEM-768-Encapsulate(OPK_B_pub_mlkem) → (ct, kemSharedSecret)
 *
 * masterSecret = HKDF-SHA256(
 *   IKM  = DH1 || DH2 || DH3 || DH4 || KEM1.sharedSecret,
 *   info = "aura-pqxdh-v1"
 * )
 *
 * The [PQXDHMessage] sent to the recipient contains:
 *   - sender's encoded X25519 ephemeral public key
 *   - sender's identity public key (for recipient to perform DH1 and DH2)
 *   - ML-KEM-768 ciphertext (for KEM1 decapsulation)
 *   - signed prekey ID and one-time prekey ID (so recipient knows which keys to use)
 *
 * See: [signal.org — PQXDH specification]
 * See: [cryspen.com/post/pqxdh] — formal cryptographic analysis
 */
object PQXDHSender {

    private const val HKDF_INFO       = "aura-pqxdh-v1"
    private const val MASTER_KEY_LEN  = 32
    private val rng = SecureRandom()

    /**
     * Encapsulate a shared master secret using the recipient's [bundle].
     *
     * @param bundle              Recipient's [PreKeyBundle].
     * @param senderIdentityPriv  Sender's X25519-derived identity private key bytes.
     * @return [PQXDHEncapsulationResult] containing [masterSecret] and [message] to send.
     */
    fun encapsulate(
        bundle: PreKeyBundle,
        senderIdentityPriv: ByteArray,
    ): PQXDHEncapsulationResult {
        // Generate sender ephemeral X25519 key pair (fresh per message)
        val ephemeralPair = generateX25519()
        val ekPriv = ephemeralPair.first
        val ekPub  = ephemeralPair.second

        // Decode recipient keys
        val spkBPub  = X25519PublicKeyParameters(bundle.signedPreKeyPublic)
        // Use copyOfRange to avoid List<Byte>.toByteArray() overload ambiguity in K2
        val ikBPubBytes: ByteArray = bundle.identityPublicKey.let { it.copyOfRange(it.size - 32, it.size) }
        val ikBPub   = X25519PublicKeyParameters(ikBPubBytes)
        val ikAPriv  = X25519PrivateKeyParameters(senderIdentityPriv)
        val ekAPriv  = X25519PrivateKeyParameters(ekPriv)
        val ekAPub   = X25519PublicKeyParameters(ekPub)

        // DH1 = X25519(IK_A, SPK_B)
        val dh1 = x25519(ikAPriv, spkBPub)
        // DH2 = X25519(EK_A, IK_B)
        val dh2 = x25519(ekAPriv, ikBPub)
        // DH3 = X25519(EK_A, SPK_B)
        val dh3 = x25519(ekAPriv, spkBPub)

        // DH4 = X25519(EK_A, OPK_B_x25519) or zeros if no one-time prekey
        val dh4 = if (bundle.oneTimePreKeyPublicX25519 != null) {
            val opkBPub = X25519PublicKeyParameters(bundle.oneTimePreKeyPublicX25519)
            x25519(ekAPriv, opkBPub)
        } else {
            ByteArray(32) { 0 }
        }

        // KEM1 = ML-KEM-768 encapsulation or zeros if no one-time prekey
        val (kemCiphertext, kemSharedSecret) = if (bundle.oneTimePreKeyPublicMLKEM != null) {
            mlkem768Encapsulate(bundle.oneTimePreKeyPublicMLKEM)
        } else {
            Pair(ByteArray(0), ByteArray(32) { 0 })
        }

        // masterSecret = HKDF(DH1 || DH2 || DH3 || DH4 || kemSS, info="aura-pqxdh-v1")
        val ikm = dh1 + dh2 + dh3 + dh4 + kemSharedSecret
        val masterSecret = hkdfSha256(ikm, HKDF_INFO.toByteArray(), MASTER_KEY_LEN)

        // Zero-fill intermediate DH outputs immediately
        dh1.fill(0); dh2.fill(0); dh3.fill(0); dh4.fill(0); kemSharedSecret.fill(0)

        Timber.i(
            "PQXDH sender: encapsulation complete — " +
            "spkId=${bundle.signedPreKeyId} otpkId=${bundle.oneTimePreKeyId} " +
            "hasOtpk=${bundle.hasOneTimePreKey}"
        )

        val message = PQXDHMessage(
            senderIdentityPublicKey   = bundle.identityPublicKey, // sender's own identity public
            senderEphemeralPublicKey  = ekPub,
            kemCiphertext             = kemCiphertext,
            signedPreKeyId            = bundle.signedPreKeyId,
            oneTimePreKeyId           = bundle.oneTimePreKeyId,
        )

        return PQXDHEncapsulationResult(masterSecret = masterSecret, message = message)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun generateX25519(): Pair<ByteArray, ByteArray> {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        return Pair(
            (pair.private as X25519PrivateKeyParameters).encoded,
            (pair.public  as X25519PublicKeyParameters).encoded,
        )
    }

    private fun x25519(priv: X25519PrivateKeyParameters, pub: X25519PublicKeyParameters): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(priv)
        val out = ByteArray(32)
        agreement.calculateAgreement(pub, out, 0)
        return out
    }

    private fun mlkem768Encapsulate(publicKeyBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val pubKey = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, publicKeyBytes)
        val generator = MLKEMGenerator(java.security.SecureRandom())
        val encResult = generator.generateEncapsulated(pubKey)
        return Pair(encResult.encapsulation, encResult.secret)
    }

    /**
     * HKDF-SHA256 with optional salt (null = 32 zero bytes per RFC 5869).
     * Implemented via HMAC-SHA256 extract+expand.
     */
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

/** Result of [PQXDHSender.encapsulate]. */
data class PQXDHEncapsulationResult(
    /** 32-byte master secret. Feed into Double Ratchet KDF as root key. */
    val masterSecret: ByteArray,
    /** Message to transmit to recipient via any transport. */
    val message: PQXDHMessage,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PQXDHEncapsulationResult) return false
        return masterSecret.contentEquals(other.masterSecret)
    }
    override fun hashCode(): Int = masterSecret.contentHashCode()
}

/** Wire message sent from PQXDH sender to recipient. */
data class PQXDHMessage(
    val senderIdentityPublicKey: ByteArray,
    val senderEphemeralPublicKey: ByteArray,
    /** ML-KEM-768 ciphertext; empty if no one-time prekey was available. */
    val kemCiphertext: ByteArray,
    val signedPreKeyId: Int,
    val oneTimePreKeyId: Int?,
)
