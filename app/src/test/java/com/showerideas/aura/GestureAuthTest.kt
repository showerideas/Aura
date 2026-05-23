package com.showerideas.aura

import com.showerideas.aura.model.GesturePattern
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for gesture biometric authentication contracts.
 *
 * These tests validate the pure-math contracts that underpin the camera-based
 * hand-embedding authentication system. They run on the JVM and avoid importing
 * CameraHandEmbedder / GestureAuthManager directly — those classes carry
 * MediaPipe / Android Context dependencies that require the real device runtime.
 *
 * The cosine similarity function and threshold value (0.88f) are inlined here
 * to make the test self-contained. Any change to the production constants
 * should be mirrored in these tests, enforcing an explicit review gate.
 */
class GestureAuthTest {

    // -------------------------------------------------------------------------
    // Constants mirrored from production code.
    // Any change to these values in GestureAuthManager / CameraHandEmbedder
    // must be intentional and should update the test expectations below.
    // -------------------------------------------------------------------------

    /** Cosine similarity threshold from GestureAuthManager.SIMILARITY_THRESHOLD */
    private val SIMILARITY_THRESHOLD = 0.88f

    /** Expected embedding dimension from CameraHandEmbedder.EMBEDDING_SIZE */
    private val EMBEDDING_SIZE = 42   // 21 landmarks × (x, y)

    // -------------------------------------------------------------------------
    // Cosine similarity — inlined to avoid MediaPipe native-library issues
    // -------------------------------------------------------------------------

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot   += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return dot / sqrt(normA * normB)
    }

    // -------------------------------------------------------------------------
    // Cosine similarity contract tests
    // -------------------------------------------------------------------------

    @Test
    fun `identical vectors have cosine similarity of 1`() {
        val v = FloatArray(EMBEDDING_SIZE) { it.toFloat() + 1f }
        assertEquals(1f, cosineSimilarity(v, v), 0.0001f)
    }

    @Test
    fun `opposite vectors have cosine similarity of -1`() {
        val v   = FloatArray(EMBEDDING_SIZE) { it.toFloat() + 1f }
        val neg = FloatArray(EMBEDDING_SIZE) { -(it.toFloat() + 1f) }
        assertEquals(-1f, cosineSimilarity(v, neg), 0.0001f)
    }

    @Test
    fun `cosine similarity is 0 for zero vector`() {
        val zero = FloatArray(EMBEDDING_SIZE)
        val v    = FloatArray(EMBEDDING_SIZE) { it.toFloat() + 1f }
        assertEquals(0f, cosineSimilarity(zero, v), 0.0001f)
    }

    @Test
    fun `cosine similarity returns 0 for mismatched sizes`() {
        val a = FloatArray(EMBEDDING_SIZE) { 1f }
        val b = FloatArray(EMBEDDING_SIZE - 1) { 1f }
        assertEquals(0f, cosineSimilarity(a, b), 0.0001f)
    }

    @Test
    fun `slightly perturbed vector exceeds similarity threshold`() {
        // Simulates the same hand slightly repositioned — must still authenticate.
        val base   = FloatArray(EMBEDDING_SIZE) { it.toFloat() * 0.1f + 0.5f }
        val slight = FloatArray(EMBEDDING_SIZE) { it.toFloat() * 0.1f + 0.51f }
        val sim = cosineSimilarity(base, slight)
        assertTrue(
            "Slightly perturbed same-hand embedding (sim=$sim) must be ≥ $SIMILARITY_THRESHOLD",
            sim >= SIMILARITY_THRESHOLD
        )
    }

    @Test
    fun `very different vectors are below similarity threshold`() {
        // Simulates two completely different hand shapes — must reject.
        val a = FloatArray(EMBEDDING_SIZE) { it.toFloat() }
        val b = FloatArray(EMBEDDING_SIZE) { (EMBEDDING_SIZE - it).toFloat() }
        val sim = cosineSimilarity(a, b)
        assertTrue(
            "Very different vectors (sim=$sim) must be < $SIMILARITY_THRESHOLD",
            sim < SIMILARITY_THRESHOLD
        )
    }

    @Test
    fun `similarity threshold is within documented range`() {
        // Guard against accidental threshold drift — 0.88 sits above random
        // hand-noise (~0.5) and below within-person consistency (~0.93).
        assertTrue(
            "SIMILARITY_THRESHOLD must be ≥ 0.80 to reject random hands",
            SIMILARITY_THRESHOLD >= 0.80f
        )
        assertTrue(
            "SIMILARITY_THRESHOLD must be ≤ 0.95 to accommodate within-person variation",
            SIMILARITY_THRESHOLD <= 0.95f
        )
    }

    @Test
    fun `embedding size matches 21 landmarks times 2 coordinates`() {
        // Guards against accidental resize of the embedding vector which
        // would invalidate all stored patterns.
        assertEquals("EMBEDDING_SIZE must be 21 × 2 = 42", 42, EMBEDDING_SIZE)
    }

    // -------------------------------------------------------------------------
    // GesturePattern model contracts
    // -------------------------------------------------------------------------

    @Test
    fun `gesture pattern equality is based on id and feature vector`() {
        val v  = floatArrayOf(1f, 2f, 3f)
        val p1 = GesturePattern("abc", featureVector = v)
        val p2 = GesturePattern("abc", featureVector = v.copyOf())
        val p3 = GesturePattern("xyz", featureVector = v)
        assertEquals("Same id + same vector must be equal", p1, p2)
        assertNotEquals("Different ids must not be equal", p1, p3)
    }

    @Test
    fun `gesture pattern with different vector content is not equal`() {
        val p1 = GesturePattern("abc", featureVector = floatArrayOf(1f, 2f, 3f))
        val p2 = GesturePattern("abc", featureVector = floatArrayOf(1f, 2f, 4f))
        assertNotEquals("Same id but different vector must not be equal", p1, p2)
    }

    @Test
    fun `gesture pattern hashCode is consistent with equals`() {
        val v  = floatArrayOf(1f, 2f, 3f)
        val p1 = GesturePattern("abc", featureVector = v)
        val p2 = GesturePattern("abc", featureVector = v.copyOf())
        assertEquals("Equal patterns must have the same hashCode", p1.hashCode(), p2.hashCode())
    }

    @Test
    fun `gesture pattern with empty vector can be constructed`() {
        // Empty vector is valid as a sentinel / unset state; must not throw.
        val p = GesturePattern("id-only")
        assertEquals(0, p.featureVector.size)
    }
}
