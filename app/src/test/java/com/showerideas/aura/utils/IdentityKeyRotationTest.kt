package com.showerideas.aura.utils

import com.showerideas.aura.model.RotationCertificate
import org.junit.Assert.*
import org.junit.Test

class IdentityKeyRotationTest {

    @Test
    fun `rotation cert round-trip — sign with old key verify on receiver`() {
        val (oldPriv, oldPub) = IdentityKeyRotator.generateNewKeyPair()
        val (_, newPub)       = IdentityKeyRotator.generateNewKeyPair()
        val cert  = IdentityKeyRotator.createRotationCertificate(oldPriv, oldPub, newPub)
        val valid = IdentityKeyRotator.verifyRotationCertificate(cert, oldPub.encoded)
        assertTrue("Rotation cert should be valid", valid)
    }

    @Test
    fun `rotation cert with wrong known old key fails verification`() {
        val (oldPriv, oldPub) = IdentityKeyRotator.generateNewKeyPair()
        val (_, newPub)       = IdentityKeyRotator.generateNewKeyPair()
        val (_, diffPub)      = IdentityKeyRotator.generateNewKeyPair()
        val cert  = IdentityKeyRotator.createRotationCertificate(oldPriv, oldPub, newPub)
        val valid = IdentityKeyRotator.verifyRotationCertificate(cert, diffPub.encoded)
        assertFalse("Wrong known key should fail", valid)
    }

    @Test
    fun `RotationCertificate serialize-deserialize round-trip`() {
        val (oldPriv, oldPub) = IdentityKeyRotator.generateNewKeyPair()
        val (_, newPub)       = IdentityKeyRotator.generateNewKeyPair()
        val cert         = IdentityKeyRotator.createRotationCertificate(oldPriv, oldPub, newPub)
        val serialized   = cert.serialize()
        val deserialized = RotationCertificate.deserialize(serialized)
        assertEquals(cert, deserialized)
    }

    @Test
    fun `tampered new public key bytes fail verification`() {
        val (oldPriv, oldPub) = IdentityKeyRotator.generateNewKeyPair()
        val (_, newPub)       = IdentityKeyRotator.generateNewKeyPair()
        val cert     = IdentityKeyRotator.createRotationCertificate(oldPriv, oldPub, newPub)
        val tampered = cert.copy(newPublicKeyBytes = ByteArray(cert.newPublicKeyBytes.size) { 0xFF.toByte() })
        val valid    = IdentityKeyRotator.verifyRotationCertificate(tampered, oldPub.encoded)
        assertFalse("Tampered cert should fail", valid)
    }
}
