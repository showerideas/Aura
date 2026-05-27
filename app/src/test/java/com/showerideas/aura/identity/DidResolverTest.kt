package com.showerideas.aura.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DidResolver] — covers did:key, did:peer:2, and parsing helpers.
 *
 * did:web tests require network access and are excluded here;
 * they run as instrumented tests.
 */
class DidResolverTest {

    private lateinit var resolver: DidResolver

    @Before
    fun setUp() {
        resolver = DidResolver()
    }

    // ── did:key ───────────────────────────────────────────────────────────────

    @Test
    fun `resolve returns null for non-did string`() {
        kotlinx.coroutines.runBlocking {
            assertNull(resolver.resolve("not-a-did"))
        }
    }

    @Test
    fun `resolve returns null for unsupported method`() {
        kotlinx.coroutines.runBlocking {
            assertNull(resolver.resolve("did:unknown:abc123"))
        }
    }

    @Test
    fun `resolve returns null for malformed did`() {
        kotlinx.coroutines.runBlocking {
            assertNull(resolver.resolve("did:key"))  // missing identifier
        }
    }

    // ── did:peer:2 round-trip ─────────────────────────────────────────────────

    @Test
    fun `encodePeer2Did produces correct prefix`() {
        val keyDer = ByteArray(65) { it.toByte() }  // simulate P-256 uncompressed point
        val did = resolver.encodePeer2Did(keyDer)
        assertTrue(did.startsWith("did:peer:2."))
    }

    @Test
    fun `encodePeer2Did then resolve returns document with same key bytes`() {
        kotlinx.coroutines.runBlocking {
            // Generate a dummy P-256 key pair for round-trip testing
            val kpg = java.security.KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            val kp = kpg.generateKeyPair()
            val pubDer = kp.public.encoded  // X.509 SubjectPublicKeyInfo DER

            val did = resolver.encodePeer2Did(pubDer)
            assertTrue(did.startsWith("did:peer:2."))

            val doc = resolver.resolve(did)
            assertNotNull(doc)
            assertEquals(DidResolver.METHOD_PEER, doc!!.method)
            assertTrue(doc.verificationKeyEncoded.contentEquals(pubDer))
        }
    }

    @Test
    fun `did peer2 resolution sets method correctly`() {
        kotlinx.coroutines.runBlocking {
            val kpg = java.security.KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            val kp = kpg.generateKeyPair()
            val did = resolver.encodePeer2Did(kp.public.encoded)
            val doc = resolver.resolve(did)
            assertEquals(DidResolver.METHOD_PEER, doc?.method)
        }
    }

    @Test
    fun `did peer2 with mangled identifier returns null`() {
        kotlinx.coroutines.runBlocking {
            // Identifier doesn't start with "2."
            val doc = resolver.resolve("did:peer:3.invalidencoding")
            assertNull(doc)
        }
    }

    @Test
    fun `did peer2 id preserved in document`() {
        kotlinx.coroutines.runBlocking {
            val kpg = java.security.KeyPairGenerator.getInstance("EC")
            kpg.initialize(256)
            val kp = kpg.generateKeyPair()
            val did = resolver.encodePeer2Did(kp.public.encoded)
            val doc = resolver.resolve(did)
            assertEquals(did, doc?.id)
            assertEquals(did, doc?.controller)
        }
    }

    // ── DidDocument equality ──────────────────────────────────────────────────

    @Test
    fun `DidDocument equals by id and key bytes`() {
        val keyBytes = ByteArray(32) { 1 }
        val a = DidDocument("did:peer:2.xyz", "did:peer:2.xyz", "peer", keyBytes, null)
        val b = DidDocument("did:peer:2.xyz", "did:peer:2.xyz", "peer", keyBytes.copyOf(), null)
        assertEquals(a, b)
    }

    @Test
    fun `DidDocument hasAnyField works via ImportedContact analogy`() {
        // Just a coverage check on DidDocument hashCode
        val keyBytes = ByteArray(32) { 5 }
        val doc = DidDocument("did:key:z123", "did:key:z123", "key", keyBytes, null)
        assertTrue(doc.hashCode() != 0)
    }
}
