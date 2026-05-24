package com.showerideas.aura.utils

import com.showerideas.aura.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ContactDiffEngine].
 *
 * No Android framework required — pure Kotlin data class operations.
 */
class ContactDiffEngineTest {

    private fun contact(
        name: String = "Alice",
        phone: String = "123",
        email: String = "alice@test.com",
        company: String = "Acme",
        title: String = "Engineer",
        website: String = "alice.dev",
        bio: String = "Hi",
        avatarUri: String = "",
        hash: String = "hash123"
    ) = Contact(
        id = "test-id",
        displayName = name,
        phone = phone,
        email = email,
        company = company,
        title = title,
        website = website,
        bio = bio,
        avatarUri = avatarUri,
        identityKeyHash = hash
    )

    // -------------------------------------------------------------------------
    // No changes
    // -------------------------------------------------------------------------

    @Test
    fun `diff identical contacts returns empty diff`() {
        val prev = contact()
        val incoming = contact()
        val event = ContactDiffEngine.diff(prev, incoming)
        assertFalse(event.hasChanges)
        assertTrue(event.diffs.isEmpty())
    }

    @Test
    fun `diff trims whitespace before comparing`() {
        val prev = contact(name = "Alice")
        val incoming = contact(name = " Alice ")
        val event = ContactDiffEngine.diff(prev, incoming)
        assertFalse("Whitespace-only difference should not produce a diff", event.hasChanges)
    }

    // -------------------------------------------------------------------------
    // Single field changes
    // -------------------------------------------------------------------------

    @Test
    fun `diff detects name change`() {
        val event = ContactDiffEngine.diff(contact(name = "Alice"), contact(name = "Alice Smith"))
        assertEquals(1, event.diffs.size)
        assertEquals("displayName", event.diffs[0].field)
        assertEquals("Alice", event.diffs[0].oldValue)
        assertEquals("Alice Smith", event.diffs[0].newValue)
    }

    @Test
    fun `diff detects phone change`() {
        val event = ContactDiffEngine.diff(contact(phone = "111"), contact(phone = "222"))
        assertEquals(1, event.diffs.size)
        assertEquals("phone", event.diffs[0].field)
    }

    @Test
    fun `diff detects email change`() {
        val event = ContactDiffEngine.diff(contact(email = "a@b.com"), contact(email = "c@d.com"))
        assertEquals(1, event.diffs.size)
        assertEquals("email", event.diffs[0].field)
    }

    @Test
    fun `diff detects company change`() {
        val event = ContactDiffEngine.diff(contact(company = "Acme"), contact(company = "Widgets Inc"))
        assertEquals(1, event.diffs.size)
        assertEquals("company", event.diffs[0].field)
    }

    @Test
    fun `diff detects title change`() {
        val event = ContactDiffEngine.diff(contact(title = "Engineer"), contact(title = "Staff Engineer"))
        assertEquals(1, event.diffs.size)
        assertEquals("title", event.diffs[0].field)
    }

    @Test
    fun `diff detects website change`() {
        val event = ContactDiffEngine.diff(contact(website = "old.dev"), contact(website = "new.dev"))
        assertEquals(1, event.diffs.size)
        assertEquals("website", event.diffs[0].field)
    }

    @Test
    fun `diff detects bio change`() {
        val event = ContactDiffEngine.diff(contact(bio = "Short bio"), contact(bio = "Updated bio"))
        assertEquals(1, event.diffs.size)
        assertEquals("bio", event.diffs[0].field)
    }

    // -------------------------------------------------------------------------
    // Avatar change
    // -------------------------------------------------------------------------

    @Test
    fun `diff detects avatar added`() {
        val event = ContactDiffEngine.diff(
            contact(avatarUri = ""),
            contact(avatarUri = "content://some.uri")
        )
        assertEquals(1, event.diffs.size)
        assertEquals("avatarUri", event.diffs[0].field)
        assertEquals("(none)", event.diffs[0].oldValue)
        assertEquals("(photo)", event.diffs[0].newValue)
    }

    @Test
    fun `diff detects avatar removed`() {
        val event = ContactDiffEngine.diff(
            contact(avatarUri = "content://old.uri"),
            contact(avatarUri = "")
        )
        assertEquals(1, event.diffs.size)
        assertEquals("avatarUri", event.diffs[0].field)
        assertEquals("(photo)", event.diffs[0].oldValue)
        assertEquals("(none)", event.diffs[0].newValue)
    }

    @Test
    fun `diff does NOT report avatar change when both have URIs (URIs change across sessions)`() {
        val event = ContactDiffEngine.diff(
            contact(avatarUri = "content://uri1"),
            contact(avatarUri = "content://uri2")
        )
        // Both have avatars — presence is the same; no diff expected
        val avatarDiff = event.diffs.filter { it.field == "avatarUri" }
        assertTrue(avatarDiff.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Multiple field changes
    // -------------------------------------------------------------------------

    @Test
    fun `diff detects multiple changed fields`() {
        val event = ContactDiffEngine.diff(
            contact(name = "Alice", phone = "111", email = "a@old.com"),
            contact(name = "Alice Smith", phone = "222", email = "a@old.com")
        )
        assertEquals(2, event.diffs.size)
        assertTrue(event.diffs.any { it.field == "displayName" })
        assertTrue(event.diffs.any { it.field == "phone" })
        assertFalse(event.diffs.any { it.field == "email" })
    }

    // -------------------------------------------------------------------------
    // applySelections
    // -------------------------------------------------------------------------

    @Test
    fun `applySelections replaces selected fields`() {
        val base = contact(name = "Alice Smith", phone = "222")
        val selections = mapOf("displayName" to "Alice", "phone" to "111")
        val result = ContactDiffEngine.applySelections(base, selections)
        assertEquals("Alice", result.displayName)
        assertEquals("111", result.phone)
        // Non-selected fields unchanged
        assertEquals(base.email, result.email)
    }

    @Test
    fun `applySelections with empty map returns unchanged contact`() {
        val base = contact()
        val result = ContactDiffEngine.applySelections(base, emptyMap())
        assertEquals(base.displayName, result.displayName)
        assertEquals(base.phone, result.phone)
    }

    @Test
    fun `applySelections preserves id and receivedAt`() {
        val base = contact().copy(id = "stable-id", receivedAt = 9999L)
        val result = ContactDiffEngine.applySelections(base, mapOf("displayName" to "New Name"))
        assertEquals("stable-id", result.id)
        assertEquals(9999L, result.receivedAt)
    }

    // -------------------------------------------------------------------------
    // MergeEvent properties
    // -------------------------------------------------------------------------

    @Test
    fun `MergeEvent hasChanges false when diffs empty`() {
        val event = ContactDiffEngine.diff(contact(), contact())
        assertFalse(event.hasChanges)
    }

    @Test
    fun `MergeEvent hasChanges true when diffs non-empty`() {
        val event = ContactDiffEngine.diff(contact(name = "Alice"), contact(name = "Bob"))
        assertTrue(event.hasChanges)
    }

    @Test
    fun `MergeEvent preserved is the incoming contact`() {
        val incoming = contact(name = "Alice Smith")
        val event = ContactDiffEngine.diff(contact(name = "Alice"), incoming)
        assertEquals("Alice Smith", event.preserved.displayName)
    }

    @Test
    fun `MergeEvent previous is the original contact`() {
        val previous = contact(name = "Alice")
        val event = ContactDiffEngine.diff(previous, contact(name = "Alice Smith"))
        assertEquals("Alice", event.previous.displayName)
    }

    // -------------------------------------------------------------------------
    // Label coverage
    // -------------------------------------------------------------------------

    @Test
    fun `all diff fields have non-blank labels`() {
        val prev = contact(name = "A", phone = "1", email = "a@b.c", company = "X",
                          title = "T", website = "w.com", bio = "B", avatarUri = "")
        val incoming = contact(name = "B", phone = "2", email = "b@c.d", company = "Y",
                               title = "U", website = "x.com", bio = "C", avatarUri = "uri")
        val event = ContactDiffEngine.diff(prev, incoming)
        event.diffs.forEach { diff ->
            assertTrue("Label blank for field ${diff.field}", diff.label.isNotBlank())
        }
    }
}
