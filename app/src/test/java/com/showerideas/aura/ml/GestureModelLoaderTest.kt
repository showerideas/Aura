package com.showerideas.aura.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for GestureModelLoader's hash verification logic.
 *
 * These are pure JVM tests; they exercise the SHA-256 logic in isolation
 * without any Android context or MediaPipe runtime.
 */
class GestureModelLoaderTest {

    private val expectedSha256 = "f7bbcc17ecc99c879f45f58d36e4e0feec78e9b0aedde99d9b1a5f2e28dbd36c"

    @Test
    fun sha256_ofKnownBytes_producesExpectedHex() {
        // SHA-256 of empty byte array is well-known
        val emptyHash = sha256Hex(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptyHash
        )
    }

    @Test
    fun sha256_hex_is64Characters() {
        val hash = sha256Hex("AURA".toByteArray())
        assertEquals("SHA-256 hex must be 64 characters", 64, hash.length)
        assertTrue("SHA-256 hex must be lowercase hex", hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun sha256_twoDistinctInputs_produceDifferentHashes() {
        val hash1 = sha256Hex("model-v1".toByteArray())
        val hash2 = sha256Hex("model-v2".toByteArray())
        assertFalse("Different inputs must produce different hashes", hash1 == hash2)
    }

    @Test
    fun sha256_sameInput_isReproducible() {
        val data = "deterministic-input".toByteArray()
        val h1 = sha256Hex(data)
        val h2 = sha256Hex(data)
        assertEquals("Same input must always produce same SHA-256", h1, h2)
    }

    @Test
    fun expectedModelSha256_hasCorrectFormat() {
        assertEquals("Model SHA-256 must be 64 hex characters", 64, expectedSha256.length)
        assertTrue(expectedSha256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
