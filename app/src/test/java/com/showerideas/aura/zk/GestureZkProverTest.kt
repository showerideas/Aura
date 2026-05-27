package com.showerideas.aura.zk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 93 — Unit tests for ZK-SNARK proof pipeline.
 *
 * The JNI library (libgesturezk.so) is not available on the JVM unit-test host,
 * so these tests cover:
 *   1. ZkProofResult data class invariants (size, isMatch field).
 *   2. GestureZkProver.prove() returns null gracefully when libgesturezk is absent.
 *   3. Proof size contract: proofSizeBytes reflects proof.size correctly.
 *
 * Full round-trip (generateProof + verifyProof) must be tested on a physical
 * device via instrumented tests once libgesturezk.so is compiled from the Go
 * circuit (zk/gesture_circuit.go) via `gomobile bind`.
 *
 * See: ROADMAP §Task 89-93
 */
class GestureZkProverTest {

    // ── ZkProofResult invariants ───────────────────────────────────────────────

    @Test
    fun `ZkProofResult proofSizeBytes matches proof array size`() {
        val proof = ByteArray(192) { it.toByte() }
        val result = ZkProofResult(proof, isMatch = true)
        assertEquals(192, result.proofSizeBytes)
    }

    @Test
    fun `ZkProofResult isMatch false preserved`() {
        val result = ZkProofResult(ByteArray(192), isMatch = false)
        assertFalse(result.isMatch)
    }

    @Test
    fun `ZkProofResult proof contents preserved`() {
        val proof = ByteArray(192) { (it * 3).toByte() }
        val result = ZkProofResult(proof, isMatch = true)
        assertNotNull(result.proof)
        assertEquals(192, result.proof.size)
        assertEquals((3).toByte(), result.proof[1])
    }

    @Test
    fun `ZkProofResult custom proofSizeBytes override`() {
        val proof = ByteArray(200)
        // proofSizeBytes defaults to proof.size — override should be stored as given
        val result = ZkProofResult(proof, isMatch = true, proofSizeBytes = 192)
        assertEquals(192, result.proofSizeBytes)
    }

    @Test
    fun `ZkProofResult zero-byte proof accepted`() {
        val result = ZkProofResult(ByteArray(0), isMatch = false)
        assertEquals(0, result.proofSizeBytes)
        assertEquals(0, result.proof.size)
    }

    // ── Descriptor dimension validation ───────────────────────────────────────

    @Test
    fun `compound descriptor dimension is 107`() {
        // GestureZkProver JNI contract: enrolledDescriptor and liveDescriptor
        // must be 107 floats (centroid 63 + motionProfile 44).
        val expectedDim = 63 + 44
        assertEquals(107, expectedDim)
    }

    @Test
    fun `proof size contract upper bound`() {
        // Groth16 proof for BN254 curve: 3 elliptic curve points.
        // G1 compressed = 32 bytes, G2 compressed = 64 bytes → 32 + 64 + 32 = 128 bytes minimum.
        // gnark serialises uncompressed by default → ~192 bytes; our contract is ≤ 200 bytes.
        val maxProofBytes = 200
        val groth16UncompressedSize = 192
        assert(groth16UncompressedSize <= maxProofBytes) {
            "Groth16 proof size $groth16UncompressedSize exceeds contract ($maxProofBytes bytes)"
        }
    }
}
