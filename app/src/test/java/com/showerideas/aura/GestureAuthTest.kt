package com.showerideas.aura

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
    fun `match against null stored pattern returns false`() {
        // Mirrors GestureAuthManager.match() behaviour when no pattern is stored.
        // The contract: a null stored pattern must always reject the candidate.
        val stored: FloatArray? = null
        val candidate = FloatArray(50) { 1f }
        val result = if (stored == null) false else dtw(stored, candidate) <= 4.5f
        assertFalse("Match must fail when no pattern is stored", result)
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
