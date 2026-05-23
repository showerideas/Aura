package com.showerideas.aura

import com.showerideas.aura.utils.CryptoUtils
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for the ECDH + AES-GCM crypto stack.
 */
class CryptoUtilsTest {

    @Test
    fun `ECDH key agreement produces same session key on both sides`() {
        val aliceKp = CryptoUtils.generateEphemeralECDHKeyPair()
        val bobKp = CryptoUtils.generateEphemeralECDHKeyPair()

        val aliceKey = CryptoUtils.deriveSharedAESKey(aliceKp.private, bobKp.public)
        val bobKey = CryptoUtils.deriveSharedAESKey(bobKp.private, aliceKp.public)

        assertArrayEquals(aliceKey.encoded, bobKey.encoded)
    }

    @Test
    fun `encrypt then decrypt produces original plaintext`() {
        val kp1 = CryptoUtils.generateEphemeralECDHKeyPair()
        val kp2 = CryptoUtils.generateEphemeralECDHKeyPair()
        val key = CryptoUtils.deriveSharedAESKey(kp1.private, kp2.public)

        val original = "Hello, AURA! {\"name\":\"Alice\",\"phone\":\"+1555000\"}".toByteArray()
        val encrypted = CryptoUtils.encrypt(key, original)
        val decrypted = CryptoUtils.decrypt(key, encrypted)

        assertArrayEquals(original, decrypted)
    }

    @Test
    fun `different session keys cannot decrypt each other's ciphertext`() {
        val kp1 = CryptoUtils.generateEphemeralECDHKeyPair()
        val kp2 = CryptoUtils.generateEphemeralECDHKeyPair()
        val kp3 = CryptoUtils.generateEphemeralECDHKeyPair()

        val sessionKey = CryptoUtils.deriveSharedAESKey(kp1.private, kp2.public)
        val wrongKey = CryptoUtils.deriveSharedAESKey(kp1.private, kp3.public)

        val ciphertext = CryptoUtils.encrypt(sessionKey, "secret".toByteArray())

        try {
            CryptoUtils.decrypt(wrongKey, ciphertext)
            fail("Expected decryption with wrong key to fail")
        } catch (e: Exception) {
            // Expected — AES-GCM authentication tag mismatch
        }
    }

    @Test
    fun `ephemeral keypairs are unique per call`() {
        val kp1 = CryptoUtils.generateEphemeralECDHKeyPair()
        val kp2 = CryptoUtils.generateEphemeralECDHKeyPair()
        assertFalse(kp1.public.encoded.contentEquals(kp2.public.encoded))
    }

    /**
     * PR-02 race condition: when both sides simultaneously send their
     * public key before receiving the other's, each side derives its own
     * shared secret independently. The ECDH math is symmetric: regardless
     * of which order the keys arrive, both sides converge on the same
     * AES-256 session key bytes.
     */
    @Test
    fun `ECDH key derivation is symmetric regardless of which side sends first`() {
        val aliceKp = CryptoUtils.generateEphemeralECDHKeyPair()
        val bobKp = CryptoUtils.generateEphemeralECDHKeyPair()

        // Alice receives Bob's key first — derives with (alicePriv, bobPub).
        val aliceKey = CryptoUtils.deriveSharedAESKey(aliceKp.private, bobKp.public)
        // Bob receives Alice's key first — derives with (bobPriv, alicePub).
        val bobKey = CryptoUtils.deriveSharedAESKey(bobKp.private, aliceKp.public)

        assertArrayEquals(
            "Session key must be order-independent for the both-sides-send-first race",
            aliceKey.encoded, bobKey.encoded
        )

        // And the round-trip works either way.
        val plaintext = "race-condition-payload".toByteArray()
        val ct = CryptoUtils.encrypt(aliceKey, plaintext)
        val pt = CryptoUtils.decrypt(bobKey, ct)
        assertArrayEquals(plaintext, pt)
    }

    // -------------------------------------------------------------------------
    // FIX-1: HKDF-SHA256 after ECDH — NIST SP 800-56A compliance
    // -------------------------------------------------------------------------

    @Test
    fun `derived key is 32 bytes`() {
        val aliceKp = CryptoUtils.generateEphemeralECDHKeyPair()
        val bobKp = CryptoUtils.generateEphemeralECDHKeyPair()
        val aliceKey = CryptoUtils.deriveSharedAESKey(aliceKp.private, bobKp.public)
        assertEquals(
            "HKDF-derived AES key must be exactly 32 bytes for AES-256",
            32, aliceKey.encoded.size
        )
    }

    @Test
    fun `raw ECDH bytes differ from HKDF-derived key`() {
        // Verify HKDF is transforming the shared secret, not simply truncating it.
        val aliceKp = CryptoUtils.generateEphemeralECDHKeyPair()
        val bobKp = CryptoUtils.generateEphemeralECDHKeyPair()

        // Derive the raw ECDH shared secret the old (insecure) way.
        val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
        ka.init(aliceKp.private)
        ka.doPhase(bobKp.public, true)
        val rawSecret = ka.generateSecret()

        val derivedKey = CryptoUtils.deriveSharedAESKey(aliceKp.private, bobKp.public)

        assertFalse(
            "HKDF output must differ from raw ECDH shared secret — KDF is not a no-op",
            rawSecret.copyOf(32).contentEquals(derivedKey.encoded)
        )
    }

    // -------------------------------------------------------------------------
    // PR-13: ECDSA challenge / response
    //
    // The Android Keystore path (getOrCreateDeviceIdentityKey) is unavailable
    // on the JVM — so for unit testing we generate plain secp256r1 keys via
    // KeyPairGenerator and exercise signChallenge / verifyChallenge directly.
    // -------------------------------------------------------------------------

    private fun makeIdentityKeyPair(): java.security.KeyPair {
        val kpg = java.security.KeyPairGenerator.getInstance("EC")
        kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    @Test
    fun `signChallenge and verifyChallenge round trip passes`() {
        val kp = makeIdentityKeyPair()
        val challenge = ByteArray(32) { it.toByte() }
        val sig = CryptoUtils.signChallenge(kp.private, challenge)
        assertTrue(
            "A signature produced by signChallenge must verify against its own public key",
            CryptoUtils.verifyChallenge(kp.public, challenge, sig)
        )
    }

    @Test
    fun `tampered challenge bytes fail verification`() {
        val kp = makeIdentityKeyPair()
        val challenge = ByteArray(32) { it.toByte() }
        val sig = CryptoUtils.signChallenge(kp.private, challenge)
        // Flip a single byte in the challenge — the signature must now reject.
        val tampered = challenge.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFalse(
            "Verification must fail when the challenge is mutated post-signing",
            CryptoUtils.verifyChallenge(kp.public, tampered, sig)
        )
    }

    @Test
    fun `wrong public key fails verification`() {
        val signer = makeIdentityKeyPair()
        val attacker = makeIdentityKeyPair()
        val challenge = ByteArray(32) { (it * 7).toByte() }
        val sig = CryptoUtils.signChallenge(signer.private, challenge)
        assertFalse(
            "Verification must fail when the wrong identity key is presented",
            CryptoUtils.verifyChallenge(attacker.public, challenge, sig)
        )
    }

    @Test
    fun `garbage signature bytes fail verification without throwing`() {
        val kp = makeIdentityKeyPair()
        val challenge = ByteArray(32) { it.toByte() }
        // Random byte garbage — verifyChallenge wraps in runCatching so it
        // must return false, never throw.
        val garbage = ByteArray(72) { (it * 13).toByte() }
        assertFalse(
            "Malformed signatures must be swallowed and return false",
            CryptoUtils.verifyChallenge(kp.public, challenge, garbage)
        )
    }
}
