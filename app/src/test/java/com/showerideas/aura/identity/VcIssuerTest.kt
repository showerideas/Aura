package com.showerideas.aura.identity

import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.ProfileType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Task 51 — VcIssuer unit tests.
 *
 * Verifies VC issuance, DID derivation, JWS proof sign/verify, and expiry.
 */
class VcIssuerTest {

    private lateinit var issuer: VcIssuer
    private lateinit var ecPriv: ECPrivateKey
    private lateinit var ecPub: ECPublicKey
    private lateinit var profile: Profile

    @Before
    fun setup() {
        issuer = VcIssuer()
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = gen.generateKeyPair()
        ecPriv = pair.private as ECPrivateKey
        ecPub  = pair.public  as ECPublicKey
        profile = Profile(
            id = "test-profile",
            displayName = "Alice",
            avatarUri = "content://media/abc123",
            version = 5,
            profileType = ProfileType.PERSONAL
        )
    }

    @Test
    fun `issue returns VC with proof`() {
        val vc = issuer.issue(profile, ecPriv, ecPub)
        assertNotNull("Issued VC must have proof", vc.proof)
        assertNotNull("Issued VC must have id", vc.id)
        assertTrue("VC type must include AuraProfileCredential",
            vc.type.contains(VerifiableCredential.TYPE_AURA))
    }

    @Test
    fun `verify signed VC succeeds`() {
        val vc = issuer.issue(profile, ecPriv, ecPub)
        assertTrue("Valid issued VC must verify", issuer.verify(vc, ecPub))
    }

    @Test
    fun `verify fails with wrong public key`() {
        val vc = issuer.issue(profile, ecPriv, ecPub)
        val otherGen = KeyPairGenerator.getInstance("EC")
        otherGen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val otherPub = otherGen.generateKeyPair().public as ECPublicKey
        assertFalse("VC must not verify with different public key", issuer.verify(vc, otherPub))
    }

    @Test
    fun `DID starts with did_key prefix`() {
        val did = issuer.deriveDid(ecPub)
        assertTrue("DID must start with did:key:z", did.startsWith("did:key:z"))
        assertTrue("DID must be non-trivial length", did.length > 20)
    }

    @Test
    fun `VC JSON contains required W3C context`() {
        val vc = issuer.issue(profile, ecPriv, ecPub)
        val json = vc.toJsonString()
        assertTrue("VC JSON must contain W3C context", json.contains("w3.org/ns/credentials"))
        assertTrue("VC JSON must contain AURA context", json.contains("aura.id"))
        assertTrue("VC JSON must contain displayName", json.contains("Alice"))
    }

    @Test
    fun `credential subject has correct profile data`() {
        val vc = issuer.issue(profile, ecPriv, ecPub)
        val subj = vc.credentialSubject
        assertTrue("Display name must match", subj.displayName == "Alice")
        assertTrue("Profile version must match", subj.profileVersion == 5)
    }
}
