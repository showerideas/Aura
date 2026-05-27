package com.showerideas.aura.crypto

import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Unit tests for [SealedEnvelope].
 */
class SealedEnvelopeTest {

    private val rng = SecureRandom()

    private fun generateKeyPair(): Pair<X25519PrivateKeyParameters, X25519PublicKeyParameters> {
        val gen = X25519KeyPairGenerator().also { it.init(X25519KeyGenerationParameters(rng)) }
        val kp  = gen.generateKeyPair()
        return kp.private as X25519PrivateKeyParameters to kp.public as X25519PublicKeyParameters
    }

    @Test
    fun `wrap and unwrap round-trip recovers original payload`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (recipPriv, recipPub)   = generateKeyPair()
        val payload = "Hello, AURA!".toByteArray()

        val envelope = SealedEnvelope.wrap(payload, senderPriv, senderPub, recipPub)
        val result   = SealedEnvelope.unwrap(envelope, recipPriv, recipPub)

        assertArrayEquals(payload, result.payload)
        assertArrayEquals(senderPub.encoded, result.senderStaticPub.encoded)
    }

    @Test
    fun `unwrap correctly identifies sender`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (recipPriv, recipPub)   = generateKeyPair()

        val envelope = SealedEnvelope.wrap("profile data".toByteArray(), senderPriv, senderPub, recipPub)
        val result   = SealedEnvelope.unwrap(envelope, recipPriv, recipPub)

        assertArrayEquals("Sender identity must match", senderPub.encoded, result.senderStaticPub.encoded)
    }

    @Test
    fun `outer envelope does not reveal sender identity (anonymity check)`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (_, recipPub)           = generateKeyPair()

        val envelope = SealedEnvelope.wrap("secret profile".toByteArray(), senderPriv, senderPub, recipPub)

        // The sender's 32-byte public key should NOT appear verbatim in the outer envelope
        val senderPubBytes = senderPub.encoded
        val envelopeHex = envelope.joinToString("") { "%02x".format(it) }
        val senderHex   = senderPubBytes.joinToString("") { "%02x".format(it) }
        assertFalse("Sender public key must not appear in plaintext in the envelope", envelopeHex.contains(senderHex))
    }

    @Test
    fun `two wraps of the same payload produce different envelopes`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (_, recipPub)           = generateKeyPair()
        val payload = "profile".toByteArray()

        val e1 = SealedEnvelope.wrap(payload, senderPriv, senderPub, recipPub)
        val e2 = SealedEnvelope.wrap(payload, senderPriv, senderPub, recipPub)

        assertFalse("Re-wrapping must produce different ciphertext (ephemeral randomness)", e1.contentEquals(e2))
    }

    @Test
    fun `outer envelope frame has expected fixed size`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (_, recipPub)           = generateKeyPair()
        val payload = ByteArray(100) { it.toByte() }

        val envelope = SealedEnvelope.wrap(payload, senderPriv, senderPub, recipPub)
        // 1 (version) + 32 (eph pub) + 12 (iv) + 16 (GCM tag) + FRAME_SIZE
        val expectedSize = 1 + 32 + 12 + 16 + SealedEnvelope.FRAME_SIZE
        assertEquals("Envelope must be fixed size", expectedSize, envelope.size)
    }

    @Test
    fun `large payload up to MAX_PAYLOAD_BYTES is accepted`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (recipPriv, recipPub)   = generateKeyPair()
        val payload = ByteArray(SealedEnvelope.MAX_PAYLOAD_BYTES) { 0xAB.toByte() }

        val envelope = SealedEnvelope.wrap(payload, senderPriv, senderPub, recipPub)
        val result   = SealedEnvelope.unwrap(envelope, recipPriv, recipPub)
        assertArrayEquals(payload, result.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload exceeding MAX_PAYLOAD_BYTES throws`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (_, recipPub)           = generateKeyPair()
        SealedEnvelope.wrap(ByteArray(SealedEnvelope.MAX_PAYLOAD_BYTES + 1), senderPriv, senderPub, recipPub)
    }

    @Test(expected = SealedEnvelope.SealedEnvelopeException::class)
    fun `wrong recipient key causes decryption failure`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (_, recipPub)           = generateKeyPair()
        val (wrongPriv, wrongPub)   = generateKeyPair()

        val envelope = SealedEnvelope.wrap("data".toByteArray(), senderPriv, senderPub, recipPub)
        SealedEnvelope.unwrap(envelope, wrongPriv, wrongPub)
    }

    @Test(expected = SealedEnvelope.SealedEnvelopeException::class)
    fun `tampered ciphertext throws SealedEnvelopeException`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (recipPriv, recipPub)   = generateKeyPair()

        val envelope = SealedEnvelope.wrap("data".toByteArray(), senderPriv, senderPub, recipPub).also { env ->
            // Flip a byte in the ciphertext portion
            env[env.size - 10] = env[env.size - 10].xor(0xFF.toByte())
        }
        SealedEnvelope.unwrap(envelope, recipPriv, recipPub)
    }

    @Test(expected = SealedEnvelope.SealedEnvelopeException::class)
    fun `wrong version byte throws`() {
        val (senderPriv, senderPub) = generateKeyPair()
        val (recipPriv, recipPub)   = generateKeyPair()

        val envelope = SealedEnvelope.wrap("data".toByteArray(), senderPriv, senderPub, recipPub).also {
            it[0] = 0x05.toByte()  // wrong version
        }
        SealedEnvelope.unwrap(envelope, recipPriv, recipPub)
    }
}
