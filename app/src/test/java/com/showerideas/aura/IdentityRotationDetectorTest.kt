package com.showerideas.aura

import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.local.ContactDao
import com.showerideas.aura.model.Contact
import com.showerideas.aura.utils.IdentityRotationDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [IdentityRotationDetector].
 *
 * Verifies the TOFU (Trust On First Use) key-rotation logic that guards against
 * MITM key substitution in AURA's P2P exchange protocol.
 *
 * No Android or Room dependencies — all tests run on JVM using in-memory fakes.
 */
class IdentityRotationDetectorTest {

    // -------------------------------------------------------------------------
    // In-memory test doubles
    // -------------------------------------------------------------------------

    /**
     * Minimal ContactDao stub that returns a fixed list from [observeAll].
     * All mutating methods throw [UnsupportedOperationException] since the
     * detector only ever reads — it never writes contacts.
     */
    private class StubContactDao(private val contacts: List<Contact>) : ContactDao {
        override fun observeAll(): Flow<List<Contact>> = flowOf(contacts)
        override fun observeFavorites(): Flow<List<Contact>> = flowOf(emptyList())
        override fun search(query: String): Flow<List<Contact>> = flowOf(emptyList())
        override suspend fun getById(id: String): Contact? = null
        override suspend fun insert(contact: Contact) = Unit
        override suspend fun update(contact: Contact) = Unit
        override suspend fun delete(contact: Contact) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun count(): Int = contacts.size
        override suspend fun findLatestByEndpoint(endpointId: String): Contact? = null
        override suspend fun findByIdentityKeyHash(hash: String): Contact? = null
    }

    private fun detectorWith(contacts: List<Contact>): IdentityRotationDetector =
        IdentityRotationDetector(ContactRepository(StubContactDao(contacts)))

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeContact(name: String, keyHash: String? = null) = Contact(
        id = "test-${name.lowercase().replace(' ', '-')}",
        displayName = name,
        identityKeyHash = keyHash
    )

    // -------------------------------------------------------------------------
    // check() — first contact (no stored identity)
    // -------------------------------------------------------------------------

    @Test
    fun `check returns FirstContact when no contacts exist`() = runTest {
        val detector = detectorWith(emptyList())
        val result = detector.check("Alice", "hash-abc")
        assertEquals(
            IdentityRotationDetector.RotationEvent.FirstContact,
            result
        )
    }

    @Test
    fun `check returns FirstContact when contact exists but has no identity hash`() = runTest {
        val alice = makeContact("Alice", keyHash = null)
        val detector = detectorWith(listOf(alice))
        val result = detector.check("Alice", "hash-abc")
        assertEquals(
            "A contact with null identityKeyHash should be treated as first-contact",
            IdentityRotationDetector.RotationEvent.FirstContact,
            result
        )
    }

    @Test
    fun `check returns FirstContact when name does not match any stored contact`() = runTest {
        val bob = makeContact("Bob", keyHash = "hash-bob")
        val detector = detectorWith(listOf(bob))
        val result = detector.check("Alice", "hash-alice")
        assertEquals(
            "Unknown peer name should return FirstContact regardless of stored contacts",
            IdentityRotationDetector.RotationEvent.FirstContact,
            result
        )
    }

    // -------------------------------------------------------------------------
    // check() — matching key (returning peer, same device)
    // -------------------------------------------------------------------------

    @Test
    fun `check returns KeyMatches when incoming hash equals stored hash`() = runTest {
        val alice = makeContact("Alice", keyHash = "hash-alice-123")
        val detector = detectorWith(listOf(alice))
        val result = detector.check("Alice", "hash-alice-123")
        assertTrue(
            "Same key hash should produce KeyMatches",
            result is IdentityRotationDetector.RotationEvent.KeyMatches
        )
        assertEquals("hash-alice-123", (result as IdentityRotationDetector.RotationEvent.KeyMatches).storedHash)
    }

    @Test
    fun `check is case-insensitive for display name`() = runTest {
        val alice = makeContact("Alice Smith", keyHash = "hash-alice")
        val detector = detectorWith(listOf(alice))
        // Peer might announce their name with slightly different casing
        val result = detector.check("alice smith", "hash-alice")
        assertTrue(
            "Name comparison should be case-insensitive",
            result is IdentityRotationDetector.RotationEvent.KeyMatches
        )
    }

    // -------------------------------------------------------------------------
    // check() — rotated key (possible MITM, reinstall, or new device)
    // -------------------------------------------------------------------------

    @Test
    fun `check returns KeyRotated when incoming hash differs from stored hash`() = runTest {
        val alice = makeContact("Alice", keyHash = "hash-alice-OLD")
        val detector = detectorWith(listOf(alice))
        val result = detector.check("Alice", "hash-alice-NEW")
        assertTrue(
            "Different key hash should produce KeyRotated",
            result is IdentityRotationDetector.RotationEvent.KeyRotated
        )
        val rotated = result as IdentityRotationDetector.RotationEvent.KeyRotated
        assertEquals("hash-alice-OLD", rotated.storedHash)
        assertEquals("hash-alice-NEW", rotated.incomingHash)
    }

    @Test
    fun `KeyRotated shortDiff contains abbreviated fingerprints of both keys`() = runTest {
        val alice = makeContact("Alice", keyHash = "ABCDEFGHIJKLMNOP")
        val detector = detectorWith(listOf(alice))
        val result = detector.check("Alice", "ZYXWVUTSRQPONMLK")
        val rotated = result as IdentityRotationDetector.RotationEvent.KeyRotated
        // shortDiff shows first 12 chars of each hash
        assertTrue("shortDiff must contain truncated stored hash",
            rotated.shortDiff.contains("ABCDEFGHIJKL"))
        assertTrue("shortDiff must contain truncated incoming hash",
            rotated.shortDiff.contains("ZYXWVUTSRQPO"))
    }

    // -------------------------------------------------------------------------
    // checkByStoredHash() — lookup by key hash rather than name
    // -------------------------------------------------------------------------

    @Test
    fun `checkByStoredHash returns KeyMatches when hashes are equal`() = runTest {
        val detector = detectorWith(emptyList()) // contacts not needed for equal-hash path
        val result = detector.checkByStoredHash("hash-xyz", "hash-xyz")
        assertEquals(
            "Equal hashes should return KeyMatches without DB lookup",
            IdentityRotationDetector.RotationEvent.KeyMatches("hash-xyz"),
            result
        )
    }

    @Test
    fun `checkByStoredHash returns FirstContact when stored hash not found in contacts`() = runTest {
        val alice = makeContact("Alice", keyHash = "hash-alice")
        val detector = detectorWith(listOf(alice))
        // Query a hash that no contact has
        val result = detector.checkByStoredHash("hash-unknown", "hash-new")
        assertEquals(
            "Unknown stored hash should return FirstContact",
            IdentityRotationDetector.RotationEvent.FirstContact,
            result
        )
    }

    @Test
    fun `checkByStoredHash returns KeyRotated when stored hash exists but incoming differs`() = runTest {
        val alice = makeContact("Alice", keyHash = "hash-alice-stored")
        val detector = detectorWith(listOf(alice))
        val result = detector.checkByStoredHash("hash-alice-stored", "hash-alice-NEW")
        assertTrue(
            "Known stored hash with different incoming hash should return KeyRotated",
            result is IdentityRotationDetector.RotationEvent.KeyRotated
        )
        val rotated = result as IdentityRotationDetector.RotationEvent.KeyRotated
        assertEquals("hash-alice-stored", rotated.storedHash)
        assertEquals("hash-alice-NEW",    rotated.incomingHash)
    }

    // -------------------------------------------------------------------------
    // Multi-contact edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `check uses first contact with non-null hash when multiple match the name`() = runTest {
        // Multiple contacts with the same name — ambiguous but handled by first-match.
        val alice1 = makeContact("Alice", keyHash = null)           // no hash — skipped
        val alice2 = makeContact("Alice", keyHash = "hash-alice-2") // has hash — matched
        val detector = detectorWith(listOf(alice1, alice2))
        val result = detector.check("Alice", "hash-alice-2")
        assertTrue(
            "Should match alice2 (first contact with non-null hash)",
            result is IdentityRotationDetector.RotationEvent.KeyMatches
        )
    }

    @Test
    fun `check ignores contacts for other names`() = runTest {
        val bob   = makeContact("Bob",   keyHash = "hash-bob")
        val carol = makeContact("Carol", keyHash = "hash-carol")
        val detector = detectorWith(listOf(bob, carol))
        // Checking Alice — neither Bob nor Carol should match
        val result = detector.check("Alice", "hash-alice")
        assertEquals(IdentityRotationDetector.RotationEvent.FirstContact, result)
    }
}
