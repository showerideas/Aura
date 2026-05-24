package com.showerideas.aura.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the [WifiDirectTransport] wire-protocol contract using
 * [FakeWifiDirectTransport] as an in-process stand-in.
 *
 * These tests verify:
 * - Bidirectional payload delivery between two in-process transports
 * - Endpoint discovery and connection lifecycle callbacks
 * - Multi-message ordering and isolation
 * - Disconnect semantics
 * - Edge cases: empty payload, large payload, self-send guard
 *
 * No Android framework, Play Services, or real network I/O is required.
 */
class WifiDirectTransportTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun pair(): Pair<FakeWifiDirectTransport, FakeWifiDirectTransport> {
        val alice = FakeWifiDirectTransport("alice")
        val bob   = FakeWifiDirectTransport("bob")
        return alice to bob
    }

    private fun connectedPair(): Pair<FakeWifiDirectTransport, FakeWifiDirectTransport> {
        val (alice, bob) = pair()
        alice.connectTo(bob)
        return alice to bob
    }

    // -------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun `connectTo fires onConnected on both sides`() {
        val (alice, bob) = pair()
        val aliceEvents = mutableListOf<Triple<String, String, Boolean>>()
        val bobEvents   = mutableListOf<Triple<String, String, Boolean>>()
        alice.onConnected = { id, name, incoming -> aliceEvents.add(Triple(id, name, incoming)) }
        bob.onConnected   = { id, name, incoming -> bobEvents.add(Triple(id, name, incoming)) }

        alice.connectTo(bob)

        assertEquals(1, aliceEvents.size)
        assertEquals("bob", aliceEvents[0].first)
        assertTrue("alice should be GO — incoming=true", aliceEvents[0].third)

        assertEquals(1, bobEvents.size)
        assertEquals("alice", bobEvents[0].first)
        assertTrue("bob is client — incoming=false", !bobEvents[0].third)
    }

    @Test
    fun `connectTo fires onEndpointFound on both sides`() {
        val (alice, bob) = pair()
        val aliceFound = mutableListOf<String>()
        val bobFound   = mutableListOf<String>()
        alice.onEndpointFound = { id, _ -> aliceFound.add(id) }
        bob.onEndpointFound   = { id, _ -> bobFound.add(id) }

        alice.connectTo(bob)

        assertTrue("bob" in aliceFound)
        assertTrue("alice" in bobFound)
    }

    // -------------------------------------------------------------------------
    // Payload delivery
    // -------------------------------------------------------------------------

    @Test
    fun `sendBytes delivers payload to peer`() {
        val (alice, bob) = connectedPair()
        val received = mutableListOf<ByteArray>()
        bob.onPayloadReceived = { _, data -> received.add(data) }

        alice.sendBytes("bob", byteArrayOf(0x01, 0x02, 0x03))

        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), received[0])
    }

    @Test
    fun `sendBytes delivers from bob to alice`() {
        val (alice, bob) = connectedPair()
        val received = mutableListOf<ByteArray>()
        alice.onPayloadReceived = { _, data -> received.add(data) }

        bob.sendBytes("alice", byteArrayOf(0xAA.toByte(), 0xBB.toByte()))

        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), received[0])
    }

    @Test
    fun `sendBytes includes endpointId in callback`() {
        val (alice, bob) = connectedPair()
        var fromId: String? = null
        bob.onPayloadReceived = { id, _ -> fromId = id }

        alice.sendBytes("bob", byteArrayOf(0x00))

        assertEquals("alice", fromId)
    }

    @Test
    fun `sendBytes multiple messages delivered in order`() {
        val (alice, bob) = connectedPair()
        val received = mutableListOf<ByteArray>()
        bob.onPayloadReceived = { _, data -> received.add(data) }

        alice.sendBytes("bob", byteArrayOf(0x01))
        alice.sendBytes("bob", byteArrayOf(0x02))
        alice.sendBytes("bob", byteArrayOf(0x03))

        assertEquals(3, received.size)
        assertArrayEquals(byteArrayOf(0x01), received[0])
        assertArrayEquals(byteArrayOf(0x02), received[1])
        assertArrayEquals(byteArrayOf(0x03), received[2])
    }

    @Test
    fun `sendBytes delivers empty payload`() {
        val (alice, bob) = connectedPair()
        val received = mutableListOf<ByteArray>()
        bob.onPayloadReceived = { _, data -> received.add(data) }

        alice.sendBytes("bob", byteArrayOf())

        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(), received[0])
    }

    @Test
    fun `sendBytes delivers large payload without corruption`() {
        val (alice, bob) = connectedPair()
        val payload = ByteArray(64_000) { it.toByte() }
        var received: ByteArray? = null
        bob.onPayloadReceived = { _, data -> received = data }

        alice.sendBytes("bob", payload)

        assertArrayEquals(payload, received)
    }

    @Test
    fun `receivedFrom accumulates payloads for test assertion`() {
        val (alice, bob) = connectedPair()
        alice.sendBytes("bob", byteArrayOf(0xAA.toByte()))
        alice.sendBytes("bob", byteArrayOf(0xBB.toByte()))

        val queue = bob.receivedFrom["alice"]!!
        assertEquals(2, queue.size)
    }

    // -------------------------------------------------------------------------
    // Payload isolation — two sessions don't bleed
    // -------------------------------------------------------------------------

    @Test
    fun `two independent pairs do not receive each other payloads`() {
        val (alice1, bob1) = connectedPair()
        val (alice2, bob2) = connectedPair()

        val bob1Received = mutableListOf<ByteArray>()
        val bob2Received = mutableListOf<ByteArray>()
        bob1.onPayloadReceived = { _, d -> bob1Received.add(d) }
        bob2.onPayloadReceived = { _, d -> bob2Received.add(d) }

        alice1.sendBytes("bob", byteArrayOf(0x11))
        alice2.sendBytes("bob", byteArrayOf(0x22))

        assertEquals(1, bob1Received.size)
        assertEquals(1, bob2Received.size)
        assertArrayEquals(byteArrayOf(0x11), bob1Received[0])
        assertArrayEquals(byteArrayOf(0x22), bob2Received[0])
    }

    // -------------------------------------------------------------------------
    // Disconnect semantics
    // -------------------------------------------------------------------------

    @Test
    fun `disconnectFrom fires onDisconnected on both sides`() {
        val (alice, bob) = connectedPair()
        val aliceDisconn = mutableListOf<String>()
        val bobDisconn   = mutableListOf<String>()
        alice.onDisconnected = { id -> aliceDisconn.add(id) }
        bob.onDisconnected   = { id -> bobDisconn.add(id) }

        alice.disconnectFrom(bob)

        assertTrue("bob" in aliceDisconn)
        assertTrue("alice" in bobDisconn)
    }

    @Test(expected = IllegalStateException::class)
    fun `sendBytes after disconnect throws`() {
        val (alice, bob) = connectedPair()
        alice.disconnectFrom(bob)
        alice.sendBytes("bob", byteArrayOf(0x01))
    }

    @Test
    fun `stopAllEndpoints disconnects all peers`() {
        val alice = FakeWifiDirectTransport("alice")
        val bob   = FakeWifiDirectTransport("bob")
        val carol = FakeWifiDirectTransport("carol")
        alice.connectTo(bob)
        alice.connectTo(carol)

        val disconnected = mutableListOf<String>()
        bob.onDisconnected   = { id -> disconnected.add("bob saw $id") }
        carol.onDisconnected = { id -> disconnected.add("carol saw $id") }

        alice.stopAllEndpoints()

        assertEquals(2, disconnected.size)
    }

    // -------------------------------------------------------------------------
    // Advertising / discovery lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun `startAdvertising records serviceId`() {
        val alice = FakeWifiDirectTransport("alice")
        assertNull(alice.advertisedServiceId)
        alice.startAdvertising("alice", "com.showerideas.aura")
        assertEquals("com.showerideas.aura", alice.advertisedServiceId)
    }

    @Test
    fun `stopAdvertising clears serviceId`() {
        val alice = FakeWifiDirectTransport("alice")
        alice.startAdvertising("alice", "com.showerideas.aura")
        alice.stopAdvertising()
        assertNull(alice.advertisedServiceId)
    }

    @Test
    fun `startDiscovery records serviceId`() {
        val alice = FakeWifiDirectTransport("alice")
        alice.startDiscovery("com.showerideas.aura")
        assertTrue(alice.discoveryServiceIds.contains("com.showerideas.aura"))
    }

    @Test
    fun `stopDiscovery clears service ids`() {
        val alice = FakeWifiDirectTransport("alice")
        alice.startDiscovery("com.showerideas.aura")
        alice.stopDiscovery()
        assertTrue(alice.discoveryServiceIds.isEmpty())
    }

    // -------------------------------------------------------------------------
    // rejectConnection
    // -------------------------------------------------------------------------

    @Test
    fun `rejectConnection disconnects peer`() {
        val (alice, bob) = connectedPair()
        val bobDisconn = mutableListOf<String>()
        bob.onDisconnected = { id -> bobDisconn.add(id) }

        alice.rejectConnection("bob")

        assertTrue("alice" in bobDisconn)
    }
}
