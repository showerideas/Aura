package com.showerideas.aura.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Phase 9.4 — Unit tests for [GestureClassifier].
 * Uses synthetic embeddings to verify training and prediction logic.
 */
class GestureClassifierTest {

    private fun randomEmbedding(seed: Float, dim: Int = 63): FloatArray =
        FloatArray(dim) { i -> (seed + i * 0.01f) % 1.0f }

    @Test
    fun train_producesNonEmptyBytes() {
        val samples = (1..5).map { randomEmbedding(it * 0.1f) }
        val model = GestureClassifier.train(samples)
        assertTrue("Trained model must be non-empty", model.isNotEmpty())
    }

    @Test
    fun predict_sameEmbeddingAsTraining_highScore() {
        val enrollSample = randomEmbedding(0.5f)
        val samples = listOf(enrollSample, randomEmbedding(0.51f), randomEmbedding(0.49f))
        val model = GestureClassifier.train(samples)
        val score = GestureClassifier.predict(enrollSample, model)
        assertTrue("Enrolled embedding should score > 0.9 against its own centroid", score > 0.9f)
    }

    @Test
    fun classify_enrolledEmbedding_returnsTrue() {
        val sample = randomEmbedding(0.3f)
        val model = GestureClassifier.train(List(5) { randomEmbedding(0.3f + it * 0.001f) })
        assertTrue("Enrolled-like embedding should classify as genuine",
                   GestureClassifier.classify(sample, model))
    }

    @Test
    fun classify_veryDifferentEmbedding_returnsFalse() {
        val model = GestureClassifier.train(List(5) { randomEmbedding(0.3f + it * 0.001f) })
        val impostor = randomEmbedding(0.9f, 63)
        assertFalse("Very different embedding should not classify as genuine",
                    GestureClassifier.classify(impostor, model))
    }

    @Test
    fun serializedModel_correctDimension() {
        val dim = 63
        val samples = List(3) { randomEmbedding(it * 0.1f, dim) }
        val model = GestureClassifier.train(samples)
        // Expected size: 4 (dim) + 63*4 (centroid) + 4 (threshold) = 260 bytes
        val expectedSize = 4 + dim * 4 + 4
        assertEquals("Serialized model must be $expectedSize bytes", expectedSize, model.size)
    }
}
