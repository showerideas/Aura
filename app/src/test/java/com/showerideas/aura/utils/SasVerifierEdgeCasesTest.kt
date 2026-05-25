package com.showerideas.aura.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Edge-case tests for [SasVerifier] — Phase 5.2 coverage hardening.
 *
 * The primary [SasVerifierTest] validates determinism, range, and uniqueness.
 * This file focuses on:
 *  - Ordering invariance (A,B == B,A)
 *  - Output format (exactly 6 digits, no leading zeros lost)
 *  - SHA-256 avalanche effect (tiny key change → completely different pin)
 *  - Multiple key pairs produce unique pins with high probability
 */
class SasVerifierEdgeCasesTest {

    private fun generateEcKey() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    // -------------------------------------------------------------------------
    // Format invariants
    // -------------------------------------------------------------------------

    @Test
    fun `derived pin is exactly 6 characters`() {
        val kp1 = generateEcKey()
        val kp2 = generateEcKey()
        val pin = SasVerifier.derive(kp1.public, kp2.public)
        assertEquals("SAS pin must be exactly 6 characters", 6, pin.length)
    }

    @Test
    fun `derived pin contains only digits`() {
        val kp1 = generateEcKey()
        val kp2 = generateEcKey()
        val pin = SasVerifier.derive(kp1.public, kp2.public)
        assertTrue("SAS pin must contain only decimal digits", pin.all { it.isDigit() })
    }

    @Test
    fun `derived pin is padded to 6 digits with leading zeros if needed`() {
        // We can't control the exact output, but we can verify 100 samples are all 6 chars
        repeat(100) {
            val kp1 = generateEcKey()
            val kp2 = generateEcKey()
            val pin = SasVerifier.derive(kp1.public, kp2.public)
            assertEquals("Every pin must be 6 chars", 6, pin.length)
        }
    }

    // -------------------------------------------------------------------------
    // Ordering invariance — derive(A, B) == derive(B, A)
    // -------------------------------------------------------------------------

    @Test
    fun `derive is commutative - same result regardless of key order`() {
        val kp1 = generateEcKey()
        val kp2 = generateEcKey()
        val pin1 = SasVerifier.derive(kp1.public, kp2.public)
        val pin2 = SasVerifier.derive(kp2.public, kp1.public)
        assertEquals("SAS derivation must be commutative (order independent)", pin1, pin2)
    }

    @Test
    fun `derive is commutative for 20 key pairs`() {
        repeat(20) {
            val kp1 = generateEcKey()
            val kp2 = generateEcKey()
            assertEquals(
                "derive(A,B) must equal derive(B,A)",
                SasVerifier.derive(kp1.public, kp2.public),
                SasVerifier.derive(kp2.public, kp1.public)
            )
        }
    }

    // -------------------------------------------------------------------------
    // Determinism — same inputs always produce same output
    // -------------------------------------------------------------------------

    @Test
    fun `derive is deterministic - calling twice with same keys gives same pin`() {
        val kp1 = generateEcKey()
        val kp2 = generateEcKey()
        val pin1 = SasVerifier.derive(kp1.public, kp2.public)
        val pin2 = SasVerifier.derive(kp1.public, kp2.public)
        assertEquals("SAS derivation must be deterministic", pin1, pin2)
    }

    // -------------------------------------------------------------------------
    // Avalanche effect — different keys → different pins (with high probability)
    // -------------------------------------------------------------------------

    @Test
    fun `derive produces different pins for different key pairs`() {
        // Technically could collide but probability is 1/1,000,000 per pair
        val kp1 = generateEcKey()
        val kp2 = generateEcKey()
        val kp3 = generateEcKey()  // completely different third key
        val pin12 = SasVerifier.derive(kp1.public, kp2.public)
        val pin13 = SasVerifier.derive(kp1.public, kp3.public)
        assertNotEquals("Different key pairs should (almost certainly) produce different pins",
            pin12, pin13)
    }

    @Test
    fun `same key paired with itself produces a valid pin`() {
        val kp = generateEcKey()
        // Edge case: same key used as both parties (degenerate session)
        val pin = SasVerifier.derive(kp.public, kp.public)
        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })
    }

    // -------------------------------------------------------------------------
    // Range gate — pin must be in [0, 999_999]
    // -------------------------------------------------------------------------

    @Test
    fun `derived pin as integer is within 0 to 999999`() {
        repeat(50) {
            val kp1 = generateEcKey()
            val kp2 = generateEcKey()
            val pin = SasVerifier.derive(kp1.public, kp2.public)
            val intValue = pin.toInt()
            assertTrue("Pin value must be >= 0", intValue >= 0)
            assertTrue("Pin value must be <= 999999", intValue <= 999_999)
        }
    }

    // -------------------------------------------------------------------------
    // Uniqueness across many samples
    // -------------------------------------------------------------------------

    @Test
    fun `derive produces diverse pins across 30 random key pairs`() {
        val pins = (1..30).map {
            val kp1 = generateEcKey()
            val kp2 = generateEcKey()
            SasVerifier.derive(kp1.public, kp2.public)
        }.toSet()
        // With 1-in-1,000,000 collision probability per pair, 30 unique values is near-certain
        assertTrue("Expected at least 28 unique pins out of 30", pins.size >= 28)
    }
}
