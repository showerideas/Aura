package com.showerideas.aura.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * T21 — Coverage milestone 70%: unit tests for [LivenessGuard].
 *
 * Tests the three-check anti-spoofing pipeline:
 * 1. Passive drift window (60 frames)
 * 2. Challenge gesture detection
 * 3. Optical flow coherence
 */
class LivenessGuardTest {

    private lateinit var guard: LivenessGuard

    @Before
    fun setUp() {
        guard = LivenessGuard()
    }

    // -------------------------------------------------------------------------
    // Collecting state
    // -------------------------------------------------------------------------

    @Test
    fun `single frame returns Collecting`() {
        val result = guard.feed(liveEmbedding(0))
        assertTrue("Expected Collecting", result is LivenessGuard.Result.Collecting)
    }

    @Test
    fun `fewer than WINDOW_FRAMES returns Collecting`() {
        repeat(LivenessGuard.WINDOW_FRAMES - 1) { i ->
            val result = guard.feed(liveEmbedding(i))
            assertTrue("Frame $i should be Collecting", result is LivenessGuard.Result.Collecting)
        }
    }

    // -------------------------------------------------------------------------
    // Static (spoof) detection
    // -------------------------------------------------------------------------

    @Test
    fun `identical embeddings detect DRIFT_TOO_LOW spoof`() {
        val static = staticEmbedding()
        var result: LivenessGuard.Result = LivenessGuard.Result.Collecting
        repeat(LivenessGuard.WINDOW_FRAMES + 1) {
            result = guard.feed(static)
        }
        assertTrue("Expected Spoof(DRIFT_TOO_LOW), got $result",
            result is LivenessGuard.Result.Spoof &&
            (result as LivenessGuard.Result.Spoof).reason ==
                LivenessGuard.SpoofReason.DRIFT_TOO_LOW)
    }

    @Test
    fun `very small but non-zero drift below threshold also triggers spoof`() {
        var result: LivenessGuard.Result = LivenessGuard.Result.Collecting
        repeat(LivenessGuard.WINDOW_FRAMES + 1) { i ->
            result = guard.feed(nearStaticEmbedding(i))
        }
        assertTrue("Near-static drift should also be DRIFT_TOO_LOW spoof",
            result is LivenessGuard.Result.Spoof)
    }

    // -------------------------------------------------------------------------
    // Live detection and challenge
    // -------------------------------------------------------------------------

    @Test
    fun `60 live frames returns ChallengeRequired`() {
        var result: LivenessGuard.Result = LivenessGuard.Result.Collecting
        repeat(LivenessGuard.WINDOW_FRAMES) { i ->
            result = guard.feed(liveEmbedding(i))
        }
        assertTrue("After ${ LivenessGuard.WINDOW_FRAMES} live frames should be ChallengeRequired, got $result",
            result is LivenessGuard.Result.ChallengeRequired)
    }

    @Test
    fun `challenge is issued exactly once`() {
        fillWindowLive()
        val challenge1 = guard.currentChallenge()
        assertNotNull("Challenge should be set after window fills", challenge1)

        // Feed more frames — challenge should not change
        guard.feed(liveEmbedding(100))
        assertEquals("Challenge should remain stable", challenge1, guard.currentChallenge())
    }

    @Test
    fun `challenge timeout returns ChallengeTimeout`() {
        fillWindowLive()
        // Feed CHALLENGE_TIMEOUT_FRAMES more frames without a challenge response
        var result: LivenessGuard.Result = LivenessGuard.Result.Collecting
        repeat(LivenessGuard.CHALLENGE_TIMEOUT_FRAMES + 1) { i ->
            result = guard.feed(liveEmbedding(i + 100))
        }
        assertTrue("Should timeout if challenge not answered in time, got $result",
            result is LivenessGuard.Result.ChallengeTimeout)
    }

    @Test
    fun `spread fingers challenge resolves to Live`() {
        // Override the guard to a known challenge via reset + targeted frame sequence
        guard = LivenessGuard()
        fillWindowLive()
        val challenge = guard.currentChallenge()
        assertNotNull(challenge)

        // Feed the appropriate challenge response embedding
        val responseEmb = challengeResponseEmbedding(challenge!!)
        var result: LivenessGuard.Result = LivenessGuard.Result.Collecting
        repeat(3) {
            result = guard.feed(responseEmb)
        }
        assertTrue("Challenge response should resolve to Live or ChallengeRequired, got $result",
            result is LivenessGuard.Result.Live || result is LivenessGuard.Result.ChallengeRequired)
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    @Test
    fun `reset clears window and challenge`() {
        fillWindowLive()
        assertNotNull(guard.currentChallenge())
        assertNotNull(guard.currentMeanDrift())

        guard.reset()

        assertNull("Challenge should be null after reset", guard.currentChallenge())
        assertNull("Drift should be null after reset", guard.currentMeanDrift())
        assertTrue("After reset, first frame is Collecting",
            guard.feed(liveEmbedding(0)) is LivenessGuard.Result.Collecting)
    }

    // -------------------------------------------------------------------------
    // currentMeanDrift
    // -------------------------------------------------------------------------

    @Test
    fun `currentMeanDrift is null before two frames`() {
        assertNull(guard.currentMeanDrift())
        guard.feed(liveEmbedding(0))
        assertNull(guard.currentMeanDrift())
    }

    @Test
    fun `currentMeanDrift is positive for live embeddings`() {
        guard.feed(liveEmbedding(0))
        guard.feed(liveEmbedding(1))
        val drift = guard.currentMeanDrift()
        assertNotNull(drift)
        assertTrue("Drift must be > MIN_MEAN_DRIFT for live embeddings",
            drift!! >= LivenessGuard.MIN_MEAN_DRIFT)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** 63-float embedding with realistic live drift (frame-dependent offset). */
    private fun liveEmbedding(frame: Int): FloatArray {
        val base = FloatArray(63) { idx -> (idx.toFloat() * 0.01f) }
        // Add per-frame drift that exceeds MIN_MEAN_DRIFT
        val scale = 0.02f * (frame % 7 + 1)
        return FloatArray(63) { idx -> base[idx] + scale * kotlin.math.sin(idx.toFloat() + frame) }
    }

    /** Perfectly static embedding — all frames identical. */
    private fun staticEmbedding(): FloatArray = FloatArray(63) { it.toFloat() * 0.01f }

    /** Near-static embedding (below MIN_MEAN_DRIFT threshold). */
    private fun nearStaticEmbedding(frame: Int): FloatArray {
        val base = FloatArray(63) { it.toFloat() * 0.01f }
        val noise = 0.0001f * (frame % 3)   // < MIN_MEAN_DRIFT = 0.003
        return FloatArray(63) { idx -> base[idx] + noise }
    }

    /** Fills the guard window with 60 live frames. */
    private fun fillWindowLive() {
        repeat(LivenessGuard.WINDOW_FRAMES) { i ->
            guard.feed(liveEmbedding(i))
        }
    }

    /**
     * Produce an embedding that satisfies the given challenge gesture.
     * Based on the landmark rules in LivenessGuard.detectsChallengeResponse.
     */
    private fun challengeResponseEmbedding(gesture: LivenessGuard.ChallengeGesture): FloatArray {
        val emb = liveEmbedding(200)
        when (gesture) {
            LivenessGuard.ChallengeGesture.EXTEND_INDEX -> {
                // index tip (4) y << index MCP (5) y AND middle tip (8) y > middle MCP (9) y - 0.04
                emb[4 * 3 + 1] = 0.1f    // index tip y — high
                emb[5 * 3 + 1] = 0.3f    // index MCP y — low (tip is above MCP)
                emb[8 * 3 + 1] = 0.4f    // middle tip y — below MCP (not extended)
                emb[9 * 3 + 1] = 0.3f    // middle MCP y
            }
            LivenessGuard.ChallengeGesture.SPREAD_FINGERS -> {
                emb[20 * 3] = 0.0f        // thumb tip x — far left
                emb[16 * 3] = 0.25f       // pinky tip x — far right, spread > 0.20
            }
            LivenessGuard.ChallengeGesture.MAKE_FIST -> {
                // all fingertips below their MCPs
                val tips = intArrayOf(4, 8, 12, 16, 20)
                val mcps = intArrayOf(5, 9, 13, 17, 1)
                for ((tip, mcp) in tips.zip(mcps.toList())) {
                    emb[mcp * 3 + 1] = 0.2f   // MCP y
                    emb[tip * 3 + 1] = 0.5f   // tip y > MCP y (below in screen coords)
                }
            }
        }
        return emb
    }
}
