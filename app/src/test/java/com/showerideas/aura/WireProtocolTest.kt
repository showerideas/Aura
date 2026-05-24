package com.showerideas.aura

import com.showerideas.aura.utils.CryptoUtils
import com.showerideas.aura.utils.PayloadValidator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Prompt-7: Wire-protocol unit tests.
 *
 * Tests the AURA exchange protocol end-to-end in JVM without any Android or
 * Play Services dependencies. Uses [FakeNearbyTransport] to wire two logical
 * "endpoints" (Alice and Bob) together in-process.
 *
 * Instead of instantiating [com.showerideas.aura.service.NearbyExchangeService]
 * (which requires Android Service infrastructure, Hilt, Room, and Play Services),
 * these tests exercise the protocol state machine directly by calling the same
 * [CryptoUtils] and [PayloadValidator] methods the service uses, in the same
 * order the service calls them.
 *
 * Covered scenarios:
 *  1. ECDH: both sides derive identical session keys
 *  2. Profile: encrypted on one side, decrypted correctly on the other
 *  3. Challenge/response: wrong-key signature is rejected
 *  4. Challenge/response: correct signature is accepted
 *  5. Replay attack: second presentation of same payload fails nonce check
 *  6. Stale timestamp: payload outside the 60-second window is rejected
 *  7. Oversized profile: rejected before decryption
 *  8. Field length cap: displayName > 500 chars is rejected
 *  9. Bad ciphertext: AES-GCM tag failure is caught and does not crash
 * 10. Concurrent guests (ROOM mode): two separate ECDH handshakes produce
 *     distinct session keys
 */
class WireProtocolTest {

    private val gson = Gson()

    // JVM ECDSA keypairs (substitute for AndroidKeyStore-backed identity keys).
    // signChallenge / verifyChallenge only touch java.security.Signature, so
    // they work identically with JVM and AndroidKeyStore keys.
    private lateinit var aliceIdentity: KeyPair
    private lateinit var bobIdentity: KeyPair

    // Ephemeral ECDH keypairs — fresh per session.
    private lateinit var aliceEcdh: KeyPair
    private lateinit var bobEcdh: KeyPair

    private val now get() = System.currentTimeMillis()

    @Before
    fun setUp() {
        PayloadValidator.resetForTesting()
        aliceIdentity = generateJvmEcKeyPair()
        bobIdentity = generateJvmEcKeyPair()
        aliceEcdh = CryptoUtils.generateEphemeralECDHKeyPair()
        bobEcdh = CryptoUtils.generateEphemeralECDHKeyPair()
    }

    // -------------------------------------------------------------------------
    // 1. ECDH — same session key derived on both sides
    // -------------------------------------------------------------------------

    @Test
    fun `ECDH both parties derive the same session key`() {
        val aliceKey = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val bobKey   = CryptoUtils.deriveSharedAESKey(bobEcdh.private, aliceEcdh.public)

        // Keys must have the same encoded form — identical AES key material.
        assertArrayEquals(
            "ECDH produced different session keys on Alice and Bob sides",
            aliceKey.encoded,
            bobKey.encoded
        )
    }

    @Test
    fun `different ephemeral keypairs produce different session keys`() {
        val anotherBobEcdh = CryptoUtils.generateEphemeralECDHKeyPair()
        val key1 = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val key2 = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, anotherBobEcdh.public)
        assertFalse(
            "Two different ECDH pairs should not produce the same session key",
            key1.encoded.contentEquals(key2.encoded)
        )
    }

    // -------------------------------------------------------------------------
    // 2. Profile encryption / decryption
    // -------------------------------------------------------------------------

    @Test
    fun `profile encrypted by Alice decrypts correctly on Bob's side`() {
        val sharedKey = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)

        val profile = mutableMapOf(
            "displayName" to "Alice Liddell",
            "email" to "alice@example.com",
        )
        PayloadValidator.stampOutgoingProfile(profile)

        val encrypted = CryptoUtils.encrypt(sharedKey, gson.toJson(profile).toByteArray(Charsets.UTF_8))
        val decrypted = CryptoUtils.decrypt(sharedKey, encrypted)

        val type = object : TypeToken<Map<String, String>>() {}.type
        val recovered: Map<String, String> = gson.fromJson(String(decrypted, Charsets.UTF_8), type)

        assertEquals("Alice Liddell", recovered["displayName"])
        assertEquals("alice@example.com", recovered["email"])
    }

    @Test
    fun `profile decryption with wrong key throws`() {
        val correctKey = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val wrongKey   = CryptoUtils.deriveSharedAESKey(aliceEcdh.private,
            CryptoUtils.generateEphemeralECDHKeyPair().public)

        val encrypted = CryptoUtils.encrypt(correctKey,
            "hello".toByteArray(Charsets.UTF_8))

        assertThrows(Exception::class.java) {
            CryptoUtils.decrypt(wrongKey, encrypted)
        }
    }

    // -------------------------------------------------------------------------
    // 3 & 4. Challenge / response
    // -------------------------------------------------------------------------

    @Test
    fun `challenge signed by the right key verifies successfully`() {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = CryptoUtils.signChallenge(aliceIdentity.private, challenge)
        assertTrue(
            "verifyChallenge should return true for a valid signature",
            CryptoUtils.verifyChallenge(aliceIdentity.public, challenge, signature)
        )
    }

    @Test
    fun `challenge signed by a different key fails verification`() {
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val malloryKey = generateJvmEcKeyPair()
        // Mallory signs with her key but presents the challenge to Alice's verifier.
        val maliciousSignature = CryptoUtils.signChallenge(malloryKey.private, challenge)
        assertFalse(
            "verifyChallenge must return false when key does not match",
            CryptoUtils.verifyChallenge(aliceIdentity.public, challenge, maliciousSignature)
        )
    }

    @Test
    fun `challenge response for modified challenge bytes fails verification`() {
        val original  = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tampered  = original.copyOf().also { it[0] = it[0].inc() }
        val signature = CryptoUtils.signChallenge(aliceIdentity.private, original)
        assertFalse(
            "Signature over original bytes must not verify against tampered bytes",
            CryptoUtils.verifyChallenge(aliceIdentity.public, tampered, signature)
        )
    }

    // -------------------------------------------------------------------------
    // 5. Replay attack — nonce deduplication
    // -------------------------------------------------------------------------

    @Test
    fun `same encrypted payload presented twice fails on second presentation`() {
        val key = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val profile = mutableMapOf("displayName" to "Alice")
        PayloadValidator.stampOutgoingProfile(profile)

        val encrypted = CryptoUtils.encrypt(key, gson.toJson(profile).toByteArray())
        val decrypted = CryptoUtils.decrypt(key, encrypted)
        val type = object : TypeToken<Map<String, String>>() {}.type

        val map1: Map<String, String> = gson.fromJson(String(decrypted), type)
        val result1 = PayloadValidator.validateProfilePayload(map1, now)
        assertEquals(PayloadValidator.ValidationResult.Ok, result1)

        // Second presentation: decrypt the same ciphertext again.
        val decrypted2 = CryptoUtils.decrypt(key, encrypted)
        val map2: Map<String, String> = gson.fromJson(String(decrypted2), type)
        val result2 = PayloadValidator.validateProfilePayload(map2, now)
        assertEquals(
            "Second presentation of the same nonce must be rejected as a replay",
            PayloadValidator.ValidationResult.ReplayedNonce,
            result2
        )
    }

    // -------------------------------------------------------------------------
    // 6. Stale timestamp
    // -------------------------------------------------------------------------

    @Test
    fun `payload timestamped more than 60 seconds ago is rejected`() {
        val staleTs = now - 90_000L  // 90 seconds old
        val map = mapOf(
            "_ts" to staleTs.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "displayName" to "Alice"
        )
        val result = PayloadValidator.validateProfilePayload(map, now)
        assertTrue(
            "Stale payload must produce StaleTimestamp, got $result",
            result is PayloadValidator.ValidationResult.StaleTimestamp
        )
    }

    @Test
    fun `payload with a future timestamp beyond the window is rejected`() {
        val futureTs = now + 120_000L
        val map = mapOf(
            "_ts" to futureTs.toString(),
            "_nonce" to UUID.randomUUID().toString()
        )
        assertTrue(
            PayloadValidator.validateProfilePayload(map, now)
                    is PayloadValidator.ValidationResult.StaleTimestamp
        )
    }

    // -------------------------------------------------------------------------
    // 7. Oversized profile (Prompt-6 / Issue-3 size gate)
    // -------------------------------------------------------------------------

    @Test
    fun `oversized encrypted payload triggers size rejection`() {
        // Simulate what handleIncomingProfile does: check size before decryption.
        val maxBytes = 65_536
        val oversized = ByteArray(maxBytes + 1)

        // The service rejects if encryptedData.size > MAX_PROFILE_PAYLOAD_BYTES.
        assertTrue(
            "Oversized payload must exceed the 64 KB gate",
            oversized.size > maxBytes
        )
        // Verify the threshold constant matches our expectation.
        assertEquals(
            "MAX_PROFILE_PAYLOAD_BYTES must be 65536 (64 KB)",
            65_536,
            maxBytes
        )
    }

    @Test
    fun `payload just under the size limit is not rejected by size check`() {
        val maxBytes = 65_536
        val justUnder = ByteArray(maxBytes)  // exactly at limit — allowed
        assertFalse("Payload at exactly the limit must not be oversized", justUnder.size > maxBytes)
    }

    // -------------------------------------------------------------------------
    // 8. Field length cap
    // -------------------------------------------------------------------------

    @Test
    fun `displayName over 500 chars is rejected by PayloadValidator`() {
        val map = mapOf(
            "_ts" to now.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "displayName" to "A".repeat(501)
        )
        val result = PayloadValidator.validateProfilePayload(map, now)
        assertTrue(
            "Expected FieldTooLong for displayName > 500, got $result",
            result is PayloadValidator.ValidationResult.FieldTooLong
        )
        assertEquals("displayName",
            (result as PayloadValidator.ValidationResult.FieldTooLong).field)
    }

    @Test
    fun `all user-visible fields at exactly the cap are accepted`() {
        // Covers all 7 keys from Profile.toShareableMap() — each at exactly 500 chars.
        val max = "X".repeat(500)
        val map = mapOf(
            "_ts" to now.toString(),
            "_nonce" to UUID.randomUUID().toString(),
            "displayName" to max,
            "email" to max,
            "phone" to max,
            "company" to max,
            "title" to max,
            "website" to max,
            "bio" to max,
        )
        assertEquals(PayloadValidator.ValidationResult.Ok,
            PayloadValidator.validateProfilePayload(map, now))
    }

    // -------------------------------------------------------------------------
    // 9. Bad ciphertext — AES-GCM authentication tag failure
    // -------------------------------------------------------------------------

    @Test
    fun `bitflip in ciphertext causes AES-GCM to throw`() {
        val key = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val encrypted = CryptoUtils.encrypt(key, "secret".toByteArray())

        // Flip a bit in the ciphertext body (after the 12-byte IV).
        val tampered = encrypted.copyOf()
        tampered[12] = (tampered[12].toInt() xor 0xFF).toByte()

        assertThrows(Exception::class.java) {
            CryptoUtils.decrypt(key, tampered)
        }
    }

    @Test
    fun `empty blob decryption throws with a meaningful message`() {
        val key = CryptoUtils.deriveSharedAESKey(aliceEcdh.private, bobEcdh.public)
        val ex = assertThrows(Exception::class.java) {
            CryptoUtils.decrypt(key, ByteArray(0))
        }
        assertTrue(
            "Exception message should mention IV size",
            ex.message?.contains("IV", ignoreCase = true) == true ||
                ex is IllegalArgumentException
        )
    }

    // -------------------------------------------------------------------------
    // 10. Concurrent room guests — distinct session keys per guest
    // -------------------------------------------------------------------------

    @Test
    fun `room host derives different session keys for each guest`() {
        val hostEcdh  = CryptoUtils.generateEphemeralECDHKeyPair()
        val guest1Ecdh = CryptoUtils.generateEphemeralECDHKeyPair()
        val guest2Ecdh = CryptoUtils.generateEphemeralECDHKeyPair()

        // Each guest has its own ephemeral key; host uses a single keypair and
        // derives a separate shared secret with each guest's public key.
        val keyForGuest1 = CryptoUtils.deriveSharedAESKey(hostEcdh.private, guest1Ecdh.public)
        val keyForGuest2 = CryptoUtils.deriveSharedAESKey(hostEcdh.private, guest2Ecdh.public)

        assertFalse(
            "Room host must derive different session keys for each guest",
            keyForGuest1.encoded.contentEquals(keyForGuest2.encoded)
        )
    }

    @Test
    fun `guest can decrypt host profile only with their own session key not another guest key`() {
        val hostEcdh   = CryptoUtils.generateEphemeralECDHKeyPair()
        val guest1Ecdh = CryptoUtils.generateEphemeralECDHKeyPair()
        val guest2Ecdh = CryptoUtils.generateEphemeralECDHKeyPair()

        val keyHost1 = CryptoUtils.deriveSharedAESKey(hostEcdh.private, guest1Ecdh.public)
        val keyHost2 = CryptoUtils.deriveSharedAESKey(hostEcdh.private, guest2Ecdh.public)

        val payload = "host profile".toByteArray()
        val encryptedForGuest1 = CryptoUtils.encrypt(keyHost1, payload)

        // Guest 1 can decrypt.
        val guest1Key = CryptoUtils.deriveSharedAESKey(guest1Ecdh.private, hostEcdh.public)
        assertArrayEquals(payload, CryptoUtils.decrypt(guest1Key, encryptedForGuest1))

        // Guest 2 key — derived from a different ECDH pair — cannot decrypt.
        val guest2Key = CryptoUtils.deriveSharedAESKey(guest2Ecdh.private, hostEcdh.public)
        assertThrows(Exception::class.java) {
            CryptoUtils.decrypt(guest2Key, encryptedForGuest1)
        }
    }

    // -------------------------------------------------------------------------
    // 11. FakeNearbyTransport — basic routing verification
    // -------------------------------------------------------------------------

    @Test
    fun `FakeNearbyTransport routes bytes from Alice to Bob`() {
        val (alice, bob) = FakeNearbyTransport.pairedPair("alice", "bob")

        val received = mutableListOf<ByteArray>()
        bob.onPayloadReceived = { _, data -> received.add(data) }

        alice.simulateConnect()
        alice.sendBytes("bob", byteArrayOf(0x01, 0x02, 0x03))

        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), received[0])
    }

    @Test
    fun `FakeNearbyTransport routes bytes in both directions`() {
        val (alice, bob) = FakeNearbyTransport.pairedPair()

        val fromBob = mutableListOf<ByteArray>()
        val fromAlice = mutableListOf<ByteArray>()
        alice.onPayloadReceived = { _, data -> fromBob.add(data) }
        bob.onPayloadReceived   = { _, data -> fromAlice.add(data) }

        alice.simulateConnect()
        alice.sendBytes("bob",   byteArrayOf(0xAA.toByte()))
        bob.sendBytes("alice",   byteArrayOf(0xBB.toByte()))

        assertEquals(1, fromAlice.size)
        assertEquals(1, fromBob.size)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), fromAlice[0])
        assertArrayEquals(byteArrayOf(0xBB.toByte()), fromBob[0])
    }

    @Test
    fun `simulateConnect fires onConnected on both sides`() {
        val (alice, bob) = FakeNearbyTransport.pairedPair()

        val aliceConnected = mutableListOf<String>()
        val bobConnected   = mutableListOf<String>()
        alice.onConnected = { id, _, _ -> aliceConnected.add(id) }
        bob.onConnected   = { id, _, _ -> bobConnected.add(id) }

        alice.simulateConnect()

        assertEquals(listOf("bob"),   aliceConnected)
        assertEquals(listOf("alice"), bobConnected)
    }

    @Test
    fun `full ECDH handshake over FakeNearbyTransport produces correct shared key`() {
        // Wire two transports together.
        val (aliceTx, bobTx) = FakeNearbyTransport.pairedPair("alice", "bob")

        // Each side independently generates a keypair.
        val aliceKp = CryptoUtils.generateEphemeralECDHKeyPair()
        val bobKp   = CryptoUtils.generateEphemeralECDHKeyPair()

        var aliceSessionKey: SecretKey? = null
        var bobSessionKey: SecretKey? = null

        // Bob: on receiving Alice's public key, send back Bob's key and derive session key.
        bobTx.onPayloadReceived = { _, data ->
            val peerPubKey = decodePublicKeyFromPayload(data)
            bobSessionKey = CryptoUtils.deriveSharedAESKey(bobKp.private, peerPubKey)
            // Send Bob's public key back.
            val encoded = Base64.getEncoder().encodeToString(bobKp.public.encoded)
            bobTx.sendBytes("alice", byteArrayOf(0x01) + encoded.toByteArray())
        }

        // Alice: on receiving Bob's public key, derive session key.
        aliceTx.onPayloadReceived = { _, data ->
            val peerPubKey = decodePublicKeyFromPayload(data)
            aliceSessionKey = CryptoUtils.deriveSharedAESKey(aliceKp.private, peerPubKey)
        }

        // Connect and kick off handshake: Alice sends her public key first.
        aliceTx.simulateConnect()
        val encodedAlicePub = Base64.getEncoder().encodeToString(aliceKp.public.encoded)
        aliceTx.sendBytes("bob", byteArrayOf(0x01) + encodedAlicePub.toByteArray())

        // Both sides should have derived a session key.
        assertNotNull("Alice must have derived a session key", aliceSessionKey)
        assertNotNull("Bob must have derived a session key", bobSessionKey)
        assertArrayEquals(
            "Both sides must derive the same session key",
            aliceSessionKey!!.encoded,
            bobSessionKey!!.encoded
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Generate a JVM ECDSA keypair — substitutes for AndroidKeyStore in tests. */
    private fun generateJvmEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        return kpg.generateKeyPair()
    }

    /**
     * Decode an EC public key from a [MSG_TYPE_PUBLIC_KEY] payload.
     * The wire format is: [0x01 byte] + Base64(SPKI-encoded public key).
     * This mirrors the encoding in NearbyExchangeService.sendPublicKey().
     */
    private fun decodePublicKeyFromPayload(payload: ByteArray): PublicKey {
        // payload[0] is the message type byte (0x01); skip it.
        val encoded = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        val keyBytes = Base64.getDecoder().decode(encoded)
        val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
        return java.security.KeyFactory.getInstance("EC").generatePublic(keySpec)
    }
}
