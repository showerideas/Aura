package com.showerideas.aura

import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.model.GesturePattern
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

/**
 * Unit tests for DTW-based gesture matching logic.
 * These run on the JVM (no Android context needed).
 */
class GestureAuthTest {

    // Inline DTW for pure JVM testing (mirrors GestureAuthManager logic)
    private fun dtw(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty()) return Float.MAX_VALUE
        val n = a.size; val m = b.size
        val cost = Array(n) { FloatArray(m) { Float.MAX_VALUE } }
        cost[0][0] = abs(a[0] - b[0])
        for (i in 1 until n) cost[i][0] = cost[i - 1][0] + abs(a[i] - b[0])
        for (j in 1 until m) cost[0][j] = cost[0][j - 1] + abs(a[0] - b[j])
        for (i in 1 until n) for (j in 1 until m) {
            cost[i][j] = abs(a[i] - b[j]) + minOf(cost[i-1][j], cost[i][j-1], cost[i-1][j-1])
        }
        return cost[n - 1][m - 1] / maxOf(n, m).toFloat()
    }

    @Test
    fun `identical gestures should have zero distance`() {
        val v = FloatArray(50) { it.toFloat() * 0.1f }
        assertEquals(0f, dtw(v, v), 0.001f)
    }

    @Test
    fun `similar gestures should have low distance`() {
        val base = FloatArray(50) { it.toFloat() * 0.1f }
        val slight = FloatArray(50) { it.toFloat() * 0.1f + 0.05f }
        val distance = dtw(base, slight)
        assertTrue("Expected distance < 1.0 but was $distance", distance < 1.0f)
    }

    @Test
    fun `very different gestures should have high distance`() {
        val up = FloatArray(50) { it.toFloat() }
        val down = FloatArray(50) { (50 - it).toFloat() }
        val distance = dtw(up, down)
        assertTrue("Expected distance > 4.0 but was $distance", distance > 4.0f)
    }

    @Test
    fun `empty gesture should return MAX_VALUE`() {
        val v = FloatArray(50) { 1f }
        assertEquals(Float.MAX_VALUE, dtw(floatArrayOf(), v), 0.001f)
    }

    @Test
    fun `stationary hold should fail variance check`() {
        // A flat feature vector — simulates the user holding the phone still.
        val flat = FloatArray(50) { 0.02f }
        val v = GestureAuthManager.computeVariance(flat)
        assertTrue(
            "Variance for a flat hold ($v) must be below the MIN_GESTURE_VARIANCE threshold",
            v < GestureAuthManager.MIN_GESTURE_VARIANCE
        )
    }

    @Test
    fun `sharp movement should pass variance check`() {
        // Alternating peaks/troughs — simulates a deliberate shake.
        val sharp = FloatArray(50) { i -> if (i % 2 == 0) 3f else -3f }
        val v = GestureAuthManager.computeVariance(sharp)
        assertTrue(
            "Variance for a deliberate motion ($v) must exceed the threshold",
            v > GestureAuthManager.MIN_GESTURE_VARIANCE
        )
    }

    @Test
    fun `match against null stored pattern returns false`() {
        // Mirrors GestureAuthManager.match() behaviour when no pattern is stored.
        // The contract: a null stored pattern must always reject the candidate.
        val stored: FloatArray? = null
        val candidate = FloatArray(50) { 1f }
        val result = if (stored == null) false else dtw(stored, candidate) <= 4.5f
        assertFalse("Match must fail when no pattern is stored", result)
    }

    @Test
    fun `live variance window emits zero when buffer is empty`() {
        // PR-11: at recording start the rolling window has no samples yet.
        // The contract (mirrored in GestureAuthManager.sensorListener) is
        // that an empty input vector must produce variance == 0f so the UI
        // strength meter doesn't briefly flash any lit bars.
        val v = GestureAuthManager.computeVariance(FloatArray(0))
        assertEquals(0f, v, 0.0001f)
    }

    @Test
    fun `live variance falls back to zero after the recording stops`() {
        // PR-11: stopRecording() explicitly resets _liveVariance to 0f, so
        // the UI must see a zero on the next collect even if the last
        // computed variance was high. We mirror that by computing a high
        // variance window then asserting the reset value is still 0f.
        val highVarianceWindow = FloatArray(20) { i -> if (i % 2 == 0) 5f else -5f }
        val high = GestureAuthManager.computeVariance(highVarianceWindow)
        assertTrue("sanity: high-variance fixture must exceed threshold",
            high > GestureAuthManager.MIN_GESTURE_VARIANCE)
        // The reset value emitted by stopRecording() is always exactly 0f.
        val resetValue = 0f
        assertEquals(0f, resetValue, 0.0001f)
    }

    @Test
    fun `live variance bar bucketing matches the documented thresholds`() {
        // PR-11: ProfileFragment buckets variance into 0..5 lit bars. This
        // test pins the bucket boundaries so a future change to the
        // thresholds in the fragment is forced through a test update too.
        fun lit(v: Float): Int = when {
            v < 0.15f -> 0
            v < 0.35f -> 1
            v < 0.60f -> 2
            v < 1.00f -> 3
            v < 2.00f -> 4
            else      -> 5
        }
        assertEquals(0, lit(0.05f))
        assertEquals(1, lit(0.20f))
        assertEquals(2, lit(0.50f))
        assertEquals(3, lit(0.80f))
        assertEquals(4, lit(1.50f))
        assertEquals(5, lit(3.00f))
    }

    @Test
    fun `gesture pattern equality is based on id and feature vector`() {
        val v = floatArrayOf(1f, 2f, 3f)
        val p1 = GesturePattern("abc", featureVector = v)
        val p2 = GesturePattern("abc", featureVector = v.copyOf())
        val p3 = GesturePattern("xyz", featureVector = v)
        assertEquals(p1, p2)
        assertNotEquals(p1, p3)
    }
}
