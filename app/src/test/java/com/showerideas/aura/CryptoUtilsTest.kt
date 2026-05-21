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
}
