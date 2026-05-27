package com.showerideas.aura.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FakeNearbyTransport] — validates the test double itself.
 *
 * This is a meta-test suite: it ensures [FakeNearbyTransport] correctly captures
 * calls and fires callbacks, so test suites that depend on it can trust its behaviour.
 *
 * Covers all [NearbyTransport] interface methods.
 */
class FakeNearbyTransportTest {

    private lateinit var transport: FakeNearbyTransport

    @Before
    fun setUp() {
        transport = FakeNearbyTransport()
    }

    // startAdvertising / stopAdvertising

    @Test
    fun `startAdvertising records localName and serviceId`() {
        transport.startAdvertising("Alice-Phone", "com.aura.service")
        assertEquals(1, transport.advertisingStartedWith.size)
        assertEquals("Alice-Phone", transport.advertisingStartedWith[0].first)
        assertEquals("com.aura.service", transport.advertisingStartedWith[0].second)
    }

    @Test
    fun `startAdvertising accumulates multiple calls`() {
        transport.startAdvertising("A", "svc1")
        transport.startAdvertising("B", "svc2")
        assertEquals(2, transport.advertisingStartedWith.size)
    }

    @Test
    fun `stopAdvertising sets advertisingStopped flag`() {
        assertFalse(transport.advertisingStopped)
        transport.stopAdvertising()
        assertTrue(transport.advertisingStopped)
    }

    // startDiscovery / stopDiscovery

    @Test
    fun `startDiscovery records serviceId`() {
        transport.startDiscovery("my.service.id")
        assertEquals(listOf("my.service.id"), transport.discoveryStartedWith)
    }

    @Test
    fun `stopDiscovery sets discoveryStopped flag`() {
        assertFalse(transport.discoveryStopped)
        transport.stopDiscovery()
        assertTrue(transport.discoveryStopped)
    }

    // requestConnection / acceptConnection / rejectConnection

    @Test
    fun `requestConnection records localName and endpointId`() {
        transport.requestConnection("local", "peer-1")
        assertEquals(1, transport.connectionRequestedWith.size)
        assertEquals("local" to "peer-1", transport.connectionRequestedWith[0])
    }

    @Test
    fun `acceptConnection records endpointId`() {
        transport.acceptConnection("ep-42")
        assertEquals(listOf("ep-42"), transport.acceptedConnections)
    }

    @Test
    fun `rejectConnection records endpointId`() {
        transport.rejectConnection("ep-99")
        assertEquals(listOf("ep-99"), transport.rejectedConnections)
    }

    // stopAllEndpoints

    @Test
    fun `stopAllEndpoints sets allEndpointsStopped flag`() {
        assertFalse(transport.allEndpointsStopped)
        transport.stopAllEndpoints()
        assertTrue(transport.allEndpointsStopped)
    }

    // sendBytes

    @Test
    fun `sendBytes records payload for endpointId`() {
        val data = byteArrayOf(1, 2, 3, 4)
        transport.sendBytes("ep-1", data)
        val captured = transport.sentPayloads["ep-1"]
        assertNotNull(captured)
        assertEquals(1, captured!!.size)
        assertArrayEquals(data, captured[0])
    }

    @Test
    fun `sendBytes accumulates multiple payloads per endpoint`() {
        val d1 = byteArrayOf(0x01)
        val d2 = byteArrayOf(0x02, 0x03)
        transport.sendBytes("ep-A", d1)
        transport.sendBytes("ep-A", d2)
        val captured = transport.sentPayloads["ep-A"]!!
        assertEquals(2, captured.size)
        assertArrayEquals(d1, captured[0])
        assertArrayEquals(d2, captured[1])
    }

    @Test
    fun `sendBytes keeps payloads per endpoint separate`() {
        transport.sendBytes("ep-X", byteArrayOf(1))
        transport.sendBytes("ep-Y", byteArrayOf(2))
        assertEquals(1, transport.sentPayloads["ep-X"]!!.size)
        assertEquals(1, transport.sentPayloads["ep-Y"]!!.size)
    }

    @Test
    fun `totalSentCount sums across all endpoints`() {
        transport.sendBytes("ep-1", byteArrayOf(1))
        transport.sendBytes("ep-1", byteArrayOf(2))
        transport.sendBytes("ep-2", byteArrayOf(3))
        assertEquals(3, transport.totalSentCount)
    }

    @Test
    fun `lastSentTo returns the most recent payload`() {
        transport.sendBytes("ep-1", byteArrayOf(0x0A))
        transport.sendBytes("ep-1", byteArrayOf(0x0B))
        assertArrayEquals(byteArrayOf(0x0B), transport.lastSentTo("ep-1"))
    }

    @Test
    fun `lastSentTo returns null when nothing sent to endpoint`() {
        assertNull(transport.lastSentTo("ep-never"))
    }

    // Callback simulation

    @Test
    fun `simulateEndpointFound fires onEndpointFound callback`() {
        var capturedId: String? = null
        var capturedName: String? = null
        transport.onEndpointFound = { id, name -> capturedId = id; capturedName = name }
        transport.simulateEndpointFound("ep-found", "Bob")
        assertEquals("ep-found", capturedId)
        assertEquals("Bob", capturedName)
    }

    @Test
    fun `simulateConnected fires onConnected callback`() {
        var capturedId: String? = null
        var capturedIncoming: Boolean? = null
        transport.onConnected = { id, _, incoming -> capturedId = id; capturedIncoming = incoming }
        transport.simulateConnected("ep-conn", "Carol", isIncoming = true)
        assertEquals("ep-conn", capturedId)
        assertEquals(true, capturedIncoming)
    }

    @Test
    fun `simulatePayloadReceived fires onPayloadReceived callback`() {
        var receivedData: ByteArray? = null
        transport.onPayloadReceived = { _, data -> receivedData = data }
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        transport.simulatePayloadReceived("ep-data", payload)
        assertArrayEquals(payload, receivedData)
    }

    @Test
    fun `simulateDisconnected fires onDisconnected callback`() {
        var disconnectedId: String? = null
        transport.onDisconnected = { id -> disconnectedId = id }
        transport.simulateDisconnected("ep-gone")
        assertEquals("ep-gone", disconnectedId)
    }

    @Test
    fun `callbacks are no-ops when not set`() {
        // Should not throw when callbacks are null
        transport.simulateEndpointFound("ep", "name")
        transport.simulateConnected("ep", "name", false)
        transport.simulatePayloadReceived("ep", byteArrayOf())
        transport.simulateDisconnected("ep")
    }

    // Initial state

    @Test
    fun `initial state has empty captured call lists`() {
        assertTrue(transport.advertisingStartedWith.isEmpty())
        assertTrue(transport.discoveryStartedWith.isEmpty())
        assertTrue(transport.connectionRequestedWith.isEmpty())
        assertTrue(transport.acceptedConnections.isEmpty())
        assertTrue(transport.rejectedConnections.isEmpty())
        assertTrue(transport.sentPayloads.isEmpty())
        assertFalse(transport.advertisingStopped)
        assertFalse(transport.discoveryStopped)
        assertFalse(transport.allEndpointsStopped)
        assertEquals(0, transport.totalSentCount)
    }
}
