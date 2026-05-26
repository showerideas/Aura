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
 * Task 60 — VpBuilder unit tests.
 *
 * Tests VP construction, selective disclosure, proof presence, JSON format.
 */
class VpBuilderTest {

    private lateinit var issuer: VcIssuer
    private lateinit var vpBuilder: VpBuilder
    private lateinit var ecPriv: ECPrivateKey
    private lateinit var ecPub: ECPublicKey
    private lateinit var did: String
    private lateinit var vc: VerifiableCredential

    @Before
    fun setup() {
        issuer = VcIssuer()
        vpBuilder = VpBuilder(issuer)

        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = gen.generateKeyPair()
        ecPriv = pair.private as ECPrivateKey
        ecPub  = pair.public  as ECPublicKey
        did = issuer.deriveDid(ecPub)

        val profile = Profile(
            id = "test", displayName = "Alice", version = 3, profileType = ProfileType.PERSONAL
        )
        vc = issuer.issue(profile, ecPriv, ecPub)
    }

    @Test
    fun `VP is built with proof`() {
        val vp = vpBuilder.buildVp(
            vcs = listOf(vc),
            holderDid = did,
            nonce = "test-nonce-123",
            holderPriv = ecPriv,
            holderPub = ecPub
        )
        assertNotNull("VP must have a proof", vp.proof)
        assertTrue("VP must contain VC", vp.verifiableCredentials.isNotEmpty())
        assertTrue("VP holder must match DID", vp.holder == did)
    }

    @Test
    fun `VP JSON contains required fields`() {
        val vp = vpBuilder.buildVp(listOf(vc), did, "nonce-abc", ecPriv, ecPub)
        val json = vp.toJsonString()
        assertTrue("VP JSON must contain context", json.contains("w3.org/ns/credentials"))
        assertTrue("VP JSON must contain VerifiablePresentation type",
            json.contains(VpBuilder.VP_TYPE))
        assertTrue("VP JSON must contain holder DID", json.contains(did))
        assertTrue("VP JSON must contain nonce", json.contains("nonce-abc"))
    }

    @Test
    fun `selective disclosure hides non-disclosed fields`() {
        val vp = vpBuilder.buildVp(
            vcs = listOf(vc),
            holderDid = did,
            nonce = "nonce-sd",
            holderPriv = ecPriv,
            holderPub = ecPub,
            disclosedFields = setOf("displayName")  // only name, not avatarSha256
        )
        val json = vp.toJsonString()
        assertTrue("displayName must be disclosed", json.contains("Alice"))
        assertFalse("avatarSha256 must be redacted from VP JSON",
            json.contains("content://"))
    }

    @Test
    fun `VP with no selective disclosure includes all fields`() {
        val vp = vpBuilder.buildVp(listOf(vc), did, "nonce-full", ecPriv, ecPub,
            disclosedFields = null)
        val json = vp.toJsonString()
        assertTrue("All fields included when disclosedFields is null",
            json.contains("Alice"))
    }

    @Test
    fun `VP ID is unique per build`() {
        val vp1 = vpBuilder.buildVp(listOf(vc), did, "n1", ecPriv, ecPub)
        val vp2 = vpBuilder.buildVp(listOf(vc), did, "n2", ecPriv, ecPub)
        assertFalse("Each VP must have a unique ID", vp1.id == vp2.id)
    }
}
