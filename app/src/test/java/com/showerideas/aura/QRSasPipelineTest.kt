package com.showerideas.aura

import com.showerideas.aura.model.Contact
import com.showerideas.aura.utils.CryptoUtils
import com.showerideas.aura.utils.SasVerifier
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.util.Base64
import java.util.UUID

/**
 * JVM unit tests for the QR-path SAS verification pipeline.
 *
 * Validates the pure crypto and SAS logic that [QRExchangeViewModel] exercises,
 * without any Android, ViewModel, or Room dependencies. Mirrors the approach
 * used by [WireProtocolTest] for the Nearby path.
 *
 * Covered scenarios:
 *  1. QR ECDH symmetry — Alice and Bob derive the same AES-256 session key.
 *  2. Encrypt-then-decrypt round-trip — Bob can read Alice's encrypted profile.
 *  3. SAS symmetry — both parties derive the same 6-digit code.
 *  4. SAS MITM detection — a substituted key produces a DIFFERENT SAS.
 *  5. SAS derivation is QR-path consistent — uses the ephemeral ECDH keys, not identity keys.
 *  6. Mutual exchange — both parties can encrypt + decrypt each other's profiles.
 *  7. Contact.fromMap preserves all fields — post-decrypt contact construction is faithful.
 *  8. Tampered ciphertext — AES-GCM tag failure is caught and does not crash.
 *  9. Empty profile fields — gracefully produce empty Contact, not crash.
 * 10. SAS range — always exactly 6 digits in [0, 10^6).
 */
class QRSasPipelineTest {

    private lateinit var aliceEcdh: KeyPair
    private lateinit var bobEcdh: KeyPair

    @Before
    fun setUp() {
        aliceEcdh = CryptoUtils.generateEphemeralECDHKeyPair()
        bobEcdh   = CryptoUtils.generateEphemeralECDHKeyPair()
    }

    // -------------------------------------------------------------------------
    // 1. ECDH symmetry
    // -------------------------------------------------------------------------

    @Test
    fun `QR ECDH both sides derive the same session key`() {
        val aliceKey = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val bobKey   = CryptoUtils.deriveSharedAESKey(bobEcdh.private, aliceEcdh.public)
        assertArrayEquals(aliceKey.encoded, bobKey.encoded)
    }

    // -------------------------------------------------------------------------
    // 2. Encrypt-then-decrypt round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `encrypted profile can be decrypted by peer`() {
        val key = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val payload = sampleProfileJson("Alice", "alice@example.com")
        val cipher = CryptoUtils.encrypt(key, payload.toByteArray(Charsets.UTF_8))
        val plain  = CryptoUtils.decrypt(key, cipher).toString(Charsets.UTF_8)
        assertEquals(payload, plain)
    }

    // -------------------------------------------------------------------------
    // 3. SAS symmetry (both sides see the same 6-digit code)
    // -------------------------------------------------------------------------

    @Test
    fun `SAS is symmetric -- both parties derive the same code`() {
        // Alice derives from (her key, Bob's key) — Bob derives from (Bob's key, Alice's key).
        val aliceSas = SasVerifier.derive(aliceEcdh.public, bobEcdh.public)
        val bobSas   = SasVerifier.derive(bobEcdh.public, aliceEcdh.public)
        assertEquals("SAS must be identical on both sides", aliceSas, bobSas)
    }

    // -------------------------------------------------------------------------
    // 4. SAS MITM detection — substituted key gives different SAS
    // -------------------------------------------------------------------------

    @Test
    fun `MITM substituting their key produces a different SAS`() {
        val mitmEcdh = CryptoUtils.generateEphemeralECDHKeyPair()

        // Honest exchange SAS
        val honestAliceSas = SasVerifier.derive(aliceEcdh.public, bobEcdh.public)

        // What Alice sees if MITM substitutes Bob's key with their own
        val mitmSasOnAlice = SasVerifier.derive(aliceEcdh.public, mitmEcdh.public)

        // If Alice's SAS equals the MITM SAS this is a collision (astronomically unlikely)
        assertNotEquals(
            "MITM substituted key should produce a different SAS (collision would be a bug)",
            honestAliceSas,
            mitmSasOnAlice
        )
    }

    // -------------------------------------------------------------------------
    // 5. SAS uses ephemeral keys (not identity keys)
    // -------------------------------------------------------------------------

    @Test
    fun `different ephemeral sessions produce different SAS codes`() {
        val session2Alice = CryptoUtils.generateEphemeralECDHKeyPair()
        val session2Bob   = CryptoUtils.generateEphemeralECDHKeyPair()

        val sas1 = SasVerifier.derive(aliceEcdh.public, bobEcdh.public)
        val sas2 = SasVerifier.derive(session2Alice.public, session2Bob.public)

        // Two independent sessions should (with overwhelming probability) produce different SAS
        assertNotEquals("Independent sessions must produce fresh SAS codes", sas1, sas2)
    }

    // -------------------------------------------------------------------------
    // 6. Mutual exchange — both Alice and Bob can send + receive
    // -------------------------------------------------------------------------

    @Test
    fun `mutual encrypted exchange both parties can read each other's profile`() {
        val aliceKey = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val bobKey   = CryptoUtils.deriveSharedAESKey(bobEcdh.private, aliceEcdh.public)

        // Alice encrypts for Bob; Bob encrypts for Alice
        val alicePayload = sampleProfileJson("Alice", "alice@test.com")
        val bobPayload   = sampleProfileJson("Bob", "bob@test.com")

        val aliceCipher = CryptoUtils.encrypt(aliceKey, alicePayload.toByteArray())
        val bobCipher   = CryptoUtils.encrypt(bobKey,   bobPayload.toByteArray())

        // Cross-decrypt
        val aliceDecrypted = CryptoUtils.decrypt(bobKey, aliceCipher).toString(Charsets.UTF_8)
        val bobDecrypted   = CryptoUtils.decrypt(aliceKey, bobCipher).toString(Charsets.UTF_8)

        assertEquals(alicePayload, aliceDecrypted)
        assertEquals(bobPayload,   bobDecrypted)
    }

    // -------------------------------------------------------------------------
    // 7. Contact.fromMap round-trip (post-decrypt contact construction)
    // -------------------------------------------------------------------------

    @Test
    fun `decrypted profile JSON produces correct Contact fields`() {
        val key = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val json = """{"displayName":"Alice","email":"alice@test.com","phone":"+1555","company":"ACME","title":"Engineer","website":"acme.io","bio":"Hello"}"""
        val cipher  = CryptoUtils.encrypt(key, json.toByteArray(Charsets.UTF_8))
        val plain   = CryptoUtils.decrypt(key, cipher).toString(Charsets.UTF_8)
        val peerObj = JSONObject(plain)
        val peerMap = buildMap<String, String> {
            peerObj.keys().forEach { k -> put(k, peerObj.optString(k)) }
        }
        val endpoint = "endpoint-xyz"
        val contact = Contact.fromMap(UUID.randomUUID().toString(), peerMap, endpoint)

        assertEquals("Alice",          contact.displayName)
        assertEquals("alice@test.com", contact.email)
        assertEquals("+1555",          contact.phone)
        assertEquals("ACME",           contact.company)
        assertEquals("Engineer",       contact.title)
        assertEquals("acme.io",        contact.website)
        assertEquals("Hello",          contact.bio)
        assertEquals(endpoint,         contact.sourceEndpointId)
    }

    // -------------------------------------------------------------------------
    // 8. Tampered ciphertext — must throw, not return garbage
    // -------------------------------------------------------------------------

    @Test(expected = Exception::class)
    fun `tampered ciphertext throws during decryption`() {
        val key = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val cipher = CryptoUtils.encrypt(key, "hello".toByteArray())
        // Flip a byte in the middle of the ciphertext to break the GCM tag
        cipher[cipher.size / 2] = cipher[cipher.size / 2].xor(0xFF.toByte())
        CryptoUtils.decrypt(key, cipher)  // must throw
    }

    // -------------------------------------------------------------------------
    // 9. Empty profile fields — Contact.fromMap handles empty map gracefully
    // -------------------------------------------------------------------------

    @Test
    fun `empty profile map produces Contact with blank fields, no crash`() {
        val contact = Contact.fromMap(UUID.randomUUID().toString(), emptyMap(), "ep-0")
        assertEquals("", contact.displayName)
        assertEquals("", contact.email)
        assertEquals("ep-0", contact.sourceEndpointId)
    }

    // -------------------------------------------------------------------------
    // 10. SAS range — always exactly 6 digits in [0, 10^6)
    // -------------------------------------------------------------------------

    @Test
    fun `SAS is always a 6-digit zero-padded decimal string`() {
        repeat(50) {
            val a = CryptoUtils.generateEphemeralECDHKeyPair()
            val b = CryptoUtils.generateEphemeralECDHKeyPair()
            val sas = SasVerifier.derive(a.public, b.public)
            assertEquals("SAS must always be exactly 6 digits", 6, sas.length)
            val value = sas.toLong()
            assertTrue("SAS must be in [0, 10^6)", value in 0 until 1_000_000L)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleProfileJson(name: String, email: String) =
        JSONObject(mapOf("displayName" to name, "email" to email, "phone" to "+1555")).toString()
}
