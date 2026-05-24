package com.showerideas.aura

import com.showerideas.aura.service.NearbyExchangeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Smoke tests for the gesture-verification gate on [NearbyExchangeService].
 *
 * Issue-50: the gate flag was moved from a static companion-object @Volatile
 * field to a per-instance variable backed by DataStore.  The gate is now
 * opened via [NearbyExchangeService.ACTION_GESTURE_VERIFIED] — an explicit
 * Intent delivered through [android.app.Service.onStartCommand], consistent
 * with the existing ACTION_CONFIRM_SAS / ACTION_ABORT_SAS pattern.
 *
 * Full gate behaviour (service lifecycle + DataStore persistence) is covered
 * by the instrumented ExchangeFlowEspressoTest.  These JVM-only tests pin the
 * companion-level Intent-action constants so a rename or removal is caught
 * immediately at compile-and-test time.
 */
class NearbyExchangeServiceGateTest {

    @Test
    fun `ACTION_GESTURE_VERIFIED constant has correct value`() {
        assertEquals(
            "com.showerideas.aura.nearby.GESTURE_VERIFIED",
            NearbyExchangeService.ACTION_GESTURE_VERIFIED
        )
    }

    @Test
    fun `ACTION_GESTURE_VERIFIED is distinct from all other action constants`() {
        val others = listOf(
            NearbyExchangeService.ACTION_START,
            NearbyExchangeService.ACTION_STOP,
            NearbyExchangeService.ACTION_START_ROOM_HOST,
            NearbyExchangeService.ACTION_START_ROOM_GUEST,
            NearbyExchangeService.ACTION_STATE_UPDATE,
            NearbyExchangeService.ACTION_CONFIRM_SAS,
            NearbyExchangeService.ACTION_ABORT_SAS
        )
        val gate = NearbyExchangeService.ACTION_GESTURE_VERIFIED
        assertNotNull(gate)
        assert(others.none { it == gate }) {
            "ACTION_GESTURE_VERIFIED collides with an existing action constant: $gate"
        }
    }

    @Test
    fun `all action constants share the aura package prefix`() {
        val prefix = "com.showerideas.aura.nearby."
        listOf(
            NearbyExchangeService.ACTION_START,
            NearbyExchangeService.ACTION_STOP,
            NearbyExchangeService.ACTION_CONFIRM_SAS,
            NearbyExchangeService.ACTION_ABORT_SAS,
            NearbyExchangeService.ACTION_GESTURE_VERIFIED
        ).forEach { action ->
            assert(action.startsWith(prefix)) {
                "Action '$action' does not start with expected prefix '$prefix'"
            }
        }
    }
}
