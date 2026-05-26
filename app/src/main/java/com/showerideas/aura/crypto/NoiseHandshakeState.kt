package com.showerideas.aura.crypto

import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.agreement.X25519Agreement
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Task 56 — Noise Protocol Framework: Noise_XX handshake state machine.
 *
 * Implements the Noise_XX pattern from the Noise Protocol Specification (revision 34).
 * Noise_XX provides mutual authentication with ephemeral DH, allowing both parties to
 * prove ownership of long-term static keys without a PKI.
 *
 * ## Pattern (Noise_XX, initiator perspective)
 * ```
 * Noise_XX:
 *   -> e                                    // initiator sends ephemeral public key
 *   <- e, ee, s, es                         // responder sends ephemeral, performs ee+es
 *   -> s, se                                // initiator sends static, performs se
 * ```
 * After 3 messages, both sides derive a pair of symmetric transport keys (send/recv).
 *
 * ## Security properties
 * - Forward secrecy: both parties use ephemeral X25519 keys discarded after handshake
 * - Mutual auth: both static keys are authenticated via chained hash
 * - Identity hiding: responder's static key is encrypted under ee; initiator's under es+ee
 * - Formally verified: ProVerif + Tamarin proofs exist for the Noise_XX pattern
 *
 * ## Why replace the custom ECDH handshake
 * AURA's current session token (Task 3 HKDF) and wire v8 negotiation (Task 33) use a
 * bespoke ECDH construction. Noise_XX is a drop-in replacement that is formally specified,
 * publicly audited, and used by WireGuard (which replaced OpenVPN in production at scale).
 *
 * ## AURA integration
 * [NoiseChannel] wraps this state machine and exposes `encrypt`/`decrypt` transport
 * operations after the handshake completes. [HybridKemEngine.WIRE_V9] sessions prefer
 * the Noise_XX channel when both peers advertise `NOISE_XX` capability.
 *
 * See: [noiseprotocol.org/noise.html]
 * See: [github.com/nicowillis/noise-kotlin] — reference implementation patterns
 */
class NoiseHandshakeState(
    private val isInitiator: Boolean,
    private val localStaticPriv: X25519PrivateKeyParameters,
    private val localStaticPub: X25519PublicKeyParameters,
    private val rng: SecureRandom = SecureRandom()
) {

    // ---- Noise_XX prologue and protocol name ----------------------------------
    companion object {
        private const val PROTOCOL_NAME  = "Noise_XX_25519_AESGCM_SHA256"
        private const val DHLEN          = 32
        private const val KEYLEN         = 32
        private const val BLOCKLEN       = 64
        private const val TAGLEN         = 16

        /** Generate a fresh X25519 static key pair for use as a Noise static key. */
        fun generateStaticKeyPair(rng: SecureRandom = SecureRandom()): Pair<X25519PrivateKeyParameters, X25519PublicKeyParameters> {
            val gen = X25519KeyPairGenerator()
            gen.init(X25519KeyGenerationParameters(rng))
            val pair = gen.generateKeyPair()
            return Pair(
                pair.private as X25519PrivateKeyParameters,
                pair.public  as X25519PublicKeyParameters
            )
        }
    }

    // Noise state variables
    private var ck: ByteArray = ByteArray(DHLEN)  // chaining key
    private var h: ByteArray  = ByteArray(DHLEN)  // hash value (handshake hash)
    private var k: ByteArray? = null              // cipher key
    private var n: Long       = 0L                // nonce

    // Ephemeral key pair (generated fresh per handshake)
    private val localEphPriv: X25519PrivateKeyParameters
    private val localEphPub: X25519PublicKeyParameters

    // Transport keys (available after handshake)
    private var sendKey: ByteArray? = null
    private var recvKey: ByteArray? = null

    var handshakeComplete: Boolean = false
        private set

    init {
        val ephPair = generateStaticKeyPair(rng)
        localEphPriv = ephPair.first
        localEphPub  = ephPair.second
        initializeSymmetric()
    }

    // ---- Symmetric state ops --------------------------------------------------

    private fun initializeSymmetric() {
        val nameBytes = PROTOCOL_NAME.toByteArray(Charsets.UTF_8)
        h = if (nameBytes.size <= DHLEN) {
            nameBytes.copyOf(DHLEN)
        } else {
            sha256(nameBytes)
        }
        ck = h.copyOf()
        Timber.v("Noise_XX initialised — protocol=$PROTOCOL_NAME initiator=$isInitiator")
    }

    private fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    private fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempK) = hkdf(ck, inputKeyMaterial, numOutputs = 2)
        ck = newCk
        k  = tempK.copyOf(KEYLEN)
        n  = 0
    }

    private fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = if (k != null) encryptWithAd(h, plaintext) else plaintext
        mixHash(ct)
        return ct
    }

    private fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val pt = if (k != null) decryptWithAd(h, ciphertext) else ciphertext
        mixHash(ciphertext)
        return pt
    }

    private fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = nonceToIv(n++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k!!, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ad)
        return cipher.doFinal(plaintext)
    }

    private fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val iv = nonceToIv(n++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k!!, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(ad)
        return cipher.doFinal(ciphertext)
    }

    // ---- DH ------------------------------------------------------------------

    private fun dh(privKey: X25519PrivateKeyParameters, pubKey: X25519PublicKeyParameters): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privKey)
        val shared = ByteArray(DHLEN)
        agreement.calculateAgreement(pubKey, shared, 0)
        return shared
    }

    // ---- Noise_XX message processing -----------------------------------------

    /**
     * Write handshake message 1 (initiator → responder): `-> e`
     * @return payload bytes to send
     */
    fun writeMessage1(payload: ByteArray = ByteArray(0)): ByteArray {
        check(isInitiator) { "Only initiator writes message 1" }
        mixHash(localEphPub.encoded)
        val encPayload = encryptAndHash(payload)
        return localEphPub.encoded + encPayload
    }

    /**
     * Read handshake message 1 (responder reads): `-> e`
     */
    fun readMessage1(message: ByteArray): ByteArray {
        check(!isInitiator) { "Only responder reads message 1" }
        val remoteEphPubBytes = message.copyOfRange(0, DHLEN)
        remoteEphPub = X25519PublicKeyParameters(remoteEphPubBytes, 0)
        mixHash(remoteEphPubBytes)
        val payload = decryptAndHash(message.copyOfRange(DHLEN, message.size))
        return payload
    }

    private var remoteEphPub: X25519PublicKeyParameters? = null
    private var remoteStaticPub: X25519PublicKeyParameters? = null

    /**
     * Write handshake message 2 (responder → initiator): `<- e, ee, s, es`
     */
    fun writeMessage2(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!isInitiator) { "Only responder writes message 2" }
        val remoteEph = remoteEphPub ?: error("Message 1 not processed")
        mixHash(localEphPub.encoded)
        mixKey(dh(localEphPriv, remoteEph))           // ee
        val encStatic = encryptAndHash(localStaticPub.encoded)
        mixKey(dh(localStaticPriv, remoteEph))        // es
        val encPayload = encryptAndHash(payload)
        return localEphPub.encoded + encStatic + encPayload
    }

    /**
     * Read handshake message 2 (initiator reads): `<- e, ee, s, es`
     */
    fun readMessage2(message: ByteArray): ByteArray {
        check(isInitiator) { "Only initiator reads message 2" }
        var offset = 0
        val remoteEphBytes = message.copyOfRange(offset, offset + DHLEN); offset += DHLEN
        remoteEphPub = X25519PublicKeyParameters(remoteEphBytes, 0)
        mixHash(remoteEphBytes)
        mixKey(dh(localEphPriv, remoteEphPub!!))        // ee
        val encStatic = message.copyOfRange(offset, offset + DHLEN + TAGLEN); offset += DHLEN + TAGLEN
        val remoteStaticBytes = decryptAndHash(encStatic)
        remoteStaticPub = X25519PublicKeyParameters(remoteStaticBytes, 0)
        mixKey(dh(localEphPriv, remoteStaticPub!!))     // es
        return decryptAndHash(message.copyOfRange(offset, message.size))
    }

    /**
     * Write handshake message 3 (initiator → responder): `-> s, se`
     * After this message, handshake is complete and transport keys are available.
     */
    fun writeMessage3(payload: ByteArray = ByteArray(0)): ByteArray {
        check(isInitiator) { "Only initiator writes message 3" }
        val remoteEph = remoteEphPub ?: error("Message 2 not processed")
        val encStatic = encryptAndHash(localStaticPub.encoded)
        mixKey(dh(localStaticPriv, remoteEph))         // se
        val encPayload = encryptAndHash(payload)
        splitKeys()
        return encStatic + encPayload
    }

    /**
     * Read handshake message 3 (responder reads): `-> s, se`
     * After this message, handshake is complete and transport keys are available.
     */
    fun readMessage3(message: ByteArray): ByteArray {
        check(!isInitiator) { "Only responder reads message 3" }
        var offset = 0
        val encStatic = message.copyOfRange(offset, offset + DHLEN + TAGLEN); offset += DHLEN + TAGLEN
        val remoteStaticBytes = decryptAndHash(encStatic)
        remoteStaticPub = X25519PublicKeyParameters(remoteStaticBytes, 0)
        mixKey(dh(localEphPriv, remoteStaticPub!!))    // se
        val payload = decryptAndHash(message.copyOfRange(offset, message.size))
        splitKeys()
        return payload
    }

    /** Remote static public key — available after handshake completes. Used for TOFU verification. */
    fun remoteStaticPublicKey(): X25519PublicKeyParameters =
        remoteStaticPub ?: error("Handshake not complete")

    /** The handshake hash — use as session binding for channel binding / SAS. */
    fun handshakeHash(): ByteArray = h.copyOf()

    private fun splitKeys() {
        val (k1, k2) = hkdf(ck, ByteArray(0), numOutputs = 2)
        if (isInitiator) { sendKey = k1; recvKey = k2 } else { sendKey = k2; recvKey = k1 }
        handshakeComplete = true
        Timber.d("Noise_XX handshake complete — transport keys derived")
    }

    /** Transport encrypt (post-handshake). */
    fun transportEncrypt(plaintext: ByteArray): ByteArray {
        check(handshakeComplete) { "Handshake not complete" }
        val iv = nonceToIv(n++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey!!, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plaintext)
    }

    /** Transport decrypt (post-handshake). */
    fun transportDecrypt(ciphertext: ByteArray): ByteArray {
        check(handshakeComplete) { "Handshake not complete" }
        val iv = nonceToIv(n++)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvKey!!, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    // ---- Crypto primitives ---------------------------------------------------

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /** Noise HKDF: two-output key derivation from chaining key. */
    private fun hkdf(key: ByteArray, ikm: ByteArray, numOutputs: Int): Pair<ByteArray, ByteArray> {
        val prk = hmacSha256(key, ikm)
        val out1 = hmacSha256(prk, byteArrayOf(0x01))
        val out2 = hmacSha256(prk, out1 + byteArrayOf(0x02))
        return Pair(out1, out2)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Big-endian 96-bit nonce for AES-GCM from 64-bit counter. */
    private fun nonceToIv(n: Long): ByteArray {
        val iv = ByteArray(12)
        iv[4] = (n shr 56).toByte(); iv[5] = (n shr 48).toByte()
        iv[6] = (n shr 40).toByte(); iv[7] = (n shr 32).toByte()
        iv[8] = (n shr 24).toByte(); iv[9] = (n shr 16).toByte()
        iv[10] = (n shr 8).toByte(); iv[11] = n.toByte()
        return iv
    }
}
