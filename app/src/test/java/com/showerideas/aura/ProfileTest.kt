package com.showerideas.aura

import com.showerideas.aura.model.Profile
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for Profile share-field filtering logic.
 * This is the last line of defence before data goes on the wire.
 */
class ProfileTest {

    private val fullProfile = Profile(
        displayName = "Alice Dev",
        phone = "+15550001234",
        email = "alice@example.com",
        company = "ShowerIdeas",
        title = "Engineer",
        website = "https://alice.dev",
        bio = "Builds things",
        shareFields = "displayName,phone,email,company,title,website,bio"
    )

    @Test
    fun `shareableMap includes all fields when all enabled`() {
        val map = fullProfile.toShareableMap()
        assertEquals("Alice Dev", map["displayName"])
        assertEquals("+15550001234", map["phone"])
        assertEquals("alice@example.com", map["email"])
        assertEquals("ShowerIdeas", map["company"])
        assertEquals("Engineer", map["title"])
        assertEquals("https://alice.dev", map["website"])
        assertEquals("Builds things", map["bio"])
    }

    @Test
    fun `shareableMap only includes enabled fields`() {
        val profile = fullProfile.copy(shareFields = "displayName,email")
        val map = profile.toShareableMap()
        assertEquals("Alice Dev", map["displayName"])
        assertEquals("alice@example.com", map["email"])
        assertNull("phone should not be shared", map["phone"])
        assertNull("company should not be shared", map["company"])
        assertNull("bio should not be shared", map["bio"])
    }

    @Test
    fun `shareableMap excludes blank fields even if enabled`() {
        val profile = fullProfile.copy(phone = "", shareFields = "displayName,phone,email")
        val map = profile.toShareableMap()
        assertTrue(map.containsKey("displayName"))
        assertTrue(map.containsKey("email"))
        assertFalse("blank phone should be omitted", map.containsKey("phone"))
    }

    @Test
    fun `shareableMap with no fields enabled returns only non-blank shareFields`() {
        val profile = fullProfile.copy(shareFields = "")
        val map = profile.toShareableMap()
        // No user fields enabled → only the always-present version sentinel is sent
        assertEquals(1, map.size)
        assertTrue("version should always be present", map.containsKey("version"))
    }

    @Test
    fun `displayName is always included when non-blank and enabled`() {
        val profile = fullProfile.copy(shareFields = "displayName")
        val map = profile.toShareableMap()
        // displayName + always-present version
        assertEquals(2, map.size)
        assertEquals("Alice Dev", map["displayName"])
        assertTrue("version should always be present", map.containsKey("version"))
    }
}
