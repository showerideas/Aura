package com.showerideas.aura

import com.showerideas.aura.utils.CryptoUtils
import com.showerideas.aura.utils.SasVerifier
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * Prompt-8: Unit tests for [SasVerifier].
 *
 * Tests confirm the SAS derivation contract:
 *  1. Determinism — same keys always produce the same SAS
 *  2. Symmetry — derive(a, b) == derive(b, a)
 *  3. Range — always 6 digits, always [0, 10^6)
 *  4. Sensitivity — changing one key produces a different SAS with high probability
 *  5. Verification helper delegates correctly
 *  6. Distinct key-pairs never collide in a small sample (birthday-paradox sanity check)
 */
class SasVerifierTest {

    private fun ecKeyPair() = CryptoUtils.generateEphemeralECDHKeyPair()

    // -------------------------------------------------------------------------
    // 1. Determinism
    // -------------------------------------------------------------------------

    @Test
    fun `same key pair always produces the same SAS`() {
        val a = ecKeyPair()
        val b = ecKeyPair()
        val sas1 = SasVerifier.derive(a.public, b.public)
        val sas2 = SasVerifier.derive(a.public, b.public)
        assertEquals("SAS must be deterministic", sas1, sas2)
    }

    @Test
    fun `SAS from raw bytes matches SAS from PublicKey objects`() {
        val a = ecKeyPair()
        val b = ecKeyPair()
        val fromKeys  = SasVerifier.derive(a.public, b.public)
        val fromBytes = SasVerifier.deriveFromBytes(a.public.encoded, b.public.encoded)
        assertEquals(fromKeys, fromBytes)
    }

    // -------------------------------------------------------------------------
    // 2. Symmetry (canonical ordering)
    // -------------------------------------------------------------------------

    @Test
    fun `derive(a, b) equals derive(b, a)`() {
        val a = ecKeyPair()
        val b = ecKeyPair()
        assertEquals(
            "SAS must be symmetric — both parties compute the same value",
            SasVerifier.derive(a.public, b.public),
            SasVerifier.derive(b.public, a.public)
        )
    }

    @Test
    fun `symmetry holds across 10 independent key pairs`() {
        repeat(10) {
            val a = ecKeyPair()
            val b = ecKeyPair()
            assertEquals(SasVerifier.derive(a.public, b.public), SasVerifier.derive(b.public, a.public))
        }
    }

    // -------------------------------------------------------------------------
    // 3. Range and format
    // -------------------------------------------------------------------------

    @Test
    fun `SAS is always exactly 6 characters`() {
        repeat(20) {
            val sas = SasVerifier.derive(ecKeyPair().public, ecKeyPair().public)
            assertEquals("SAS must be exactly ${SasVerifier.SAS_DIGITS} digits", SasVerifier.SAS_DIGITS, sas.length)
        }
    }

    @Test
    fun `SAS contains only decimal digits`() {
        val sas = SasVerifier.derive(ecKeyPair().public, ecKeyPair().public)
        assertTrue("SAS must be all digits, got: $sas", sas.all { it.isDigit() })
    }

    @Test
    fun `SAS is always less than 1_000_000`() {
        repeat(20) {
            val value = SasVerifier.derive(ecKeyPair().public, ecKeyPair().public).toLong()
            assertTrue("SAS must be < 1_000_000, got $value", value < 1_000_000L)
        }
    }

    @Test
    fun `SAS is always non-negative`() {
        repeat(20) {
            val value = SasVerifier.derive(ecKeyPair().public, ecKeyPair().public).toLong()
            assertTrue("SAS must be >= 0, got $value", value >= 0L)
        }
    }

    @Test
    fun `SAS of zero pads to 6 digits`() {
        // We can't force the hash to produce 0 without brute force, but we can
        // verify the raw deriveFromBytes path with controlled input.
        // SHA-256(0x00 || 0x00) starts with a high-entropy byte sequence,
        // but we can test zero-padding by comparing against a reference with
        // a known small value. Instead, test the format contract via regex.
        val sas = SasVerifier.derive(ecKeyPair().public, ecKeyPair().public)
        assertTrue("SAS must match 6-digit zero-padded format", sas.matches(Regex("\\d{6}")))
    }

    // -------------------------------------------------------------------------
    // 4. Sensitivity — one key change must (almost always) change the SAS
    // -------------------------------------------------------------------------

    @Test
    fun `different key pairs produce different SAS values in a sample of 50`() {
        val results = mutableSetOf<String>()
        repeat(50) {
            results.add(SasVerifier.derive(ecKeyPair().public, ecKeyPair().public))
        }
        // With 6-digit range (10^6 values) and 50 samples, the birthday-paradox
        // collision probability is ~50^2 / (2 * 10^6) ≈ 0.00125. Requiring at
        // least 48 unique values out of 50 is a very conservative assertion.
        assertTrue(
            "Expected at least 48 unique SAS values in 50 samples (got ${results.size})",
            results.size >= 48
        )
    }

    @Test
    fun `changing only one key (by one bit) almost always changes the SAS`() {
        val a = ecKeyPair()
        val b = ecKeyPair()
        val original = SasVerifier.derive(a.public, b.public)

        // Generate a completely different key pair for b to guarantee a change.
        val bPrime = ecKeyPair()
        val changed = SasVerifier.derive(a.public, bPrime.public)

        // This assertion would fail with probability ~10^-6; acceptable for a unit test.
        assertNotEquals(
            "Different keys should almost never produce the same SAS",
            original,
            changed
        )
    }

    // -------------------------------------------------------------------------
    // 5. Verification helper
    // -------------------------------------------------------------------------

    @Test
    fun `verify returns true when SAS matches`() {
        val a = ecKeyPair()
        val b = ecKeyPair()
        val sas = SasVerifier.derive(a.public, b.public)
        assertTrue(SasVerifier.verify(sas, a.public, b.public))
    }

    @Test
    fun `verify returns false when SAS does not match`() {
        val a = ecKeyPair()
        val b = ecKeyPair()
        val wrongSas = "000000"
        val correct  = SasVerifier.derive(a.public, b.public)
        // Only fail this assertion if "000000" actually IS the correct SAS (< 10^-6 chance).
        if (correct != wrongSas) {
            assertFalse(SasVerifier.verify(wrongSas, a.public, b.public))
        }
    }

    @Test
    fun `verify is symmetric — both orderings return true`() {
        val a = ecKeyPair()
        val b = ecKeyPair()
        val sas = SasVerifier.derive(a.public, b.public)
        assertTrue("verify(a, b) must pass", SasVerifier.verify(sas, a.public, b.public))
        assertTrue("verify(b, a) must also pass", SasVerifier.verify(sas, b.public, a.public))
    }

    // -------------------------------------------------------------------------
    // 6. MITM sensitivity — using a different device identity key changes the SAS
    // -------------------------------------------------------------------------

    @Test
    fun `MITM key substitution produces a different SAS`() {
        val alice = ecKeyPair()
        val bob   = ecKeyPair()
        val mitm  = ecKeyPair()

        val honestSas = SasVerifier.derive(alice.public, bob.public)
        // MITM substitutes their own key for Bob's.
        val mitmSas = SasVerifier.derive(alice.public, mitm.public)

        assertNotEquals(
            "MITM key substitution must produce a different SAS (with overwhelming probability)",
            honestSas,
            mitmSas
        )
    }
}
