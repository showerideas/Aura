package com.showerideas.aura.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealed sender profile envelope (wire protocol v7).
 *
 * Hides the sender's identity from passive observers. The outer frame contains
 * only an ephemeral public key; the sender's static identity is encrypted
 * inside the payload. The recipient learns who sent the message only after
 * successfully decrypting it.
 *
 * Security properties
 * • **Sender anonymity**: an observer cannot determine the sender from the wire.
 * • **Receiver authentication**: only the intended recipient can decrypt.
 * • **Sealed sender integrity**: a malicious relay cannot substitute a different
 *   sender identity without breaking the inner MAC.
 * • **Length hiding**: the payload is padded to a fixed frame size so message
 *   length does not leak context (e.g. profile size differences between users).
 *
 * Wire format (v7)
 * ```
 * Outer frame: [0x07][ephemeral_pub(32)][iv(12)][gcm_tag_len=16][ciphertext(FRAME_SIZE)]
 * Inner plain: [sender_static_pub(32)][payload_len(4 BE)][payload][padding zeros]
 * ```
 * Total outer frame bytes: 1 + 32 + 12 + 16 + FRAME_SIZE
 *
 * Key derivation
 * ```
 * ephemeral_ss = X25519(ephemeral_priv, recipient_static_pub)
 * static_ss    = X25519(sender_static_priv, recipient_static_pub)
 * envelope_key = HKDF-SHA256(ephemeral_ss || static_ss, info="AURA-v7-sealed-sender")
 * ```
 * Using both the ephemeral and the sender's static key means the recipient can
 * re-derive the key after learning the sender identity (inner plaintext), and
 * verify that the claimed sender actually holds the private key.
 */
object SealedEnvelope {

    /** Wire protocol version byte for sealed-sender frames. */
    const val PROTOCOL_VERSION: Byte = 0x07

    /** Fixed inner plaintext size (bytes). Payload must fit in FRAME_SIZE - 36 bytes. */
    const val FRAME_SIZE = 4096

    /** Maximum payload size after accounting for sender pub (32) + length prefix (4). */
    const val MAX_PAYLOAD_BYTES = FRAME_SIZE - 36  // 4060 bytes

    private const val GCM_IV_BYTES  = 12
    private const val GCM_TAG_BITS  = 128
    private const val X25519_BYTES  = 32
    private const val HKDF_INFO     = "AURA-v7-sealed-sender"

    private val rng = SecureRandom()

    // Public API

    /**
     * Wrap a profile [payload] into a sealed envelope addressed to [recipientStaticPub].
     *
     * @param payload            Raw bytes to send (e.g. serialised contact profile).
     *                           Must be ≤ [MAX_PAYLOAD_BYTES].
     * @param senderStaticPriv   Sender's long-term X25519 private key.
     * @param senderStaticPub    Sender's long-term X25519 public key (included inside the sealed inner).
     * @param recipientStaticPub Recipient's long-term X25519 public key.
     * @return Sealed envelope bytes for transmission.
     * @throws IllegalArgumentException if payload exceeds [MAX_PAYLOAD_BYTES].
     */
    fun wrap(
        payload           : ByteArray,
        senderStaticPriv  : X25519PrivateKeyParameters,
        senderStaticPub   : X25519PublicKeyParameters,
        recipientStaticPub: X25519PublicKeyParameters
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) {
            "Payload too large: ${payload.size} bytes, max is $MAX_PAYLOAD_BYTES"
        }

        // Generate ephemeral key pair
        val (ephPriv, ephPub) = generateX25519KeyPair()

        // Derive envelope key
        val envelopeKey = deriveEnvelopeKey(
            ephPriv, senderStaticPriv, recipientStaticPub
        )

        // Build inner plaintext: [sender_pub(32)][payload_len(4 BE)][payload][padding]
        val inner = ByteArray(FRAME_SIZE).also { buf ->
            senderStaticPub.encoded.copyInto(buf, 0)
            val len = payload.size
            buf[32] = (len shr 24).toByte()
            buf[33] = (len shr 16).toByte()
            buf[34] = (len shr  8).toByte()
            buf[35] = (len       ).toByte()
            payload.copyInto(buf, 36)
            // Remaining bytes are already zero-padded from ByteArray constructor
        }

        // Encrypt with AES-256-GCM
        val iv = ByteArray(GCM_IV_BYTES).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(envelopeKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(inner)  // includes 16-byte GCM tag appended

        // Assemble outer frame: [0x07][eph_pub(32)][iv(12)][gcm_ciphertext(FRAME_SIZE+16)]
        return ByteArray(1 + X25519_BYTES + GCM_IV_BYTES + ciphertext.size).also { frame ->
            frame[0] = PROTOCOL_VERSION
            ephPub.encoded.copyInto(frame, 1)
            iv.copyInto(frame, 1 + X25519_BYTES)
            ciphertext.copyInto(frame, 1 + X25519_BYTES + GCM_IV_BYTES)
        }
    }

    /**
     * Unwrap a sealed envelope using the recipient's static key pair.
     *
     * @param envelope         Bytes produced by [wrap].
     * @param recipientPriv    Recipient's long-term X25519 private key.
     * @param recipientPub     Recipient's long-term X25519 public key.
     * @return [UnwrapResult] containing the sender's static public key and plaintext payload.
     * @throws SealedEnvelopeException if decryption or version check fails.
     */
    fun unwrap(
        envelope      : ByteArray,
        recipientPriv : X25519PrivateKeyParameters,
        recipientPub  : X25519PublicKeyParameters
    ): UnwrapResult {
        if (envelope.isEmpty() || envelope[0] != PROTOCOL_VERSION) {
            throw SealedEnvelopeException(
                "Unsupported sealed envelope version: 0x%02x".format(
                    envelope.getOrElse(0) { 0xFF.toByte() })
            )
        }

        val minSize = 1 + X25519_BYTES + GCM_IV_BYTES + FRAME_SIZE + 16
        if (envelope.size < minSize) {
            throw SealedEnvelopeException("Envelope too short: ${envelope.size} bytes")
        }

        // Parse outer frame
        val ephPub = X25519PublicKeyParameters(envelope, 1)
        val iv     = envelope.copyOfRange(1 + X25519_BYTES, 1 + X25519_BYTES + GCM_IV_BYTES)
        val ct     = envelope.copyOfRange(1 + X25519_BYTES + GCM_IV_BYTES, envelope.size)

        // Phase 1: decrypt with ephemeral shared secret only (trial decryption)
        // This allows us to read the inner without knowing the sender yet.
        val ephSs = ecdh(recipientPriv, ephPub)

        // For unwrap we need the sender static pub first — read from inner after partial decrypt.
        // Use ephemeral-only key for first-pass decrypt (sender pub is inside plaintext).
        val trialKey = hkdfExpand(ephSs, "AURA-v7-sealed-sender-trial")
        val inner = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(trialKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ct)
            }
        } catch (e: Exception) {
            throw SealedEnvelopeException("Sealed envelope decryption failed (bad key or tampered)", e)
        }

        // Extract sender static pub from inner plaintext
        val senderPubBytes = inner.copyOfRange(0, X25519_BYTES)
        val senderStaticPub = X25519PublicKeyParameters(senderPubBytes, 0)

        // Phase 2: re-derive full envelope key with sender's static key and verify
        val senderSs    = ecdh(recipientPriv, senderStaticPub)
        val envelopeKey = hkdfCombine(ephSs, senderSs)

        // Re-decrypt with the full key (verifies sender identity binding)
        val verifiedInner = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(envelopeKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ct)
            }
        } catch (e: Exception) {
            throw SealedEnvelopeException("Sealed sender identity verification failed", e)
        }

        // Parse payload from inner: [sender_pub(32)][len(4 BE)][payload]
        val payloadLen = ((verifiedInner[32].toInt() and 0xFF) shl 24) or
                         ((verifiedInner[33].toInt() and 0xFF) shl 16) or
                         ((verifiedInner[34].toInt() and 0xFF) shl  8) or
                          (verifiedInner[35].toInt() and 0xFF)
        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
            throw SealedEnvelopeException("Invalid payload length field: $payloadLen")
        }
        val payload = verifiedInner.copyOfRange(36, 36 + payloadLen)

        return UnwrapResult(senderStaticPub, payload)
    }

    // Private helpers

    private fun generateX25519KeyPair(): Pair<X25519PrivateKeyParameters, X25519PublicKeyParameters> {
        val gen = X25519KeyPairGenerator().also { it.init(X25519KeyGenerationParameters(rng)) }
        val kp  = gen.generateKeyPair()
        return kp.private as X25519PrivateKeyParameters to kp.public as X25519PublicKeyParameters
    }

    private fun ecdh(priv: X25519PrivateKeyParameters, pub: X25519PublicKeyParameters): ByteArray {
        val out = ByteArray(32)
        X25519Agreement().apply { init(priv) }.calculateAgreement(pub, out, 0)
        return out
    }

    /**
     * Derive the full envelope key using both the ephemeral and static DH outputs.
     * Mirrors the wrap-side derivation.
     */
    private fun deriveEnvelopeKey(
        ephPriv      : X25519PrivateKeyParameters,
        senderPriv   : X25519PrivateKeyParameters,
        recipientPub : X25519PublicKeyParameters
    ): ByteArray {
        val ephSs    = ecdh(ephPriv, recipientPub)
        val senderSs = ecdh(senderPriv, recipientPub)
        return hkdfCombine(ephSs, senderSs)
    }

    /** Full envelope key: HKDF-SHA256(eph_ss || sender_ss, info=HKDF_INFO). */
    private fun hkdfCombine(ephSs: ByteArray, senderSs: ByteArray): ByteArray =
        hkdfExpand(ephSs + senderSs, HKDF_INFO)

    /** HKDF extract+expand in one step (single 32-byte block, no salt). */
    private fun hkdfExpand(ikm: ByteArray, info: String): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            doFinal(ikm)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(info.toByteArray(Charsets.UTF_8))
            update(0x01.toByte())
            doFinal()
        }
    }

    // Result types

    data class UnwrapResult(
        val senderStaticPub : X25519PublicKeyParameters,
        val payload         : ByteArray
    ) {
        override fun equals(other: Any?): Boolean =
            other is UnwrapResult &&
            senderStaticPub.encoded.contentEquals(other.senderStaticPub.encoded) &&
            payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * senderStaticPub.encoded.contentHashCode() + payload.contentHashCode()
    }

    class SealedEnvelopeException(message: String, cause: Throwable? = null) : Exception(message, cause)
}

