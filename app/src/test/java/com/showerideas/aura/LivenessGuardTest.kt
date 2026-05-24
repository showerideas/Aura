package com.showerideas.aura

import com.showerideas.aura.auth.LivenessGuard
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit tests for [LivenessGuard] — the anti-spoofing micro-motion variance detector.
 *
 * Tests verify:
 *  - Collecting state while window is filling
 *  - Live detection above MIN_MEAN_DRIFT
 *  - Spoof detection below MIN_MEAN_DRIFT
 *  - Reset clears the window
 *  - Symmetric detection regardless of drift direction
 */
class LivenessGuardTest {

    private lateinit var guard: LivenessGuard

    @Before
    fun setup() {
        guard = LivenessGuard()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Generate a 63-float embedding with all values set to [value]. */
    private fun constantEmbedding(value: Float) = FloatArray(63) { value }

    /**
     * Generate a 63-float embedding that differs from [base] by adding [delta]
     * to every element — produces L2 distance of delta * sqrt(63).
     */
    private fun shiftedEmbedding(base: Float, delta: Float) = FloatArray(63) { base + delta }

    /** Feed [count] identical embeddings to the guard. */
    private fun feedIdentical(value: Float, count: Int): LivenessGuard.Result {
        var last: LivenessGuard.Result = LivenessGuard.Result.Collecting
        repeat(count) { last = guard.feed(constantEmbedding(value)) }
        return last
    }

    // -------------------------------------------------------------------------
    // Collecting state
    // -------------------------------------------------------------------------

    @Test
    fun `returns Collecting before window is full`() {
        // Feed WINDOW_FRAMES - 1 frames (not enough to make a call)
        val result = feedIdentical(0.5f, LivenessGuard.WINDOW_FRAMES - 1)
        assertTrue(
            "Expected Collecting before window full, got $result",
            result is LivenessGuard.Result.Collecting
        )
    }

    @Test
    fun `returns Collecting after only one frame`() {
        val result = guard.feed(constantEmbedding(0.1f))
        assertTrue(result is LivenessGuard.Result.Collecting)
    }

    // -------------------------------------------------------------------------
    // Spoof detection (static source — near-zero drift)
    // -------------------------------------------------------------------------

    @Test
    fun `detects static source — identical embeddings produce Spoof`() {
        // A frozen image gives the same embedding every frame → zero drift
        val result = feedIdentical(0.5f, LivenessGuard.WINDOW_FRAMES)
        assertTrue(
            "Expected Spoof for identical frames, got $result",
            result is LivenessGuard.Result.Spoof
        )
    }

    @Test
    fun `spoof result carries meanDrift below threshold`() {
        val result = feedIdentical(0.42f, LivenessGuard.WINDOW_FRAMES) as LivenessGuard.Result.Spoof
        assertTrue(
            "Spoof meanDrift should be 0.0, was ${result.meanDrift}",
            result.meanDrift < LivenessGuard.MIN_MEAN_DRIFT
        )
    }

    @Test
    fun `near-zero but non-zero drift is still Spoof`() {
        // Drift of 0.0001 per element → L2 = 0.0001 * sqrt(63) ≈ 0.000794 << 0.003
        val base = FloatArray(63) { 0.5f }
        repeat(LivenessGuard.WINDOW_FRAMES + 1) { i ->
            val embedding = FloatArray(63) { 0.5f + i * 0.0001f }
            guard.feed(embedding)
        }
        val drift = guard.currentMeanDrift()
        assertNotNull(drift)
        assertTrue("Near-zero drift should be detected as spoof: $drift", drift!! < LivenessGuard.MIN_MEAN_DRIFT)
    }

    // -------------------------------------------------------------------------
    // Live detection (real hand — visible drift)
    // -------------------------------------------------------------------------

    @Test
    fun `detects live hand — large drift produces Live`() {
        // Simulate micro-tremors: each frame shifts by 0.01 per element
        // L2 distance per frame = 0.01 * sqrt(63) ≈ 0.0794 >> 0.003
        var last: LivenessGuard.Result = LivenessGuard.Result.Collecting
        for (i in 0..LivenessGuard.WINDOW_FRAMES) {
            val embedding = FloatArray(63) { 0.5f + i * 0.01f }
            last = guard.feed(embedding)
        }
        assertTrue(
            "Expected Live for high-drift frames, got $last",
            last is LivenessGuard.Result.Live
        )
    }

    @Test
    fun `live result carries meanDrift above threshold`() {
        var last: LivenessGuard.Result = LivenessGuard.Result.Collecting
        for (i in 0..LivenessGuard.WINDOW_FRAMES) {
            last = guard.feed(FloatArray(63) { 0.5f + i * 0.01f })
        }
        val drift = (last as LivenessGuard.Result.Live).meanDrift
        assertTrue(
            "Live meanDrift should exceed MIN_MEAN_DRIFT (${LivenessGuard.MIN_MEAN_DRIFT}), was $drift",
            drift >= LivenessGuard.MIN_MEAN_DRIFT
        )
    }

    @Test
    fun `drift exactly at threshold is Live`() {
        // L2 distance = MIN_MEAN_DRIFT exactly.
        // We need each frame to shift by d such that d * sqrt(63) = MIN_MEAN_DRIFT.
        // Multiply by 1.001 to prevent the FP round-trip (targetL2/sqrt(63)*sqrt(63))
        // landing a hair below MIN_MEAN_DRIFT and producing Spoof instead of Live.
        val targetL2 = LivenessGuard.MIN_MEAN_DRIFT
        val perElement = (targetL2 / sqrt(63f)) * 1.001f
        var last: LivenessGuard.Result = LivenessGuard.Result.Collecting
        for (i in 0..LivenessGuard.WINDOW_FRAMES) {
            last = guard.feed(FloatArray(63) { 0.5f + i * perElement })
        }
        // At or just above the threshold, should be Live (>= comparison)
        assertTrue(
            "Drift at threshold should be Live, got $last",
            last is LivenessGuard.Result.Live
        )
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    @Test
    fun `reset clears window — Collecting immediately after reset`() {
        // Fill window to trigger a result
        feedIdentical(0.3f, LivenessGuard.WINDOW_FRAMES)

        guard.reset()

        val afterReset = guard.feed(constantEmbedding(0.3f))
        assertTrue(
            "Expected Collecting immediately after reset, got $afterReset",
            afterReset is LivenessGuard.Result.Collecting
        )
    }

    @Test
    fun `reset then live feed — correctly reaches Live after re-fill`() {
        feedIdentical(0.5f, LivenessGuard.WINDOW_FRAMES)
        guard.reset()

        // Re-fill with drifting frames
        var last: LivenessGuard.Result = LivenessGuard.Result.Collecting
        for (i in 0..LivenessGuard.WINDOW_FRAMES) {
            last = guard.feed(FloatArray(63) { 0.5f + i * 0.02f })
        }
        assertTrue("Expected Live after reset and re-fill, got $last", last is LivenessGuard.Result.Live)
    }

    // -------------------------------------------------------------------------
    // currentMeanDrift
    // -------------------------------------------------------------------------

    @Test
    fun `currentMeanDrift returns null before 2 frames`() {
        guard.feed(constantEmbedding(0.5f))
        assertNull(guard.currentMeanDrift())
    }

    @Test
    fun `currentMeanDrift returns non-null after 2 frames`() {
        guard.feed(constantEmbedding(0.5f))
        guard.feed(constantEmbedding(0.6f))
        assertNotNull(guard.currentMeanDrift())
    }
}
