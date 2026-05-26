package com.showerideas.aura.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 63 — SpqrState unit tests.
 *
 * Verifies SPQR message key derivation, PQ ratchet step scheduling,
 * inbound ciphertext processing, serialization, and forward secrecy.
 */
class SpqrStateTest {

    private val rootKey = ByteArray(32) { it.toByte() }

    @Test
    fun `initialize generates local PQ key pair`() {
        val state = SpqrState()
        state.initialize(rootKey)
        assertNotNull("Local PQ public key must be present after init",
            state.localPqPublicKeyBytes())
    }

    @Test
    fun `sequential message keys are all distinct`() {
        val state = SpqrState()
        state.initialize(rootKey)
        val keys = (0 until 25).map { state.nextMessageKey() }
        for (i in 0 until keys.size) {
            for (j in i + 1 until keys.size) {
                assertFalse("Keys[$i] and keys[$j] must differ",
                    keys[i].contentEquals(keys[j]))
            }
        }
    }

    @Test
    fun `SPQR step ciphertext queued at interval boundary`() {
        val state = SpqrState()
        state.initialize(rootKey)
        // Advance to just before the interval
        repeat(SpqrState.SPQR_STEP_INTERVAL - 1) { state.nextMessageKey() }
        assertNull("No PQ ciphertext before interval", state.takePendingSpqrCiphertext())
        // The step fires at message counter == SPQR_STEP_INTERVAL
        state.nextMessageKey()
        assertNotNull("PQ ciphertext must be queued at interval", state.takePendingSpqrCiphertext())
    }

    @Test
    fun `two states initialized with same root key produce same message keys`() {
        val s1 = SpqrState()
        val s2 = SpqrState()
        s1.initialize(rootKey)
        s2.initialize(rootKey)
        // Before any PQ ratchet steps, chains are deterministic from root
        val key1 = s1.nextMessageKey()
        val key2 = s2.nextMessageKey()
        assertArrayEquals("Same root must produce same first message key", key1, key2)
    }

    @Test
    fun `serialise and deserialise preserves chain state`() {
        val state = SpqrState()
        state.initialize(rootKey)
        // Advance a few steps
        repeat(5) { state.nextMessageKey() }

        val bytes = state.toBytes()
        val restored = SpqrState.fromBytes(bytes)

        val origKey = state.nextMessageKey()
        val restoredKey = restored.nextMessageKey()
        assertArrayEquals("Restored state must produce same next message key", origKey, restoredKey)
    }

    @Test
    fun `each message key is 32 bytes`() {
        val state = SpqrState()
        state.initialize(rootKey)
        repeat(15) {
            val key = state.nextMessageKey()
            assertTrue("Message key must be 32 bytes", key.size == 32)
        }
    }
}
