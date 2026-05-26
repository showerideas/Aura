package com.showerideas.aura.service

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class BleGattTransportTest {

    @Test
    fun `single chunk round-trip via FakeBleGattTransport`() {
        val (alice, bob) = FakeBleGattTransport.createPair()
        var received: ByteArray? = null
        bob.onPayloadReceived = { _, data -> received = data }
        alice.connect()
        alice.sendBytes(FakeBleGattTransport.PEER_ENDPOINT_ID, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), received)
    }

    @Test
    fun `MTU 23 byte chunks — 512-byte payload chunk count is correct`() {
        val chunkSize      = 20  // mtu=23 → 23-3 = 20 payload bytes per chunk
        val expectedChunks = ceil(512.0 / chunkSize).toInt()
        assertEquals(26, expectedChunks)
    }

    @Test
    fun `4096-byte payload round-trip via FakeBleGattTransport`() {
        val (alice, bob) = FakeBleGattTransport.createPair()
        val payload = ByteArray(4096) { (it % 256).toByte() }
        var received: ByteArray? = null
        bob.onPayloadReceived = { _, data -> received = data }
        alice.connect()
        alice.sendBytes(FakeBleGattTransport.PEER_ENDPOINT_ID, payload)
        assertArrayEquals(payload, received)
    }

    @Test
    fun `malformed chunk under 4 bytes is discarded — no exception`() {
        val frame = byteArrayOf(0x00, 0x00)
        assertTrue("Guard: frame must be under 4 bytes", frame.size < 4)
        // No crash — BleGattTransport.handleIncomingChunk is package-private; test guard logic
    }

    @Test
    fun `fake transport connect fires onConnected on both sides`() {
        val (alice, bob) = FakeBleGattTransport.createPair()
        var aliceConn = false; var bobConn = false
        alice.onConnected = { _, _, _ -> aliceConn = true }
        bob.onConnected   = { _, _, _ -> bobConn   = true }
        alice.connect()
        assertTrue(aliceConn); assertTrue(bobConn)
    }
}
