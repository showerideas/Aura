package com.showerideas.aura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Task 56 — Noise_XX channel unit tests.
 *
 * Verifies the full Noise_XX 3-message handshake + transport encrypt/decrypt.
 * All operations are pure JVM (BouncyCastle + JCA) — no Android dependencies.
 */
class NoiseChannelTest {

    private val rng = SecureRandom()

    private fun makeKeyPair() = NoiseHandshakeState.generateStaticKeyPair(rng)

    @Test
    fun `full Noise_XX handshake completes and transport keys work`() {
        val (initPriv, initPub) = makeKeyPair()
        val (respPriv, respPub) = makeKeyPair()

        val initiator = NoiseHandshakeState(true,  initPriv, initPub, rng)
        val responder = NoiseHandshakeState(false, respPriv, respPub, rng)

        // Message 1: initiator -> responder
        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)

        // Message 2: responder -> initiator
        val msg2 = responder.writeMessage2()
        initiator.readMessage2(msg2)

        // Message 3: initiator -> responder
        val msg3 = initiator.writeMessage3()
        responder.readMessage3(msg3)

        assertTrue("Initiator handshake must be complete", initiator.handshakeComplete)
        assertTrue("Responder handshake must be complete", responder.handshakeComplete)
    }

    @Test
    fun `transport encryption round-trip succeeds after handshake`() {
        val (initPriv, initPub) = makeKeyPair()
        val (respPriv, respPub) = makeKeyPair()

        val initiator = NoiseHandshakeState(true,  initPriv, initPub, rng)
        val responder = NoiseHandshakeState(false, respPriv, respPub, rng)

        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        responder.readMessage3(initiator.writeMessage3())

        val plaintext = "AURA Noise_XX transport test payload".toByteArray()
        val ciphertext = initiator.transportEncrypt(plaintext)
        val decrypted  = responder.transportDecrypt(ciphertext)

        assertArrayEquals("Transport decrypt must recover plaintext", plaintext, decrypted)
    }

    @Test
    fun `handshake hash is identical on both sides`() {
        val (initPriv, initPub) = makeKeyPair()
        val (respPriv, respPub) = makeKeyPair()

        val initiator = NoiseHandshakeState(true,  initPriv, initPub, rng)
        val responder = NoiseHandshakeState(false, respPriv, respPub, rng)

        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        responder.readMessage3(initiator.writeMessage3())

        assertTrue(
            "Handshake hash must match on both sides for channel binding",
            initiator.handshakeHash().contentEquals(responder.handshakeHash())
        )
    }

    @Test
    fun `remote static public key authenticated after handshake`() {
        val (initPriv, initPub) = makeKeyPair()
        val (respPriv, respPub) = makeKeyPair()

        val initiator = NoiseHandshakeState(true,  initPriv, initPub, rng)
        val responder = NoiseHandshakeState(false, respPriv, respPub, rng)

        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        responder.readMessage3(initiator.writeMessage3())

        // Initiator must see responder's static pub; responder must see initiator's static pub
        assertTrue(
            "Initiator must authenticate responder's static key",
            initiator.remoteStaticPublicKey().encoded.contentEquals(respPub.encoded)
        )
        assertTrue(
            "Responder must authenticate initiator's static key",
            responder.remoteStaticPublicKey().encoded.contentEquals(initPub.encoded)
        )
    }

    @Test
    fun `tampered ciphertext fails decryption`() {
        val (initPriv, initPub) = makeKeyPair()
        val (respPriv, respPub) = makeKeyPair()

        val initiator = NoiseHandshakeState(true,  initPriv, initPub, rng)
        val responder = NoiseHandshakeState(false, respPriv, respPub, rng)

        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        responder.readMessage3(initiator.writeMessage3())

        val ct = initiator.transportEncrypt("secret".toByteArray())
        val tampered = ct.copyOf().also { it[0] = it[0].xor(0xFF.toByte()) }

        var threw = false
        try { responder.transportDecrypt(tampered) } catch (_: Exception) { threw = true }
        assertTrue("Tampered ciphertext must throw during decryption", threw)
    }
}
