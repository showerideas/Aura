package com.showerideas.aura.crypto

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 8.1 — Unit tests for [HybridKEM].
 *
 * Runs on JVM (no Android runtime required) because BouncyCastle is a pure
 * Java library and the test targets are JVM-only.
 */
class HybridKEMTest {

    // -------------------------------------------------------------------------
    // Round-trip correctness
    // -------------------------------------------------------------------------

    @Test
    fun `encapsulate and decapsulate produce identical 32-byte shared secrets`() {
        val recipientKP = HybridKEM.generateKeyPair()
        val result      = HybridKEM.encapsulate(recipientKP.encodedPublicKey)
        val recovered   = HybridKEM.decapsulate(result.ciphertext, recipientKP)

        assertArrayEquals(
            "Shared secrets must match after round-trip",
            result.sharedSecret, recovered
        )
    }

    @Test
    fun `shared secret is exactly 32 bytes`() {
        val kp     = HybridKEM.generateKeyPair()
        val result = HybridKEM.encapsulate(kp.encodedPublicKey)
        assertEquals(32, result.sharedSecret.size)
    }

    @Test
    fun `ciphertext is exactly CIPHERTEXT_BYTES`() {
        val kp     = HybridKEM.generateKeyPair()
        val result = HybridKEM.encapsulate(kp.encodedPublicKey)
        assertEquals(HybridKEM.CIPHERTEXT_BYTES, result.ciphertext.size)
    }

    @Test
    fun `encoded public key is exactly PUBLIC_KEY_BYTES`() {
        val kp = HybridKEM.generateKeyPair()
        assertEquals(HybridKEM.PUBLIC_KEY_BYTES, kp.encodedPublicKey.size)
    }

    @Test
    fun `encoded public key starts with protocol version byte 0x06`() {
        val kp = HybridKEM.generateKeyPair()
        assertEquals(
            "First byte must be PROTOCOL_VERSION",
            HybridKEM.PROTOCOL_VERSION, kp.encodedPublicKey[0]
        )
    }

    // -------------------------------------------------------------------------
    // Independence: two fresh key pairs produce different secrets
    // -------------------------------------------------------------------------

    @Test
    fun `two separate exchanges produce different shared secrets`() {
        val kp1 = HybridKEM.generateKeyPair()
        val kp2 = HybridKEM.generateKeyPair()
        val ss1 = HybridKEM.encapsulate(kp1.encodedPublicKey).sharedSecret
        val ss2 = HybridKEM.encapsulate(kp2.encodedPublicKey).sharedSecret
        assertFalse(
            "Different key pairs must produce different shared secrets",
            ss1.contentEquals(ss2)
        )
    }

    @Test
    fun `two encapsulations to the same recipient produce different ciphertexts`() {
        val kp = HybridKEM.generateKeyPair()
        val r1 = HybridKEM.encapsulate(kp.encodedPublicKey)
        val r2 = HybridKEM.encapsulate(kp.encodedPublicKey)
        // Ciphertexts must differ (ephemeral key randomness)
        assertFalse(
            "Re-encapsulation must produce different ciphertexts (ephemeral randomness)",
            r1.ciphertext.contentEquals(r2.ciphertext)
        )
        // But the recipient can decapsulate both and recover two different secrets
        val ss1 = HybridKEM.decapsulate(r1.ciphertext, kp)
        val ss2 = HybridKEM.decapsulate(r2.ciphertext, kp)
        assertFalse("Two encapsulations must produce different shared secrets", ss1.contentEquals(ss2))
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `encapsulate rejects public key of wrong length`() {
        HybridKEM.encapsulate(ByteArray(100))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encapsulate rejects public key with wrong version byte`() {
        val kp  = HybridKEM.generateKeyPair()
        val bad = kp.encodedPublicKey.also { it[0] = 0x05 }
        HybridKEM.encapsulate(bad)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decapsulate rejects ciphertext of wrong length`() {
        val kp = HybridKEM.generateKeyPair()
        HybridKEM.decapsulate(ByteArray(50), kp)
    }
}
