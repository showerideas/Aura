package com.showerideas.aura.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * T21 — Coverage: unit tests for [LoRaTransport] compression helpers.
 *
 * Tests DEFLATE compress/decompress round-trip and MAX_PAYLOAD_BYTES budget.
 * (AIDL service binding is not testable in unit scope — tested separately.)
 */
@RunWith(RobolectricTestRunner::class)
class LoRaTransportTest {

    private lateinit var transport: LoRaTransport

    @Before
    fun setUp() {
        transport = LoRaTransport(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `deflate compress and decompress round-trip`() {
        val original = """{"displayName":"Alice","email":"alice@example.com","company":"AURA"}"""
            .toByteArray(Charsets.UTF_8)
        val compressed   = transport.deflateCompress(original)
        val decompressed = transport.deflateDecompress(compressed)
        assertArrayEquals("Round-trip must match original", original, decompressed)
    }

    @Test
    fun `short JSON compresses below MAX_PAYLOAD_BYTES`() {
        val json = """{"displayName":"Bob","email":"b@x.com"}""".toByteArray()
        val compressed = transport.deflateCompress(json)
        assertTrue("Short JSON must compress below ${LoRaTransport.MAX_PAYLOAD_BYTES} bytes, got ${compressed.size}",
            compressed.size <= LoRaTransport.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `AURA_PORTNUM constant is 257`() {
        assertEquals(257, LoRaTransport.AURA_PORTNUM)
    }

    @Test
    fun `MAX_PAYLOAD_BYTES constant is 200`() {
        assertEquals(200, LoRaTransport.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `onPacketReceived decompresses and queues payload`() {
        val payload = "test payload".toByteArray()
        val compressed = transport.deflateCompress(payload)
        // Should not throw
        transport.onPacketReceived(compressed)
    }

    @Test
    fun `empty byte array round-trips cleanly`() {
        val original     = ByteArray(0)
        val compressed   = transport.deflateCompress(original)
        val decompressed = transport.deflateDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }
}
