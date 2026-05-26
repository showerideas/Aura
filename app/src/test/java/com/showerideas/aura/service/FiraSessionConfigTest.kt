package com.showerideas.aura.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 52 — FiraSessionConfig unit tests.
 *
 * Tests Aliro access decision logic, distance filter EMA,
 * and session key validation.
 */
class FiraSessionConfigTest {

    @Test
    fun `Aliro GRANT when distance below threshold and peer in front`() {
        val result = FiraSessionConfig.evaluateAliroAccess(15f, azimuthDeg = 10f)
        assertEquals(AliroAccessDecision.GRANT, result)
    }

    @Test
    fun `Aliro DENY_DISTANCE when beyond threshold`() {
        val result = FiraSessionConfig.evaluateAliroAccess(100f, azimuthDeg = 0f)
        assertEquals(AliroAccessDecision.DENY_DISTANCE, result)
    }

    @Test
    fun `Aliro DENY_ANGLE when in range but peer behind`() {
        val result = FiraSessionConfig.evaluateAliroAccess(10f, azimuthDeg = 90f)
        assertEquals(AliroAccessDecision.DENY_ANGLE, result)
    }

    @Test
    fun `Aliro GRANT when azimuth null (no AoA available)`() {
        val result = FiraSessionConfig.evaluateAliroAccess(15f, azimuthDeg = null)
        assertEquals(AliroAccessDecision.GRANT, result)
    }

    @Test
    fun `distance filter EMA converges smoothly`() {
        val filter = FiraSessionConfig.DistanceFilter(windowSize = 5, alpha = 0.3f)
        val samples = listOf(100f, 50f, 30f, 20f, 15f)
        var last = 0f
        samples.forEach { last = filter.update(it) }
        // EMA must be between first and last sample after convergence
        assertTrue("EMA must be > 15", last > 15f)
        assertTrue("EMA must be < 100", last < 100f)
    }

    @Test
    fun `distance filter reset clears state`() {
        val filter = FiraSessionConfig.DistanceFilter()
        filter.update(50f); filter.update(40f)
        filter.reset()
        val fresh = filter.update(100f)
        // After reset, first sample is the EMA seed
        assertEquals(100f, fresh, 0.001f)
    }

    @Test
    fun `buildControllerParams rejects non-16-byte session key`() {
        var threw = false
        try {
            FiraSessionConfig.buildControllerParams(
                sessionId = 1,
                complexChannel = androidx.core.uwb.UwbComplexChannel(9, 10),
                peerAddress = androidx.core.uwb.UwbAddress(byteArrayOf(0x01, 0x02)),
                sessionKey = ByteArray(32)  // wrong size
            )
        } catch (_: Exception) { threw = true }
        assertTrue("16-byte key validation must throw on 32-byte key", threw)
    }
}
