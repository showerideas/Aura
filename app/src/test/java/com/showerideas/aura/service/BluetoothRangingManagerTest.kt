package com.showerideas.aura.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 58 — BluetoothRangingManager unit tests.
 *
 * Tests OOB param encode/decode, distance filter, auto-confirm threshold.
 */
class BluetoothRangingManagerTest {

    @Test
    fun `CS OOB params encode and decode round-trip`() {
        val params = BluetoothRangingManager.CsOobParams(
            role = BluetoothRangingManager.CsOobParams.CsRole.INITIATOR,
            csConfigId = 2,
            sessionId = ByteArray(16) { it.toByte() }
        )
        val encoded = params.encode()
        val decoded = BluetoothRangingManager.CsOobParams.decode(encoded)
        assertEquals(params.role, decoded.role)
        assertEquals(params.csConfigId, decoded.csConfigId)
        assertTrue(params.sessionId.contentEquals(decoded.sessionId))
    }

    @Test
    fun `auto-confirm true below threshold`() {
        val mgr = BluetoothRangingManager()
        assertTrue(mgr.shouldAutoConfirm(49f))
        assertTrue(mgr.shouldAutoConfirm(50f))
    }

    @Test
    fun `auto-confirm false above threshold`() {
        val mgr = BluetoothRangingManager()
        assertFalse(mgr.shouldAutoConfirm(51f))
        assertFalse(mgr.shouldAutoConfirm(200f))
    }

    @Test
    fun `CS distance filter averages correctly`() {
        val filter = BluetoothRangingManager.CsDistanceFilter(windowSize = 3)
        filter.update(90f); filter.update(60f); filter.update(30f)
        val avg = filter.update(20f) // window = [60, 30, 20], avg = 36.67
        assertTrue("Filter average should be around 36", avg in 33f..40f)
    }

    @Test
    fun `CS distance filter reset clears window`() {
        val filter = BluetoothRangingManager.CsDistanceFilter(windowSize = 5)
        repeat(5) { filter.update(100f) }
        filter.reset()
        val fresh = filter.update(10f)
        assertEquals(10f, fresh, 0.001f)
    }

    @Test
    fun `CsOobParams decode rejects short byte array`() {
        var threw = false
        try { BluetoothRangingManager.CsOobParams.decode(ByteArray(5)) }
        catch (_: Exception) { threw = true }
        assertTrue("Short byte array must throw", threw)
    }
}
