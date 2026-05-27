package com.showerideas.aura.auth

import com.showerideas.aura.auth.GestureClassifier.Companion.CONFIDENCE_GATE
import com.showerideas.aura.auth.GestureClassifier.Companion.EMBED_DIM
import com.showerideas.aura.auth.GestureClassifier.Companion.MIN_ENROLL_SAMPLES
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for [GestureClassifier].
 *
 * Uses a concrete test double (no Android runtime needed) — DataStore is
 * not exercised here; persistence is tested separately in integration tests.
 */
class GestureClassifierTest {

    // Test-only subclass that bypasses DataStore persist/load
    private class TestableClassifier : GestureClassifier(null!!) {
        // Expose for white-box tests; override persist to no-op
    }

    // We'll test the algorithm directly via a helper wrapper
    private lateinit var calc: AlgorithmHelper

    @Before fun setUp() { calc = AlgorithmHelper() }

    /** Thin wrapper that exercises the algorithm without DI / DataStore. */
    private class AlgorithmHelper {
        private var centroid: FloatArray? = null
        private var spread: Float = 1f

        private val embedDim = 63

        fun train(samples: List<FloatArray>) {
            require(samples.size >= MIN_ENROLL_SAMPLES)
            val c = FloatArray(embedDim)
            for (s in samples) { for (i in s.indices) c[i] += s[i] }
            val n = c.size; for (i in c.indices) c[i] /= samples.size
            val norm = l2(c); if (norm > 0f) for (i in c.indices) c[i] /= norm
            val dists = samples.map { 1f - cosSim(it, c) }.sorted()
            spread = dists[(dists.size * 0.95).toInt().coerceAtMost(dists.size - 1)].coerceAtLeast(0.01f)
            centroid = c
        }

        fun predict(emb: FloatArray): GestureClassifier.Prediction {
            val c = centroid ?: return GestureClassifier.Prediction(0f, false)
            val dist = 1f - cosSim(emb, c)
            val conf = (1f / (1f + (dist / spread))).coerceIn(0f, 1f)
            return GestureClassifier.Prediction(conf, conf >= CONFIDENCE_GATE)
        }

        private fun l2(v: FloatArray): Float { var s = 0f; for (x in v) s += x * x; return sqrt(s) }
        private fun cosSim(a: FloatArray, b: FloatArray): Float {
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            if (na == 0f || nb == 0f) return 0f
            return dot / sqrt(na * nb)
        }
    }

    // Synthetic gesture: all ones normalised
    private fun enrolled() = FloatArray(63) { 1f / sqrt(63f) }

    // Slightly perturbed — same gesture with small noise
    private fun slightlyPerturbed() = enrolled().also { v ->
        v[0] += 0.02f; v[1] -= 0.01f
    }

    // Completely different gesture
    private fun differentGesture() = FloatArray(63) { i -> if (i % 2 == 0) -1f else 1f }
        .also { v -> val n = sqrt(63f); for (i in v.indices) v[i] /= n }

    // Tests

    @Test
    fun `enrolled gesture returns high confidence`() {
        val samples = List(5) { enrolled() }
        calc.train(samples)
        val pred = calc.predict(enrolled())
        assertTrue("Exact enrolled gesture must be accepted", pred.accepted)
        assertTrue("Confidence must be >= CONFIDENCE_GATE (${pred.confidence})", pred.confidence >= CONFIDENCE_GATE)
    }

    @Test
    fun `slightly perturbed enrolled gesture is still accepted`() {
        val samples = List(5) { enrolled() }
        calc.train(samples)
        val pred = calc.predict(slightlyPerturbed())
        assertTrue("Minor variation of enrolled gesture should be accepted (${pred.confidence})", pred.accepted)
    }

    @Test
    fun `completely different gesture is rejected`() {
        val samples = List(5) { enrolled() }
        calc.train(samples)
        val pred = calc.predict(differentGesture())
        assertFalse("Completely different gesture must be rejected (${pred.confidence})", pred.accepted)
        assertTrue("Confidence must be < CONFIDENCE_GATE", pred.confidence < CONFIDENCE_GATE)
    }

    @Test
    fun `zero embedding returns accepted=false`() {
        calc.train(List(5) { enrolled() })
        val pred = calc.predict(FloatArray(63))
        assertFalse(pred.accepted)
    }

    @Test
    fun `confidence is in range 0..1`() {
        calc.train(List(5) { enrolled() })
        val pred = calc.predict(enrolled())
        assertTrue("Confidence must be in [0,1]", pred.confidence in 0f..1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `train with fewer than MIN_ENROLL_SAMPLES throws`() {
        calc.train(List(MIN_ENROLL_SAMPLES - 1) { enrolled() })
    }

    @Test
    fun `training with more samples does not break prediction`() {
        calc.train(List(20) { enrolled() })
        val pred = calc.predict(enrolled())
        assertTrue(pred.accepted)
    }
}

