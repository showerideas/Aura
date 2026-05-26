package com.showerideas.aura.identity

import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Task 61 — MdocDocument unit tests.
 *
 * Tests mdoc construction from VC, element presence, selective disclosure,
 * validity period, and MdocIssuer sign/verify.
 */
class MdocDocumentTest {

    private lateinit var ecPriv: ECPrivateKey
    private lateinit var ecPub: ECPublicKey
    private lateinit var vc: VerifiableCredential
    private lateinit var issuer: VcIssuer
    private lateinit var mdocIssuer: MdocIssuer

    @Before
    fun setup() {
        issuer = VcIssuer()
        mdocIssuer = MdocIssuer(issuer)

        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair = gen.generateKeyPair()
        ecPriv = pair.private as ECPrivateKey
        ecPub  = pair.public  as ECPublicKey

        val profile = Profile("id1", "Alice Smith", version = 2, profileType = ProfileType.PERSONAL)
        vc = issuer.issue(profile, ecPriv, ecPub)
    }

    @Test
    fun `mdoc has correct docType`() {
        val doc = MdocDocument.fromVc(vc, "Alice Smith")
        assertEquals(MdocDocument.AURA_DOCTYPE, doc.docType)
    }

    @Test
    fun `mdoc contains mandatory elements`() {
        val doc = MdocDocument.fromVc(vc, "Alice Smith")
        val ns = doc.nameSpaces[MdocDocument.AURA_NS] ?: error("namespace missing")
        assertTrue("given_name must be present", "given_name" in ns)
        assertTrue("family_name must be present", "family_name" in ns)
        assertTrue("aura_did must be present", "aura_did" in ns)
        assertTrue("issue_date must be present", "issue_date" in ns)
        assertTrue("expiry_date must be present", "expiry_date" in ns)
    }

    @Test
    fun `given_name is first word of display name`() {
        val doc = MdocDocument.fromVc(vc, "Alice Smith")
        val ns = doc.nameSpaces[MdocDocument.AURA_NS]!!
        val givenName = (ns["given_name"] as MdocElement.Text).value
        assertEquals("Alice", givenName)
    }

    @Test
    fun `document is valid immediately after creation`() {
        val doc = MdocDocument.fromVc(vc, "Alice")
        assertTrue("New document must be valid", doc.isValid())
    }

    @Test
    fun `selective disclosure hides portrait_hash when not requested`() {
        val doc = MdocDocument.fromVc(vc, "Alice Smith", disclosedFields = setOf("given_name"))
        val ns = doc.nameSpaces[MdocDocument.AURA_NS]!!
        assertFalse("portrait_hash must not be present when not disclosed", "portrait_hash" in ns)
    }

    @Test
    fun `MdocIssuer sign and verify round-trip succeeds`() {
        val envelope = mdocIssuer.issue(vc, "Alice Smith", null, ecPriv, ecPub)
        assertTrue("Signed mdoc must verify", mdocIssuer.verify(envelope, ecPub))
    }

    @Test
    fun `disclosed elements returns only marked-as-disclosed entries`() {
        val doc = MdocDocument.fromVc(vc, "Alice")
        val disclosed = doc.disclosedElements()
        assertTrue("All mandatory elements are disclosed by default",
            setOf("given_name", "family_name", "aura_did").all { it in disclosed })
    }
}
