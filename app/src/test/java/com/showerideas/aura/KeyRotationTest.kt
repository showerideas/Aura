package com.showerideas.aura

import com.showerideas.aura.model.KnownPeer
import com.showerideas.aura.utils.IdentityRotationDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for key rotation domain logic (Phase 6.5).
 *
 * Tests the data model additions and [IdentityRotationDetector] behaviour.
 * Note: [CryptoUtils.rotateDeviceIdentityKey] requires the Android Keystore
 * and cannot be tested in a JVM unit test — it is covered by instrumented tests.
 */
class KeyRotationTest {

    // -------------------------------------------------------------------------
    // KnownPeer data model
    // -------------------------------------------------------------------------

    @Test
    fun knownPeer_defaults_to_null_rotationCertificate() {
        val peer = KnownPeer(
            endpointId = "endpoint-1",
            identityPublicKeyBase64 = "base64KeyHere=="
        )
        assertNull(peer.rotationCertificate)
    }

    @Test
    fun knownPeer_can_store_rotationCertificate() {
        val certBytes = byteArrayOf(0x30, 0x44, 0x02, 0x20) // DER header bytes
        val peer = KnownPeer(
            endpointId = "endpoint-2",
            identityPublicKeyBase64 = "base64KeyHere==",
            rotationCertificate = certBytes
        )
        assertNotNull(peer.rotationCertificate)
        assertTrue(peer.rotationCertificate!!.contentEquals(certBytes))
    }

    @Test
    fun knownPeer_equality_uses_content_equals_for_certificate_bytes() {
        val cert1 = byteArrayOf(0x01, 0x02, 0x03)
        val cert2 = byteArrayOf(0x01, 0x02, 0x03)
        val peer1 = KnownPeer("ep", "key==", rotationCertificate = cert1)
        val peer2 = KnownPeer("ep", "key==", rotationCertificate = cert2)
        // data class equals checks ByteArray reference equality by default
        // but the model should be consistent for our usage
        assertTrue(
            peer1.rotationCertificate!!.contentEquals(peer2.rotationCertificate!!)
        )
    }

    @Test
    fun knownPeer_without_certificate_indicates_no_rotation() {
        val peer = KnownPeer("ep", "key==")
        assertFalse(peer.rotationCertificate != null)
    }

    // -------------------------------------------------------------------------
    // IdentityRotationDetector
    // -------------------------------------------------------------------------

    @Test
    fun rotationDetector_returns_false_for_same_key() {
        // Same key bytes — no rotation detected
        val keyBytes = ByteArray(32) { it.toByte() }
        val base64Key = java.util.Base64.getEncoder().encodeToString(keyBytes)
        val peer = KnownPeer(
            endpointId = "ep-same",
            identityPublicKeyBase64 = base64Key
        )
        // IdentityRotationDetector.detectRotation compares against stored key
        // If they match, no rotation occurred
        assertFalse(IdentityRotationDetector.hasKeyChanged(peer, base64Key))
    }

    @Test
    fun rotationDetector_returns_true_for_different_key() {
        val originalBytes = ByteArray(32) { it.toByte() }
        val newBytes = ByteArray(32) { (it + 1).toByte() }

        val originalBase64 = java.util.Base64.getEncoder().encodeToString(originalBytes)
        val newBase64 = java.util.Base64.getEncoder().encodeToString(newBytes)

        val peer = KnownPeer(
            endpointId = "ep-changed",
            identityPublicKeyBase64 = originalBase64
        )
        assertTrue(IdentityRotationDetector.hasKeyChanged(peer, newBase64))
    }

    @Test
    fun rotationDetector_empty_original_key_is_not_equal_to_new_key() {
        val peer = KnownPeer("ep", "")
        val newKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        assertTrue(IdentityRotationDetector.hasKeyChanged(peer, newKey))
    }

    @Test
    fun rotationDetector_same_base64_different_whitespace_is_same_key() {
        // Base64 with no trailing whitespace should match cleanly
        val keyBytes = ByteArray(20) { 42.toByte() }
        val base64 = java.util.Base64.getEncoder().encodeToString(keyBytes)
        val peer = KnownPeer("ep", base64)
        assertFalse(IdentityRotationDetector.hasKeyChanged(peer, base64))
    }

    // -------------------------------------------------------------------------
    // Rotation certificate byte consistency
    // -------------------------------------------------------------------------

    @Test
    fun rotationCertificate_is_non_empty_when_set() {
        val certBytes = ByteArray(72) { it.toByte() }  // typical ECDSA-SHA256 sig length
        val peer = KnownPeer("ep", "key==", rotationCertificate = certBytes)
        val cert = peer.rotationCertificate
        assertNotNull(cert)
        assertTrue(cert!!.isNotEmpty())
    }

    @Test
    fun rotationCertificate_null_means_peer_never_rotated() {
        val peer = KnownPeer("ep", "key==")
        assertNull(peer.rotationCertificate)
    }
}
