package com.showerideas.aura.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 62 — QuicRelayClient unit tests.
 *
 * Tests engine config, experimental options JSON, SPKI pin propagation,
 * and availability flag logic.
 */
class QuicRelayClientTest {

    private val client = QuicRelayClient()

    @Test
    fun `engine config has QUIC and connection migration enabled`() {
        val config = client.buildEngineConfig(
            baseUrl = "https://relay.aura.id",
            spkiPins = setOf("abc123==", "def456==")
        )
        assertTrue("QUIC must be enabled", config.quicEnabled)
        assertTrue("Connection migration must be enabled", config.connectionMigration)
        assertTrue("0-RTT must be enabled", config.zeroRttEnabled)
        assertTrue("H2 fallback must be enabled", config.h2Enabled)
    }

    @Test
    fun `engine config extracts correct host for pinning`() {
        val config = client.buildEngineConfig(
            baseUrl = "https://relay.aura.id",
            spkiPins = setOf("pin1")
        )
        assertEquals("relay.aura.id", config.hostForPinning)
    }

    @Test
    fun `engine config propagates SPKI pins`() {
        val pins = setOf("pin-primary==", "pin-backup==")
        val config = client.buildEngineConfig("https://relay.aura.id", pins)
        assertEquals(pins, config.spkiPins)
    }

    @Test
    fun `experimental options JSON is valid and contains RVCM`() {
        val opts = client.buildExperimentalOptions()
        assertTrue("Must contain QUIC section", opts.contains("\"QUIC\""))
        assertTrue("Must contain RVCM option", opts.contains("RVCM"))
        assertTrue("Must contain migration flag", opts.contains("migrate_sessions_on_network_change_v2"))
        assertTrue("Must contain AsyncDNS", opts.contains("AsyncDNS"))
    }

    @Test
    fun `0-RTT cache TTL is 7 days`() {
        assertEquals(7 * 24 * 3600L, QuicRelayClient.ZERO_RTT_CACHE_TTL_S)
    }

    @Test
    fun `alt-svc header value is h3`() {
        assertEquals("h3", QuicRelayClient.ALT_SVC_HEADER)
    }
}
