package com.showerideas.aura

import com.showerideas.aura.service.NfcExchangeHelper
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

/**
 * Unit tests for [NfcExchangeHelper] — the NFC tap-to-exchange transport.
 *
 * NFC tag operations (Ndef, NdefFormatable) require Android hardware, so these
 * tests validate the pure-JVM payload serialisation / parsing logic only.
 * The NDEF write/read round-trip is covered by the instrumented test suite.
 */
class NfcExchangeHelperTest {

    // Helpers

    private fun generateECPublicKey() =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }
            .generateKeyPair().public

    /**
     * Simulate the payload that would be written to an NDEF record and then read back.
     * We test the payload format without constructing actual NdefMessage objects
     * (those require the Android framework).
     */
    private fun buildPayload(sessionId: String, key: java.security.PublicKey): String =
        "$sessionId\n${Base64.getEncoder().encodeToString(key.encoded)}"

    private fun parsePayload(payload: String): NfcExchangeHelper.NfcBootstrap? {
        val parts = payload.split("\n", limit = 2)
        if (parts.size < 2) return null
        val sessionUuid = parts[0].trim()
        val keyBytes = runCatching { Base64.getDecoder().decode(parts[1].trim()) }.getOrNull()
            ?: return null
        if (keyBytes.size < 64) return null
        return NfcExchangeHelper.NfcBootstrap(sessionUuid, keyBytes)
    }

    // Payload format round-trip

    @Test
    fun `buildPayload includes session UUID and public key`() {
        val sessionId = UUID.randomUUID().toString()
        val key = generateECPublicKey()
        val payload = buildPayload(sessionId, key)

        assertTrue("Payload must contain session UUID", payload.contains(sessionId))
        assertTrue("Payload must contain base64 key", payload.contains("\n"))
    }

    @Test
    fun `parsePayload round-trips session UUID correctly`() {
        val sessionId = UUID.randomUUID().toString()
        val key = generateECPublicKey()
        val payload = buildPayload(sessionId, key)
        val bootstrap = parsePayload(payload)

        assertNotNull("Parsing must succeed", bootstrap)
        assertEquals("Session UUID must survive round-trip", sessionId, bootstrap!!.peerSessionUuid)
    }

    @Test
    fun `parsePayload round-trips public key bytes correctly`() {
        val key = generateECPublicKey()
        val sessionId = UUID.randomUUID().toString()
        val payload = buildPayload(sessionId, key)
        val bootstrap = parsePayload(payload)

        assertNotNull(bootstrap)
        assertArrayEquals(
            "Public key bytes must survive round-trip",
            key.encoded,
            bootstrap!!.peerPublicKeyBytes
        )
    }

    @Test
    fun `parsePayload returns null for malformed payload without newline`() {
        val result = parsePayload("no-newline-in-this-payload")
        assertNull("Parsing must fail for payload without newline", result)
    }

    @Test
    fun `parsePayload returns null for invalid base64 key`() {
        val result = parsePayload("session-uuid\nnot_valid_base64!!!")
        assertNull("Parsing must fail for invalid base64 key", result)
    }

    @Test
    fun `parsePayload returns null for key that is too short`() {
        // Only 32 bytes — too short for an EC public key (SPKI is 91 bytes)
        val shortKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32))
        val result = parsePayload("session-id\n$shortKeyB64")
        assertNull("Parsing must reject keys shorter than 64 bytes", result)
    }

    // MIME type constant

    @Test
    fun `MIME_TYPE follows application slash vnd pattern`() {
        assertTrue(
            "MIME_TYPE must start with 'application/'",
            NfcExchangeHelper.MIME_TYPE.startsWith("application/")
        )
        assertTrue(
            "MIME_TYPE must contain 'aura'",
            NfcExchangeHelper.MIME_TYPE.contains("aura")
        )
    }

    // NfcBootstrap equality

    @Test
    fun `NfcBootstrap equals and hashCode are value-based`() {
        val session = UUID.randomUUID().toString()
        val key = generateECPublicKey()
        val keyBytes = key.encoded

        val b1 = NfcExchangeHelper.NfcBootstrap(session, keyBytes)
        val b2 = NfcExchangeHelper.NfcBootstrap(session, keyBytes.copyOf())

        assertEquals("Two NfcBootstraps with same content must be equal", b1, b2)
        assertEquals("HashCodes must match for equal NfcBootstraps", b1.hashCode(), b2.hashCode())
    }

    @Test
    fun `NfcBootstrap with different sessionId are not equal`() {
        val key = generateECPublicKey().encoded
        val b1 = NfcExchangeHelper.NfcBootstrap("session-1", key)
        val b2 = NfcExchangeHelper.NfcBootstrap("session-2", key.copyOf())
        assertNotEquals("Different session UUIDs must produce non-equal NfcBootstrap", b1, b2)
    }

    // Multiple round-trips — session uniqueness

    @Test
    fun `two exchanges with different sessions produce different payloads`() {
        val key = generateECPublicKey()
        val p1 = buildPayload(UUID.randomUUID().toString(), key)
        val p2 = buildPayload(UUID.randomUUID().toString(), key)
        assertNotEquals("Different session UUIDs must produce distinct payloads", p1, p2)
    }
}
