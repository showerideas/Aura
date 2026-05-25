package com.showerideas.aura.utils

import com.showerideas.aura.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-case tests for [ContactDiffEngine] — Phase 5.2 coverage hardening.
 *
 * The primary tests in [ContactDiffEngineTest] cover the happy-path diff.
 * This file covers boundary conditions, blank/null values, whitespace trimming,
 * avatar change detection, and [ContactDiffEngine.applySelections] all paths.
 */
class ContactDiffEngineEdgeCasesTest {

    private fun baseContact(
        displayName: String = "Alice",
        phone: String = "111",
        email: String = "a@b.com",
        company: String = "Acme",
        title: String = "Dev",
        website: String = "https://acme.com",
        bio: String = "Hello",
        avatarUri: String = ""
    ) = Contact(
        id = "c1",
        displayName = displayName,
        phone = phone,
        email = email,
        company = company,
        title = title,
        website = website,
        bio = bio,
        avatarUri = avatarUri,
        identityKeyHash = "abc123"
    )

    // -------------------------------------------------------------------------
    // diff — no changes
    // -------------------------------------------------------------------------

    @Test
    fun `diff identical contacts produces empty diffs`() {
        val c = baseContact()
        val event = ContactDiffEngine.diff(c, c.copy())
        assertFalse("Identical contacts must not have changes", event.hasChanges)
        assertTrue(event.diffs.isEmpty())
    }

    @Test
    fun `diff trims whitespace before comparison`() {
        val old = baseContact(displayName = "Alice")
        val new = old.copy(displayName = "  Alice  ")
        val event = ContactDiffEngine.diff(old, new)
        assertFalse("Leading/trailing whitespace should not count as a change", event.hasChanges)
    }

    // -------------------------------------------------------------------------
    // diff — single field changes
    // -------------------------------------------------------------------------

    @Test
    fun `diff detects displayName change`() {
        val event = ContactDiffEngine.diff(baseContact(displayName = "Alice"),
                                           baseContact(displayName = "Alicia"))
        assertTrue(event.hasChanges)
        val diff = event.diffs.single()
        assertEquals("displayName", diff.field)
        assertEquals("Alice", diff.oldValue)
        assertEquals("Alicia", diff.newValue)
    }

    @Test
    fun `diff detects phone change`() {
        val event = ContactDiffEngine.diff(baseContact(phone = "111"), baseContact(phone = "222"))
        assertTrue(event.hasChanges)
        assertEquals("phone", event.diffs.single().field)
    }

    @Test
    fun `diff detects email change`() {
        val event = ContactDiffEngine.diff(baseContact(email = "old@x.com"), baseContact(email = "new@x.com"))
        assertTrue(event.hasChanges)
        assertEquals("email", event.diffs.single().field)
    }

    @Test
    fun `diff detects company change`() {
        val event = ContactDiffEngine.diff(baseContact(company = "Foo"), baseContact(company = "Bar"))
        assertTrue(event.hasChanges)
        assertEquals("company", event.diffs.single().field)
    }

    @Test
    fun `diff detects title change`() {
        val event = ContactDiffEngine.diff(baseContact(title = "Dev"), baseContact(title = "Lead"))
        assertTrue(event.hasChanges)
        assertEquals("title", event.diffs.single().field)
    }

    @Test
    fun `diff detects website change`() {
        val event = ContactDiffEngine.diff(baseContact(website = "https://a.com"),
                                           baseContact(website = "https://b.com"))
        assertTrue(event.hasChanges)
        assertEquals("website", event.diffs.single().field)
    }

    @Test
    fun `diff detects bio change`() {
        val event = ContactDiffEngine.diff(baseContact(bio = "old bio"), baseContact(bio = "new bio"))
        assertTrue(event.hasChanges)
        assertEquals("bio", event.diffs.single().field)
    }

    // -------------------------------------------------------------------------
    // diff — avatar (presence-based, not URI-based)
    // -------------------------------------------------------------------------

    @Test
    fun `diff detects avatar added`() {
        val event = ContactDiffEngine.diff(
            baseContact(avatarUri = ""),
            baseContact(avatarUri = "content://media/photo.jpg")
        )
        assertTrue(event.hasChanges)
        val diff = event.diffs.single { it.field == "avatarUri" }
        assertEquals("(none)", diff.oldValue)
        assertEquals("(photo)", diff.newValue)
    }

    @Test
    fun `diff detects avatar removed`() {
        val event = ContactDiffEngine.diff(
            baseContact(avatarUri = "content://media/photo.jpg"),
            baseContact(avatarUri = "")
        )
        assertTrue(event.hasChanges)
        val diff = event.diffs.single { it.field == "avatarUri" }
        assertEquals("(photo)", diff.oldValue)
        assertEquals("(none)", diff.newValue)
    }

    @Test
    fun `diff does not flag avatar change when both have different URIs for same image`() {
        // Two different URIs both non-blank → considered the same (presence only)
        val event = ContactDiffEngine.diff(
            baseContact(avatarUri = "content://media/photo_v1.jpg"),
            baseContact(avatarUri = "content://media/photo_v2.jpg")
        )
        assertFalse("Different URIs for same 'has photo' state should not count as change",
            event.diffs.any { it.field == "avatarUri" })
    }

    // -------------------------------------------------------------------------
    // diff — multiple fields changed at once
    // -------------------------------------------------------------------------

    @Test
    fun `diff detects all changed fields in one pass`() {
        val old = baseContact()
        val new = old.copy(
            displayName = "Changed Name",
            email = "new@email.com",
            company = "New Corp"
        )
        val event = ContactDiffEngine.diff(old, new)
        assertTrue(event.hasChanges)
        assertEquals(3, event.diffs.size)
        val fields = event.diffs.map { it.field }.toSet()
        assertTrue(fields.containsAll(setOf("displayName", "email", "company")))
    }

    // -------------------------------------------------------------------------
    // applySelections
    // -------------------------------------------------------------------------

    @Test
    fun `applySelections with empty map returns base unchanged`() {
        val base = baseContact()
        val result = ContactDiffEngine.applySelections(base, emptyMap())
        assertEquals(base, result)
    }

    @Test
    fun `applySelections replaces displayName`() {
        val base = baseContact(displayName = "Old")
        val result = ContactDiffEngine.applySelections(base, mapOf("displayName" to "New"))
        assertEquals("New", result.displayName)
        // Other fields unchanged
        assertEquals(base.phone, result.phone)
        assertEquals(base.email, result.email)
    }

    @Test
    fun `applySelections replaces multiple fields`() {
        val base = baseContact(phone = "111", email = "old@e.com")
        val result = ContactDiffEngine.applySelections(base, mapOf(
            "phone" to "999",
            "email" to "new@e.com"
        ))
        assertEquals("999", result.phone)
        assertEquals("new@e.com", result.email)
        assertEquals(base.displayName, result.displayName)
    }

    @Test
    fun `applySelections preserves id receivedAt isFavorite and notes`() {
        val base = baseContact().copy(
            id = "stable-uuid",
            receivedAt = 12345L,
            isFavorite = true,
            notes = "Met at conference"
        )
        val result = ContactDiffEngine.applySelections(base, mapOf("displayName" to "New"))
        assertEquals("stable-uuid", result.id)
        assertEquals(12345L, result.receivedAt)
        assertTrue(result.isFavorite)
        assertEquals("Met at conference", result.notes)
    }

    @Test
    fun `applySelections with avatarUri selection`() {
        val base = baseContact(avatarUri = "old-uri")
        val result = ContactDiffEngine.applySelections(base, mapOf("avatarUri" to ""))
        assertEquals("", result.avatarUri)
    }
}
