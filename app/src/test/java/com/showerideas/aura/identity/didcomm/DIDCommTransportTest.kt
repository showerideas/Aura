package com.showerideas.aura.identity.didcomm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.UUID

/**
 * Task 110 — Unit tests for [DIDCommTransport] and [DIDCommMessage].
 *
 * Covers:
 *   1. DIDCommMessage data class invariants (type constants, expiry logic).
 *   2. ExchangeRequestBody.toBodyMap() key correctness.
 *   3. DIDCommTransport send+receive round-trip (anoncrypt stub path).
 *   4. DIDCommTransport.buildExchangeRequest() message construction.
 *   5. Message expiry detection.
 *
 * See: ROADMAP §Task 110
 */
class DIDCommTransportTest {

    private val transport = DIDCommTransport()

    private fun generateTestKeyPair(): Pair<ECPrivateKey, ECPublicKey> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        val kp = kpg.generateKeyPair()
        return kp.private as ECPrivateKey to kp.public as ECPublicKey
    }

    // ── DIDCommMessage invariants ──────────────────────────────────────────────

    @Test
    fun `TYPE_EXCHANGE_REQUEST has correct URI`() {
        assertTrue(DIDCommMessage.TYPE_EXCHANGE_REQUEST.contains("exchange"))
        assertTrue(DIDCommMessage.TYPE_EXCHANGE_REQUEST.startsWith("https://"))
    }

    @Test
    fun `isExpired returns false for future expiry`() {
        val msg = DIDCommMessage(
            id = UUID.randomUUID().toString(),
            type = DIDCommMessage.TYPE_EXCHANGE_REQUEST,
            from = "did:peer:2:abc",
            to = listOf("did:peer:2:xyz"),
            createdTime = Instant.now(),
            expiresTime = Instant.now().plusSeconds(3600),
            body = emptyMap()
        )
        assertFalse(msg.isExpired())
    }

    @Test
    fun `isExpired returns true for past expiry`() {
        val msg = DIDCommMessage(
            id = UUID.randomUUID().toString(),
            type = DIDCommMessage.TYPE_EXCHANGE_REQUEST,
            from = "did:peer:2:abc",
            to = listOf("did:peer:2:xyz"),
            createdTime = Instant.now().minusSeconds(7200),
            expiresTime = Instant.now().minusSeconds(3600),
            body = emptyMap()
        )
        assertTrue(msg.isExpired())
    }

    @Test
    fun `isExpired returns false when expiresTime is null`() {
        val msg = DIDCommMessage(
            id = UUID.randomUUID().toString(),
            type = DIDCommMessage.TYPE_EXCHANGE_REQUEST,
            from = null,
            to = listOf("did:peer:2:xyz"),
            createdTime = Instant.now(),
            expiresTime = null,
            body = emptyMap()
        )
        assertFalse(msg.isExpired())
    }

    // ── ExchangeRequestBody ───────────────────────────────────────────────────

    @Test
    fun `ExchangeRequestBody toBodyMap contains required keys`() {
        val body = ExchangeRequestBody(
            requesterDid = "did:peer:2:requester",
            vcJson = """{"type":"VerifiableCredential"}""",
            nonce = "abc123",
            gestureRequired = true
        )
        val map = body.toBodyMap()
        assertTrue(map.containsKey(DIDCommMessage.BODY_REQUESTER))
        assertTrue(map.containsKey(DIDCommMessage.BODY_VC))
        assertTrue(map.containsKey(DIDCommMessage.BODY_NONCE))
        assertTrue(map.containsKey(DIDCommMessage.BODY_GESTURE_HINT))
        assertEquals("did:peer:2:requester", map[DIDCommMessage.BODY_REQUESTER])
        assertEquals("abc123", map[DIDCommMessage.BODY_NONCE])
        assertEquals(true, map[DIDCommMessage.BODY_GESTURE_HINT])
    }

    // ── buildExchangeRequest ──────────────────────────────────────────────────

    @Test
    fun `buildExchangeRequest produces correct message type`() {
        val body = ExchangeRequestBody("did:peer:2:from", "{}", "nonce123")
        val msg = transport.buildExchangeRequest("did:peer:2:from", "did:peer:2:to", body)
        assertEquals(DIDCommMessage.TYPE_EXCHANGE_REQUEST, msg.type)
        assertEquals("did:peer:2:from", msg.from)
        assertEquals(listOf("did:peer:2:to"), msg.to)
        assertNotNull(msg.expiresTime)
        assertFalse(msg.isExpired())
    }

    @Test
    fun `buildExchangeRequest uses custom TTL`() {
        val body = ExchangeRequestBody("did:peer:2:from", "{}", "nonce456")
        val msg = transport.buildExchangeRequest("did:peer:2:from", "did:peer:2:to", body, ttlSeconds = 60L)
        assertNotNull(msg.expiresTime)
        val ttlActual = msg.expiresTime!!.epochSecond - msg.createdTime.epochSecond
        assertTrue(ttlActual <= 60L + 1 && ttlActual >= 59L)
    }

    // ── Send + Receive round-trip ─────────────────────────────────────────────

    @Test
    fun `send then receive round-trip decrypts message correctly`() = runBlocking {
        val (recipientPriv, recipientPub) = generateTestKeyPair()

        val original = DIDCommMessage(
            id          = "test-message-id",
            type        = DIDCommMessage.TYPE_EXCHANGE_REQUEST,
            from        = "did:peer:2:sender",
            to          = listOf("did:peer:2:recipient"),
            createdTime = Instant.ofEpochSecond(1_700_000_000L),
            expiresTime = Instant.ofEpochSecond(1_700_100_000L),
            body        = mapOf("nonce" to "testNonce", "vc" to "{}")
        )

        val envelope = transport.send(original, recipientPub)
        assertNotNull(envelope)
        assertTrue(envelope.contains("ciphertext"))

        val decrypted = transport.receive(envelope, recipientPriv)
        assertNotNull(decrypted)
        assertEquals("test-message-id", decrypted!!.id)
        assertEquals(DIDCommMessage.TYPE_EXCHANGE_REQUEST, decrypted.type)
        assertEquals("did:peer:2:sender", decrypted.from)
    }

    @Test
    fun `receive returns null for invalid JSON`() = runBlocking {
        val (recipientPriv, _) = generateTestKeyPair()
        val result = transport.receive("not-valid-json", recipientPriv)
        assertNull(result)
    }
}
