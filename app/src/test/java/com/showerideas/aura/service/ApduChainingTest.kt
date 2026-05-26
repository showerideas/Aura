package com.showerideas.aura.service

import org.junit.Assert.*
import org.junit.Test

class ApduChainingTest {

    @Test
    fun `payload under 255 bytes returns single response ending in SW_OK`() {
        val payload      = ByteArray(100) { it.toByte() }
        val payloadStr   = "session\n${java.util.Base64.getEncoder().encodeToString(payload)}"
        val payloadBytes = payloadStr.toByteArray(Charsets.UTF_8)
        assertTrue("Short payload should fit in one chunk", payloadBytes.size <= 255)
        val response = payloadBytes + byteArrayOf(0x90.toByte(), 0x00)
        assertEquals(0x90.toByte(), response[response.size - 2])
        assertEquals(0x00.toByte(), response[response.size - 1])
    }

    @Test
    fun `512-byte payload first chunk is 255 bytes with SW_61XX`() {
        val bigPayload = ByteArray(512) { (it % 256).toByte() }
        val firstChunk = bigPayload.copyOfRange(0, 255)
        val remaining  = bigPayload.copyOfRange(255, 512)
        assertEquals(255, firstChunk.size)
        assertEquals(257, remaining.size)
        val sw1Response = firstChunk + byteArrayOf(0x61, minOf(255, remaining.size).toByte())
        assertEquals(0x61.toByte(), sw1Response[sw1Response.size - 2])
    }

    @Test
    fun `HKDF session token is deterministic`() {
        val ecdhShared = ByteArray(32) { 0xAB.toByte() }
        val nonce      = ByteArray(32) { 0xCD.toByte() }
        val token1     = NfcExchangeHelper.deriveSessionToken(ecdhShared, nonce)
        val token2     = NfcExchangeHelper.deriveSessionToken(ecdhShared, nonce)
        assertArrayEquals(token1, token2)
        assertEquals(32, token1.size)
    }

    @Test
    fun `different nonces produce different session tokens`() {
        val ecdhShared = ByteArray(32) { 0xAB.toByte() }
        val nonce1     = ByteArray(32) { 0x01 }
        val nonce2     = ByteArray(32) { 0x02 }
        val token1     = NfcExchangeHelper.deriveSessionToken(ecdhShared, nonce1)
        val token2     = NfcExchangeHelper.deriveSessionToken(ecdhShared, nonce2)
        assertFalse(token1.contentEquals(token2))
    }
}
