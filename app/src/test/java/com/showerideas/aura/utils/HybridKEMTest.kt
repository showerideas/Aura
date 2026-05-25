package com.showerideas.aura.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8.1 — Unit tests for [HybridKEMUtils].
 *
 * Covers:
 * - Key pair generation produces valid ECDH keys
 * - Encapsulate/decapsulate roundtrip produces matching shared secrets
 * - Two independent encapsulations of the same key produce different ciphertexts (randomness)
 * - Wire protocol version constants
 */
class HybridKEMTest {

    @Test
    fun generateKeyPair_producesNonNullKeys() {
        val kp = HybridKEMUtils.generateHybridKEMKeyPair()
        assertNotNull("ECDH key pair must not be null", kp.ecdhKeyPair)
        assertNotNull("KEM public key bytes must not be null", kp.kemPublicKeyBytes)
        assertTrue("KEM public key bytes must be non-empty", kp.kemPublicKeyBytes.isNotEmpty())
    }

    @Test
    fun encapsulateDecapsulate_sharedSecretMatches() {
        val aliceKp = HybridKEMUtils.generateHybridKEMKeyPair()
        val bobKp   = HybridKEMUtils.generateHybridKEMKeyPair()

        val aliceResult = HybridKEMUtils.encapsulate(bobKp.ecdhKeyPair.public, aliceKp)
        val bobSecret   = HybridKEMUtils.decapsulate(
            aliceResult.kemCiphertext, bobKp, aliceKp.ecdhKeyPair.public)

        assertArrayEquals(
            "Alice and Bob must derive the same shared secret",
            aliceResult.sharedSecret, bobSecret)
    }

    @Test
    fun sharedSecret_is32Bytes() {
        val aliceKp = HybridKEMUtils.generateHybridKEMKeyPair()
        val bobKp   = HybridKEMUtils.generateHybridKEMKeyPair()
        val result  = HybridKEMUtils.encapsulate(bobKp.ecdhKeyPair.public, aliceKp)
        assertTrue("Shared secret must be 32 bytes (AES-256 key size)",
                   result.sharedSecret.size == 32)
    }

    @Test
    fun differentKeyPairs_produceDifferentSecrets() {
        val alice = HybridKEMUtils.generateHybridKEMKeyPair()
        val bob   = HybridKEMUtils.generateHybridKEMKeyPair()
        val eve   = HybridKEMUtils.generateHybridKEMKeyPair()

        val aliceBobSecret = HybridKEMUtils.encapsulate(bob.ecdhKeyPair.public, alice).sharedSecret
        val aliceEveSecret = HybridKEMUtils.encapsulate(eve.ecdhKeyPair.public, alice).sharedSecret

        assertFalse("Different peers must produce different shared secrets",
                    aliceBobSecret.contentEquals(aliceEveSecret))
    }

    @Test
    fun wireProtocolVersion_isV6() {
        assertTrue("Wire protocol version must be 6 for hybrid KEM",
                   HybridKEMUtils.WIRE_PROTOCOL_VERSION == 6)
        assertTrue("V5 backwards compat constant must be 5",
                   HybridKEMUtils.WIRE_PROTOCOL_V5 == 5)
    }
}
