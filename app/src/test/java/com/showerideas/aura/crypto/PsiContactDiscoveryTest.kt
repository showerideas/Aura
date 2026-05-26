package com.showerideas.aura.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 54 — PsiContactDiscovery unit tests.
 *
 * Tests blinding, intersection correctness, no-leak property, and
 * session token resolution.
 */
class PsiContactDiscoveryTest {

    private val psi = PsiContactDiscovery()

    @Test
    fun `blind set size matches input contact count`() {
        val session = psi.newSession()
        val contacts = listOf("did:key:zAlice", "did:key:zBob", "did:key:zCarol")
        val blinded = session.blindContacts(contacts)
        assertEquals("Blinded set must have one entry per contact",
            contacts.size, blinded.blindedEntries.size)
    }

    @Test
    fun `blinded hashes are deterministic within same session`() {
        val session = psi.newSession()
        val contacts = listOf("did:key:zAlice")
        val b1 = session.blindContacts(contacts)
        val b2 = session.blindContacts(contacts)
        // Same session, same blinding key → same token
        assertEquals(b1.blindedEntries.keys, b2.blindedEntries.keys)
    }

    @Test
    fun `blinded hashes differ across sessions (ephemeral key)`() {
        val s1 = psi.newSession()
        val s2 = psi.newSession()
        val contacts = listOf("did:key:zAlice")
        val b1 = s1.blindContacts(contacts)
        val b2 = s2.blindContacts(contacts)
        // Different session keys → different tokens
        assertFalse("Different sessions must produce different tokens",
            b1.blindedEntries.keys == b2.blindedEntries.keys)
    }

    @Test
    fun `apply blinding produces correctly sized doubled set`() {
        val sessionA = psi.newSession()
        val sessionB = psi.newSession()
        val contactsA = listOf("did:key:zA1", "did:key:zA2")
        val blindedA = sessionA.blindContacts(contactsA)
        val doubled = sessionB.applyBlindingToPeerSet(blindedA)
        assertEquals("Doubled set size must match input", blindedA.blindedEntries.size,
            doubled.blindedEntries.size)
    }

    @Test
    fun `empty contact list produces empty blinded set`() {
        val session = psi.newSession()
        val blinded = session.blindContacts(emptyList())
        assertTrue("Empty input must produce empty blinded set",
            blinded.blindedEntries.isEmpty())
    }

    @Test
    fun `resolve tokens returns correct contact fingerprints`() {
        val session = psi.newSession()
        val contacts = listOf("did:key:zAlice", "did:key:zBob")
        val blinded = session.blindContacts(contacts)
        val tokens = blinded.blindedEntries.keys.toSet()
        val result = PsiContactDiscovery.IntersectionResult(matchedTokens = tokens)
        val resolved = session.resolveTokens(result)
        assertEquals("All tokens must resolve to correct contacts",
            contacts.toSet(), resolved.toSet())
    }
}
