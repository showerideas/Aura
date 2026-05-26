package com.showerideas.aura.utils

import org.junit.Assert.*
import org.junit.Test

class SealedFrameTest {

    @Test
    fun `50-byte payload produces WIRE_FRAME_SIZE frame`() {
        val frame = SealedFrame.wrap(ByteArray(50) { it.toByte() })
        assertEquals(SealedFrame.WIRE_FRAME_SIZE, frame.size)
    }

    @Test
    fun `1900-byte payload produces WIRE_FRAME_SIZE frame`() {
        val frame = SealedFrame.wrap(ByteArray(1900) { (it % 256).toByte() })
        assertEquals(SealedFrame.WIRE_FRAME_SIZE, frame.size)
    }

    @Test
    fun `50-byte and 1900-byte payloads produce identical-length frames`() {
        val small = SealedFrame.wrap(ByteArray(50)   { 0x01 })
        val large = SealedFrame.wrap(ByteArray(1900) { 0x02 })
        assertEquals(small.size, large.size)
    }

    @Test
    fun `wrap-unwrap round-trip preserves payload`() {
        val payload  = ByteArray(123) { (it * 3).toByte() }
        val frame    = SealedFrame.wrap(payload)
        val unwrapped = SealedFrame.unwrap(frame)
        assertArrayEquals(payload, unwrapped)
    }

    @Test
    fun `payload exceeding MAX_ENVELOPE_BYTES throws IllegalArgumentException`() {
        val tooBig = ByteArray(SealedFrame.MAX_ENVELOPE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { SealedFrame.wrap(tooBig) }
    }
}
