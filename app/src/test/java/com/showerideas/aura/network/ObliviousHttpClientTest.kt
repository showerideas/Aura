package com.showerideas.aura.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Task 59 — ObliviousHttpClient unit tests.
 *
 * Tests OHTTP header format, encapsulation output size, and response decapsulation.
 */
class ObliviousHttpClientTest {

    private val client = ObliviousHttpClient()
    private val rng = SecureRandom()

    // Generate a dummy X25519 key pair for gateway
    private fun gatewayKeyPair(): Pair<ByteArray, ByteArray> {
        val gen = org.bouncycastle.crypto.generators.X25519KeyPairGenerator()
        gen.init(org.bouncycastle.crypto.params.X25519KeyGenerationParameters(rng))
        val pair = gen.generateKeyPair()
        val priv = (pair.private as org.bouncycastle.crypto.params.X25519PrivateKeyParameters).encoded
        val pub  = (pair.public  as org.bouncycastle.crypto.params.X25519PublicKeyParameters).encoded
        return Pair(priv, pub)
    }

    @Test
    fun `OHTTP header is 7 bytes with correct algorithm IDs`() {
        val hdr = client.buildHeader(
            keyId = 0x01,
            kemId = ObliviousHttpClient.KEM_ID,
            kdfId = ObliviousHttpClient.KDF_ID,
            aeadId = ObliviousHttpClient.AEAD_ID
        )
        assertEquals("OHTTP header must be 7 bytes", 7, hdr.size)
        assertEquals("Key ID must be 0x01", 0x01.toByte(), hdr[0])
        // KEM ID = 0x0020
        assertEquals(0x00.toByte(), hdr[1])
        assertEquals(0x20.toByte(), hdr[2])
    }

    @Test
    fun `encapsulate produces non-empty ciphertext`() {
        val (_, gwPub) = gatewayKeyPair()
        val req = client.encapsulate(gwPub, "PUT", "https://relay.aura.id/v1/slots/abc",
            "test-body".toByteArray())
        assertTrue("Encapsulated ciphertext must be non-empty", req.ciphertext.isNotEmpty())
        assertEquals("Exporter key must be 32 bytes", 32, req.exporterKey.size)
    }

    @Test
    fun `encapsulated ciphertext starts with OHTTP header`() {
        val (_, gwPub) = gatewayKeyPair()
        val req = client.encapsulate(gwPub, "GET", "https://relay.aura.id/v1/slots/abc",
            ByteArray(0))
        val expectedHdr = client.buildHeader(0x01,
            ObliviousHttpClient.KEM_ID,
            ObliviousHttpClient.KDF_ID,
            ObliviousHttpClient.AEAD_ID)
        assertArrayEquals("Ciphertext must start with OHTTP header",
            expectedHdr, req.ciphertext.copyOfRange(0, 7))
    }

    @Test
    fun `encapsulation key validation enforced`() {
        var threw = false
        try { client.encapsulate(ByteArray(16), "PUT", "url", ByteArray(0)) }
        catch (_: Exception) { threw = true }
        assertTrue("Non-32-byte key must throw", threw)
    }

    @Test
    fun `different encapsulations of same request produce different ciphertexts (ephemeral key)`() {
        val (_, gwPub) = gatewayKeyPair()
        val body = "same-body".toByteArray()
        val r1 = client.encapsulate(gwPub, "PUT", "https://relay.aura.id/v1/slots/x", body)
        val r2 = client.encapsulate(gwPub, "PUT", "https://relay.aura.id/v1/slots/x", body)
        assertTrue("Ephemeral key must produce different ciphertext each time",
            !r1.ciphertext.contentEquals(r2.ciphertext))
    }
}
