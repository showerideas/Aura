package com.showerideas.aura.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8.2 — Unit tests for [SealedEnvelope].
 */
class SealedEnvelopeTest {

    @Test
    fun wrap_producesCorrectMinimumSize() {
        val payload = "Hello AURA".toByteArray()
        val envelope = SealedEnvelope.wrap(payload)
        // 2 (header) + 10 (payload) = 12 bytes → rounds up to 256
        assertEquals("Small payload should pad to 256 bytes", 256, envelope.size)
    }

    @Test
    fun roundtrip_exactPayloadRecovery() {
        val payload = "Test profile payload — name, email, phone".toByteArray()
        val envelope = SealedEnvelope.wrap(payload)
        val recovered = SealedEnvelope.unwrap(envelope)
        assertArrayEquals("Roundtrip must recover exact payload", payload, recovered)
    }

    @Test
    fun wrap_sizeIsMultipleOf256() {
        listOf(10, 100, 250, 255, 256, 257, 500, 1000).forEach { size ->
            val payload = ByteArray(size) { it.toByte() }
            val envelope = SealedEnvelope.wrap(payload)
            assertEquals(
                "Envelope size must be multiple of 256 for payload size $size",
                0, envelope.size % 256)
        }
    }

    @Test
    fun wrap_sizeNeverExceedsMaxSize() {
        val largePayload = ByteArray(SealedEnvelope.MAX_SIZE - 2)
        val envelope = SealedEnvelope.wrap(largePayload)
        assertTrue("Envelope must not exceed MAX_SIZE", envelope.size <= SealedEnvelope.MAX_SIZE)
    }

    @Test
    fun twoWraps_sameSizeForDifferentPayloads() {
        // Payloads that fall in the same padding block should produce same-size envelopes
        val small = ByteArray(10) { 0x01.toByte() }
        val medium = ByteArray(100) { 0x02.toByte() }
        val envSmall  = SealedEnvelope.wrap(small)
        val envMedium = SealedEnvelope.wrap(medium)
        // Both < 256 bytes of content → both padded to 256
        assertEquals("Payloads in the same block must produce equal-size envelopes",
                     envSmall.size, envMedium.size)
    }

    @Test
    fun wireProtocolVersion_isV7() {
        assertEquals("Sealed sender is wire protocol v7", 7, SealedEnvelope.WIRE_PROTOCOL_VERSION)
    }
}
