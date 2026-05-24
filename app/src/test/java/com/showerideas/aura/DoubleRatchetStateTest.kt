package com.showerideas.aura

import com.showerideas.aura.utils.CryptoUtils
import com.showerideas.aura.utils.DoubleRatchetState
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for [DoubleRatchetState] — the symmetric ratchet that provides
 * per-message key rotation for forward secrecy within an AURA exchange session.
 *
 * All tests run on JVM (no Android deps) — pure javax.crypto.
 */
class DoubleRatchetStateTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Create a deterministic 256-bit AES key from a UTF-8 seed. */
    private fun seedKey(seed: String): javax.crypto.SecretKey {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(bytes, "AES")
    }

    // -------------------------------------------------------------------------
    // Synchronisation — sender and receiver produce identical keys
    // -------------------------------------------------------------------------

    @Test
    fun `sender and receiver derive identical keys in order`() {
        val session = seedKey("shared-session-key")
        val sender   = DoubleRatchetState.from(session)
        val receiver = DoubleRatchetState.from(session)

        for (i in 1..5) {
            val sKey = sender.nextMessageKey().encoded
            val rKey = receiver.nextMessageKey().encoded
            assertArrayEquals("Message $i keys must match", sKey, rKey)
        }
    }

    @Test
    fun `keys derived from same session via CryptoUtils helper match directly`() {
        val session  = seedKey("another-session")
        val ratchet1 = CryptoUtils.newRatchet(session)
        val ratchet2 = CryptoUtils.newRatchet(session)

        val k1 = ratchet1.nextMessageKey().encoded
        val k2 = ratchet2.nextMessageKey().encoded
        assertArrayEquals("CryptoUtils.newRatchet keys must match for same session", k1, k2)
    }

    // -------------------------------------------------------------------------
    // Forward secrecy — each derived key is unique
    // -------------------------------------------------------------------------

    @Test
    fun `consecutive message keys are all distinct`() {
        val ratchet = DoubleRatchetState.from(seedKey("test-fwd-secrecy"))
        val keys = (1..10).map { ratchet.nextMessageKey().encoded.toList() }
        val unique = keys.toSet()
        assertEquals("All 10 message keys must be distinct", 10, unique.size)
    }

    @Test
    fun `message key and next chain key are different`() {
        val session = seedKey("chain-vs-message")
        val r1 = DoubleRatchetState.from(session)
        val r2 = DoubleRatchetState.from(session)

        val msgKey   = r1.nextMessageKey().encoded
        val chain    = r2.exportChainKey()  // BEFORE advancing r2

        assertFalse(
            "Message key and initial chain key must not be equal",
            msgKey.contentEquals(chain)
        )
    }

    // -------------------------------------------------------------------------
    // Chain advancement — chain key evolves after each step
    // -------------------------------------------------------------------------

    @Test
    fun `chain key changes after each nextMessageKey call`() {
        val ratchet = DoubleRatchetState.from(seedKey("chain-advance"))
        val chain0 = ratchet.exportChainKey()
        ratchet.nextMessageKey()
        val chain1 = ratchet.exportChainKey()
        ratchet.nextMessageKey()
        val chain2 = ratchet.exportChainKey()

        assertFalse("Chain key must change after step 1", chain0.contentEquals(chain1))
        assertFalse("Chain key must change after step 2", chain1.contentEquals(chain2))
        assertFalse("Chain keys at step 0 and 2 must differ", chain0.contentEquals(chain2))
    }

    // -------------------------------------------------------------------------
    // Message index counter
    // -------------------------------------------------------------------------

    @Test
    fun `messageIndex increments on each nextMessageKey call`() {
        val ratchet = DoubleRatchetState.from(seedKey("index-test"))
        assertEquals(0, ratchet.messageIndex)
        ratchet.nextMessageKey()
        assertEquals(1, ratchet.messageIndex)
        ratchet.nextMessageKey()
        assertEquals(2, ratchet.messageIndex)
    }

    @Test
    fun `nextMessageKeyIndexed returns correct index`() {
        val ratchet = DoubleRatchetState.from(seedKey("indexed-test"))
        val (_, idx1) = ratchet.nextMessageKeyIndexed()
        val (_, idx2) = ratchet.nextMessageKeyIndexed()
        assertEquals(1, idx1)
        assertEquals(2, idx2)
    }

    // -------------------------------------------------------------------------
    // fromBytes round-trip (serialization)
    // -------------------------------------------------------------------------

    @Test
    fun `ratchet resumed from exported chain key produces same keys`() {
        val session = seedKey("resume-test")
        val original = DoubleRatchetState.from(session)

        // Advance once
        original.nextMessageKey()

        // Export chain key and resume from it
        val chainBytes = original.exportChainKey()
        val resumed = DoubleRatchetState.fromBytes(chainBytes)

        // Both should now produce the same next key
        val origNext    = original.nextMessageKey().encoded
        val resumedNext = resumed.nextMessageKey().encoded
        assertArrayEquals("Resumed ratchet must produce same key as original", origNext, resumedNext)
    }

    // -------------------------------------------------------------------------
    // Encrypt / decrypt round-trip via CryptoUtils helpers
    // -------------------------------------------------------------------------

    @Test
    fun `ratchetEncrypt and ratchetDecrypt round-trip correctly`() {
        val session   = seedKey("e2e-roundtrip")
        val sender    = CryptoUtils.newRatchet(session)
        val receiver  = CryptoUtils.newRatchet(session)
        val plaintext = "Hello AURA!".toByteArray(Charsets.UTF_8)

        val ciphertext = CryptoUtils.ratchetEncrypt(sender, plaintext)
        val decrypted  = CryptoUtils.ratchetDecrypt(receiver, ciphertext)

        assertArrayEquals("Decrypted plaintext must match original", plaintext, decrypted)
    }

    @Test
    fun `multiple ratchet encrypt-decrypt cycles all succeed`() {
        val session  = seedKey("multi-cycle")
        val sender   = CryptoUtils.newRatchet(session)
        val receiver = CryptoUtils.newRatchet(session)

        val messages = listOf("First message", "Second message", "Third message", "Fourth message")
        for (msg in messages) {
            val plain  = msg.toByteArray(Charsets.UTF_8)
            val cipher = CryptoUtils.ratchetEncrypt(sender, plain)
            val dec    = CryptoUtils.ratchetDecrypt(receiver, cipher)
            assertArrayEquals("Cycle failed for '$msg'", plain, dec)
        }
    }

    @Test
    fun `wrong ratchet step produces decryption failure`() {
        val session  = seedKey("wrong-step")
        val sender   = CryptoUtils.newRatchet(session)
        val receiver = CryptoUtils.newRatchet(session)

        // Sender sends message 1
        val cipher1 = CryptoUtils.ratchetEncrypt(sender, "msg1".toByteArray())

        // Receiver advances TWO steps (out of sync)
        receiver.nextMessageKey()
        receiver.nextMessageKey()

        // Decryption with the wrong key should throw (AES-GCM tag failure)
        try {
            CryptoUtils.ratchetDecrypt(receiver, cipher1)
            fail("Expected exception decrypting with wrong ratchet step")
        } catch (e: Exception) {
            // Expected — AES-GCM tag verification failure or bad padding
        }
    }

    // -------------------------------------------------------------------------
    // Key material is 256-bit AES
    // -------------------------------------------------------------------------

    @Test
    fun `message keys are 256-bit AES`() {
        val ratchet = DoubleRatchetState.from(seedKey("key-size"))
        val key = ratchet.nextMessageKey()
        assertEquals("AES", key.algorithm)
        assertEquals(32, key.encoded.size) // 256 bits = 32 bytes
    }
}
