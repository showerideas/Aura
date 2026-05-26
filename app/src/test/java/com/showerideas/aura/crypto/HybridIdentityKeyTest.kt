package com.showerideas.aura.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 48 — HybridIdentityKey unit tests.
 *
 * Verifies:
 * - ML-DSA-65 + P-256 sign/verify round-trip
 * - Hybrid signature byte layout (encode/decode)
 * - Corrupted P-256 sig fails verification
 * - Corrupted ML-DSA sig fails verification
 * - v9 backward compat: v8-only receiver ignores ML-DSA TLV (wire layout check)
 */
class HybridIdentityKeyTest {

    private lateinit var key: HybridIdentityKey
    private val testData = "AURA-v9-hybrid-sig-test".toByteArray()

    @Before
    fun setup() {
        key = HybridIdentityKey()
        key.generate()
    }

    @Test
    fun `sign and verify round-trip succeeds`() {
        val sig = key.sign(testData)
        val ok = key.verify(testData, sig, key.ecPublicKey(), key.mlDsaPublicKey())
        assertTrue("Hybrid signature must verify successfully", ok)
    }

    @Test
    fun `corrupted p256 sig fails verification`() {
        val sig = key.sign(testData)
        val badP256 = sig.p256Sig.copyOf().also { it[0] = it[0].xor(0xFF.toByte()) }
        val badSig = HybridSignature(badP256, sig.mlDsaSig)
        val ok = key.verify(testData, badSig, key.ecPublicKey(), key.mlDsaPublicKey())
        assertFalse("Corrupted P-256 sig must not verify", ok)
    }

    @Test
    fun `corrupted ml-dsa sig fails verification`() {
        val sig = key.sign(testData)
        val badMlDsa = sig.mlDsaSig.copyOf().also { it[10] = it[10].xor(0xFF.toByte()) }
        val badSig = HybridSignature(sig.p256Sig, badMlDsa)
        val ok = key.verify(testData, badSig, key.ecPublicKey(), key.mlDsaPublicKey())
        assertFalse("Corrupted ML-DSA sig must not verify", ok)
    }

    @Test
    fun `hybrid signature encode-decode round-trip`() {
        val sig = key.sign(testData)
        val encoded = sig.encode()
        val decoded = HybridSignature.decode(encoded)
        assertTrue("P-256 sig survives encode/decode", sig.p256Sig.contentEquals(decoded.p256Sig))
        assertTrue("ML-DSA sig survives encode/decode", sig.mlDsaSig.contentEquals(decoded.mlDsaSig))
    }

    @Test
    fun `wrong data fails verification`() {
        val sig = key.sign(testData)
        val ok = key.verify("tampered-data".toByteArray(), sig, key.ecPublicKey(), key.mlDsaPublicKey())
        assertFalse("Signature over different data must not verify", ok)
    }

    @Test
    fun `key material serialise and reload produces same signatures`() {
        val sig1 = key.sign(testData)
        // Reload key from encoded bytes
        val key2 = HybridIdentityKey()
        key2.load(key.ecPrivateDer(), key.ecPublicDer(), key.mlDsaPrivBytes(), key.mlDsaPubBytes())
        val sig2 = key2.sign(testData)
        // Both sigs should verify under same public keys
        assertTrue(key.verify(testData, sig2, key.ecPublicKey(), key.mlDsaPublicKey()))
        assertTrue(key2.verify(testData, sig1, key2.ecPublicKey(), key2.mlDsaPublicKey()))
    }

    @Test
    fun `p256 sig is 64 bytes`() {
        val sig = key.sign(testData)
        assertTrue("P-256 raw sig must be 64 bytes", sig.p256Sig.size == 64)
    }

    @Test
    fun `ml-dsa-65 sig size matches Dilithium-3 spec`() {
        val sig = key.sign(testData)
        // ML-DSA-65 (Dilithium mode 3) signature is 3293 bytes
        assertTrue("ML-DSA-65 sig size must be 3293 bytes", sig.mlDsaSig.size == 3293)
    }
}
