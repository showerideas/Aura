package com.showerideas.aura.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Task 55 — TorRelayManager unit tests.
 *
 * Tests ZK slot commitment generation, validation, and session ID generation.
 */
class TorRelayManagerTest {

    private val mgr = TorRelayManager()
    private val rng = SecureRandom()

    @Test
    fun `slot commitment is 32 hex characters`() {
        val key = rng.generateSeed(32)
        val sid = mgr.generateSessionId()
        val commitment = mgr.generateSlotCommitment(key, sid)
        assertEquals("Commitment must be 32 hex chars", 32, commitment.length)
        assertTrue("Commitment must be hex", commitment.all { it.isLetterOrDigit() })
    }

    @Test
    fun `slot commitment is deterministic for same inputs`() {
        val key = rng.generateSeed(32)
        val sid = rng.generateSeed(32)
        val c1 = mgr.generateSlotCommitment(key, sid)
        val c2 = mgr.generateSlotCommitment(key, sid)
        assertEquals("Same inputs must produce same commitment", c1, c2)
    }

    @Test
    fun `different session keys produce different commitments`() {
        val k1 = rng.generateSeed(32)
        val k2 = rng.generateSeed(32)
        val sid = rng.generateSeed(32)
        assertNotEquals("Different keys must produce different commitments",
            mgr.generateSlotCommitment(k1, sid), mgr.generateSlotCommitment(k2, sid))
    }

    @Test
    fun `different session IDs produce different commitments`() {
        val key = rng.generateSeed(32)
        val s1 = rng.generateSeed(32)
        val s2 = rng.generateSeed(32)
        assertNotEquals("Different session IDs must produce different commitments",
            mgr.generateSlotCommitment(key, s1), mgr.generateSlotCommitment(key, s2))
    }

    @Test
    fun `valid commitment passes validation`() {
        val key = rng.generateSeed(32)
        val sid = mgr.generateSessionId()
        val c = mgr.generateSlotCommitment(key, sid)
        assertTrue("Generated commitment must pass validation", mgr.isValidSlotCommitment(c))
    }

    @Test
    fun `short commitment fails validation`() {
        assertFalse("Short string must fail", mgr.isValidSlotCommitment("abc"))
    }

    @Test
    fun `session ID is 32 bytes`() {
        val sid = mgr.generateSessionId()
        assertEquals(32, sid.size)
    }

    @Test
    fun `key size validation enforced`() {
        var threw = false
        try { mgr.generateSlotCommitment(ByteArray(16), ByteArray(32)) }
        catch (_: Exception) { threw = true }
        assertTrue("Short key must throw", threw)
    }
}
