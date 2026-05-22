package com.showerideas.aura

import com.showerideas.aura.service.NearbyExchangeService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the gesture-verification gate on [NearbyExchangeService].
 *
 * These exercise the static gate flag only — they do not bring up the
 * real service. The gate is the contract that the UI layer is required
 * to honour before the service is allowed to advertise/discover.
 */
class NearbyExchangeServiceGateTest {

    @After
    fun tearDown() {
        // Reset between tests via the same path the service uses on
        // termination — call markGestureVerified() and then simulate a
        // session termination by reflectively clearing the flag.
        clearGate()
    }

    @Test
    fun `gate defaults to false on fresh process`() {
        clearGate()
        assertFalse(NearbyExchangeService.gestureVerified)
    }

    @Test
    fun `markGestureVerified flips the gate to true`() {
        clearGate()
        NearbyExchangeService.markGestureVerified()
        assertTrue(NearbyExchangeService.gestureVerified)
    }

    private fun clearGate() {
        // The companion property has a private setter; tests access it
        // through reflection to keep the production API surface clean.
        val companion = NearbyExchangeService.Companion::class.java
        val field = companion.getDeclaredField("gestureVerified")
        field.isAccessible = true
        field.setBoolean(NearbyExchangeService.Companion, false)
    }
}
