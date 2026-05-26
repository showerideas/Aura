package com.showerideas.aura.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T21 — Coverage milestone 70%: unit tests for [TemporalGestureClassifier].
 *
 * Tests the 30-frame sliding window, motion-profile analysis, and spoof detection.
 */
class TemporalGestureClassifierTest {

    private lateinit var classifier: TemporalGestureClassifier

    @Before
    fun setUp() {
        classifier = TemporalGestureClassifier()
    }

    // Helpers -----------------------------------------------------------------

    /** 63-float enrolled pattern (centroid). */
    private fun enrolledPattern(): FloatArray = FloatArray(63) { it * 0.01f }

    /**
     * Produce an embedding with cosine similarity > SPATIAL_THRESHOLD to [base].
     * Adds slight perturbation so motion floor is satisfied.
     */
    private fun authenticEmbedding(base: FloatArray, frame: Int): FloatArray {
        return FloatArray(63) { i -> base[i] + 0.002f * (frame % 5 + 1) }
    }

    /**
     * Static embedding identical to [base] every frame — triggers spoof.
     */
    private fun staticEmbedding(base: FloatArray) = base.copyOf()

    /**
     * Embedding with very low similarity to [base] — triggers spoof.
     */
    private fun spoofEmbedding(base: FloatArray): FloatArray =
        FloatArray(63) { if (it < 32) 1.0f - base[it] else -base[it] }

    // Tests -------------------------------------------------------------------

    @Test
    fun `fewer than WINDOW_FRAMES returns CollectingFrames`() {
        val enrolled = enrolledPattern()
        repeat(TemporalGestureClassifier.WINDOW_FRAMES - 1) { i ->
            val result = classifier.collect(authenticEmbedding(enrolled, i), enrolled)
            assertTrue("Frame $i should be Collecting",
                result is TemporalGestureClassifier.TemporalResult.CollectingFrames)
        }
    }

    @Test
    fun `WINDOW_FRAMES authentic frames produces Authentic`() {
        val enrolled = enrolledPattern()
        var result: TemporalGestureClassifier.TemporalResult =
            TemporalGestureClassifier.TemporalResult.CollectingFrames(0, TemporalGestureClassifier.WINDOW_FRAMES)
        repeat(TemporalGestureClassifier.WINDOW_FRAMES + 5) { i ->
            result = classifier.collect(authenticEmbedding(enrolled, i), enrolled)
        }
        assertTrue("Should be Authentic after full window, got $result",
            result is TemporalGestureClassifier.TemporalResult.Authentic)
    }

    @Test
    fun `static embeddings detect Spoof`() {
        val enrolled = enrolledPattern()
        var result: TemporalGestureClassifier.TemporalResult =
            TemporalGestureClassifier.TemporalResult.CollectingFrames(0, TemporalGestureClassifier.WINDOW_FRAMES)
        repeat(TemporalGestureClassifier.WINDOW_FRAMES + 1) {
            result = classifier.collect(staticEmbedding(enrolled), enrolled)
        }
        assertTrue("Static embeddings should be Spoof, got $result",
            result is TemporalGestureClassifier.TemporalResult.Spoof)
    }

    @Test
    fun `low similarity embeddings detect Spoof`() {
        val enrolled = enrolledPattern()
        var result: TemporalGestureClassifier.TemporalResult =
            TemporalGestureClassifier.TemporalResult.CollectingFrames(0, TemporalGestureClassifier.WINDOW_FRAMES)
        repeat(TemporalGestureClassifier.WINDOW_FRAMES + 1) { i ->
            result = classifier.collect(spoofEmbedding(enrolled), enrolled)
        }
        assertTrue("Low-similarity embeddings should be Spoof or Inconclusive, got $result",
            result is TemporalGestureClassifier.TemporalResult.Spoof ||
            result is TemporalGestureClassifier.TemporalResult.Inconclusive)
    }

    @Test
    fun `reset clears window`() {
        val enrolled = enrolledPattern()
        repeat(TemporalGestureClassifier.WINDOW_FRAMES / 2) { i ->
            classifier.collect(authenticEmbedding(enrolled, i), enrolled)
        }
        classifier.reset()
        val result = classifier.collect(authenticEmbedding(enrolled, 0), enrolled)
        assertTrue("After reset, frame 1 should be Collecting",
            result is TemporalGestureClassifier.TemporalResult.CollectingFrames)
    }

    @Test
    fun `WINDOW_FRAMES constant is 30`() {
        assertEquals(30, TemporalGestureClassifier.WINDOW_FRAMES)
    }

    @Test
    fun `SPATIAL_THRESHOLD is 0_88f`() {
        assertEquals(0.88f, TemporalGestureClassifier.SPATIAL_THRESHOLD)
    }
}
