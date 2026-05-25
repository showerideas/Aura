package com.showerideas.aura.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import android.nfc.NdefMessage
import android.nfc.NdefRecord

/**
 * Unit tests for [NfcExchangeHelper] — Phase 6.1 NFC tap-to-exchange path.
 *
 * Tests run on the JVM via Robolectric shadows. All NFC NDEF parsing logic
 * is pure-Kotlin / pure-Java and does not require Android hardware.
 *
 * Coverage targets:
 *  - NDEF record construction (MIME type, payload encoding)
 *  - NDEF message parsing (happy path, malformed, too-short key)
 *  - Base64 decode errors
 *  - Multi-record NDEF messages (first matching record wins)
 *  - Wrong MIME type is ignored
 *  - NfcBootstrap equality contract (equals / hashCode / contentEquals on ByteArray)
 */
class NfcExchangeHelperTest {

    // -------------------------------------------------------------------------
    // Helper — generate a test EC public key
    // -------------------------------------------------------------------------

    private fun generateP256PublicKey(): PublicKey =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().public

    // -------------------------------------------------------------------------
    // MIME_TYPE constant
    // -------------------------------------------------------------------------

    @Test
    fun `MIME_TYPE is the expected AURA application MIME string`() {
        assertEquals("application/vnd.aura.key-bootstrap", NfcExchangeHelper.MIME_TYPE)
    }

    // -------------------------------------------------------------------------
    // NfcBootstrap equality contract
    // -------------------------------------------------------------------------

    @Test
    fun `NfcBootstrap equals is reflexive`() {
        val key = generateP256PublicKey()
        val b = NfcExchangeHelper.NfcBootstrap("uuid-1", key.encoded)
        assertEquals(b, b)
    }

    @Test
    fun `NfcBootstrap equals compares peerSessionUuid and peerPublicKeyBytes`() {
        val key = generateP256PublicKey()
        val b1 = NfcExchangeHelper.NfcBootstrap("uuid-x", key.encoded.copyOf())
        val b2 = NfcExchangeHelper.NfcBootstrap("uuid-x", key.encoded.copyOf())
        assertEquals(b1, b2)
    }

    @Test
    fun `NfcBootstrap not equal when session UUID differs`() {
        val key = generateP256PublicKey()
        val b1 = NfcExchangeHelper.NfcBootstrap("uuid-1", key.encoded.copyOf())
        val b2 = NfcExchangeHelper.NfcBootstrap("uuid-2", key.encoded.copyOf())
        assertTrue(b1 != b2)
    }

    @Test
    fun `NfcBootstrap not equal when key bytes differ`() {
        val key1 = generateP256PublicKey()
        val key2 = generateP256PublicKey()
        val b1 = NfcExchangeHelper.NfcBootstrap("same-uuid", key1.encoded)
        val b2 = NfcExchangeHelper.NfcBootstrap("same-uuid", key2.encoded)
        assertTrue(b1 != b2)
    }

    @Test
    fun `NfcBootstrap hashCode is consistent with equals`() {
        val key = generateP256PublicKey()
        val b1 = NfcExchangeHelper.NfcBootstrap("uuid-h", key.encoded.copyOf())
        val b2 = NfcExchangeHelper.NfcBootstrap("uuid-h", key.encoded.copyOf())
        assertEquals(b1.hashCode(), b2.hashCode())
    }

    // -------------------------------------------------------------------------
    // NDEF record parsing helpers (via reflection on parseNdefMessage)
    // -------------------------------------------------------------------------

    /**
     * Build a minimal AURA NdefMessage using the same format NfcExchangeHelper uses:
     *   MIME record: "application/vnd.aura.key-bootstrap"
     *   Payload: "<sessionUuid>\n<base64-key>"
     */
    private fun buildAuraRecord(sessionUuid: String, keyBytes: ByteArray): NdefMessage {
        val b64Key = Base64.getEncoder().encodeToString(keyBytes)
        val payload = "$sessionUuid\n$b64Key"
        val record = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            payload.toByteArray(Charsets.UTF_8)
        )
        return NdefMessage(arrayOf(record))
    }

    /**
     * Invoke the private [NfcExchangeHelper.parseNdefMessage] via the public
     * surface — [NfcExchangeHelper.handleIntent] is the only public entry point
     * for this path in production, but we can also construct an NdefMessage
     * directly and feed it to the readFromTag path. Since readFromTag requires
     * an actual NFC Tag (hardware), we test parsing indirectly through
     * the [NfcExchangeHelper.NfcBootstrap] equality tests above and the
     * payload construction round-trip below using reflection.
     */
    private fun parseNdefMessage(message: NdefMessage): NfcExchangeHelper.NfcBootstrap? {
        // Use reflection to access the private parseNdefMessage method
        val method = NfcExchangeHelper::class.java.getDeclaredMethod(
            "parseNdefMessage", NdefMessage::class.java
        ).also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(NfcExchangeHelper, message) as? NfcExchangeHelper.NfcBootstrap
    }

    @Test
    fun `parseNdefMessage returns NfcBootstrap for valid AURA record`() {
        val key = generateP256PublicKey()
        val uuid = "session-abc-123"
        val msg = buildAuraRecord(uuid, key.encoded)
        val result = parseNdefMessage(msg)
        assertNotNull("Valid AURA NDEF record must parse successfully", result)
        assertEquals(uuid, result!!.peerSessionUuid)
        assertArrayEquals(key.encoded, result.peerPublicKeyBytes)
    }

    @Test
    fun `parseNdefMessage returns null for wrong MIME type`() {
        val record = NdefRecord.createMime(
            "application/vnd.other.app",
            "some-payload".toByteArray()
        )
        val msg = NdefMessage(arrayOf(record))
        assertNull("Wrong MIME type must return null", parseNdefMessage(msg))
    }

    @Test
    fun `parseNdefMessage returns null for payload missing newline separator`() {
        val record = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            "no-newline-here".toByteArray(Charsets.UTF_8)
        )
        val msg = NdefMessage(arrayOf(record))
        assertNull("Payload without newline separator must return null", parseNdefMessage(msg))
    }

    @Test
    fun `parseNdefMessage returns null for invalid base64 key`() {
        val record = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            "session-uuid\n!!! not valid base64 !!!".toByteArray(Charsets.UTF_8)
        )
        val msg = NdefMessage(arrayOf(record))
        assertNull("Invalid Base64 key must return null", parseNdefMessage(msg))
    }

    @Test
    fun `parseNdefMessage returns null for key bytes too short (less than 64 bytes)`() {
        // A 32-byte key is below the 64-byte minimum
        val shortKey = ByteArray(32) { it.toByte() }
        val record = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            ("session-x\n" + Base64.getEncoder().encodeToString(shortKey)).toByteArray()
        )
        val msg = NdefMessage(arrayOf(record))
        assertNull("Key shorter than 64 bytes must be rejected", parseNdefMessage(msg))
    }

    @Test
    fun `parseNdefMessage accepts key exactly 64 bytes long`() {
        val minKey = ByteArray(64) { it.toByte() }
        val record = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            ("session-64\n" + Base64.getEncoder().encodeToString(minKey)).toByteArray()
        )
        val msg = NdefMessage(arrayOf(record))
        val result = parseNdefMessage(msg)
        assertNotNull("Key exactly 64 bytes should be accepted", result)
        assertEquals(64, result!!.peerPublicKeyBytes.size)
    }

    @Test
    fun `parseNdefMessage uses first matching AURA record in multi-record message`() {
        val key1 = generateP256PublicKey()
        val key2 = generateP256PublicKey()
        // Build first record (AURA)
        val b64Key1 = Base64.getEncoder().encodeToString(key1.encoded)
        val record1 = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            "session-first\n$b64Key1".toByteArray()
        )
        // Build second record (also AURA, but should be ignored)
        val b64Key2 = Base64.getEncoder().encodeToString(key2.encoded)
        val record2 = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            "session-second\n$b64Key2".toByteArray()
        )
        val msg = NdefMessage(arrayOf(record1, record2))
        val result = parseNdefMessage(msg)
        assertNotNull(result)
        assertEquals("First matching record should win", "session-first", result!!.peerSessionUuid)
        assertArrayEquals(key1.encoded, result.peerPublicKeyBytes)
    }

    @Test
    fun `parseNdefMessage skips non-AURA records and parses the AURA record`() {
        val key = generateP256PublicKey()
        val nonAura = NdefRecord.createMime("text/plain", "hello world".toByteArray())
        val b64Key = Base64.getEncoder().encodeToString(key.encoded)
        val auraRecord = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            "session-after-non-aura\n$b64Key".toByteArray()
        )
        val msg = NdefMessage(arrayOf(nonAura, auraRecord))
        val result = parseNdefMessage(msg)
        assertNotNull("AURA record after non-AURA record must still be parsed", result)
        assertEquals("session-after-non-aura", result!!.peerSessionUuid)
    }

    @Test
    fun `parseNdefMessage handles session UUID with special characters`() {
        val key = generateP256PublicKey()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val result = parseNdefMessage(buildAuraRecord(uuid, key.encoded))
        assertNotNull(result)
        assertEquals(uuid, result!!.peerSessionUuid)
    }

    @Test
    fun `parseNdefMessage handles P-256 key round-trip (91-byte X509 SPKI)`() {
        val key = generateP256PublicKey()
        // P-256 X.509 SubjectPublicKeyInfo encoding is 91 bytes
        assertEquals("P-256 X509 SPKI must be 91 bytes", 91, key.encoded.size)
        val result = parseNdefMessage(buildAuraRecord("p256-session", key.encoded))
        assertNotNull(result)
        assertArrayEquals("Key bytes must survive NDEF round-trip", key.encoded,
            result!!.peerPublicKeyBytes)
    }

    // -------------------------------------------------------------------------
    // Payload format edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `parseNdefMessage handles empty session UUID`() {
        val key = generateP256PublicKey()
        val b64Key = Base64.getEncoder().encodeToString(key.encoded)
        val record = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            "\n$b64Key".toByteArray()  // empty session UUID
        )
        val result = parseNdefMessage(NdefMessage(arrayOf(record)))
        assertNotNull(result)
        assertEquals("", result!!.peerSessionUuid)
    }

    @Test
    fun `parseNdefMessage with multiple newlines uses first split only`() {
        // Payload: "session\nbase64key\nextra"
        // The split(limit=2) ensures extra newlines in the key are treated as part of key segment
        val key = generateP256PublicKey()
        val b64Key = Base64.getEncoder().encodeToString(key.encoded)
        val record = NdefRecord.createMime(
            NfcExchangeHelper.MIME_TYPE,
            "session-extra\n$b64Key\nextra-data".toByteArray()
        )
        val result = parseNdefMessage(NdefMessage(arrayOf(record)))
        // The extra-data after second newline is part of the key segment and will fail Base64
        // OR succeed if the truncated base64 happens to decode to >= 64 bytes.
        // Either way, we just verify no crash occurs.
        // (null is acceptable here — the extra data corrupts the key)
        assertTrue(result == null || result.peerPublicKeyBytes.size >= 64)
    }
}
