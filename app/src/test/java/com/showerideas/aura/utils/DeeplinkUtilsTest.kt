package com.showerideas.aura.utils

import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeeplinkUtils] share-card deeplink encoding.
 *
 * All tests run on the JVM (no Android dependencies); the Base64 + JSONObject
 * operations are standard Java/Kotlin library calls available in unit-test scope.
 */
class DeeplinkUtilsTest {

    // generateShareUrl

    @Test
    fun `generateShareUrl produces a valid https aura app c URL`() {
        val profile = Profile(
            id = "test",
            displayName = "Alice",
            email = "alice@example.com",
            shareFields = "displayName,email"
        )
        val url = DeeplinkUtils.generateShareUrl(profile)
        assertTrue("URL must start with https://aura.app/c/", url.startsWith("https://aura.app/c/"))
        assertTrue("URL must have non-empty payload segment", url.length > "https://aura.app/c/".length)
    }

    @Test
    fun `generateShareUrl encodes only enabled share fields`() {
        val profile = Profile(
            id = "test",
            displayName = "Bob",
            phone = "555-1234",
            email = "bob@example.com",
            company = "Acme",
            shareFields = "displayName,phone"  // email and company excluded
        )
        val decoded = DeeplinkUtils.decodeShareUrl(DeeplinkUtils.generateShareUrl(profile))
        assertNotNull(decoded)
        assertEquals("Bob", decoded!!["displayName"])
        assertEquals("555-1234", decoded["phone"])
        assertNull("email should not be present", decoded["email"])
        assertNull("company should not be present", decoded["company"])
    }

    @Test
    fun `generateShareUrl round-trips all enabled fields`() {
        val profile = Profile(
            id = "round-trip",
            displayName = "Carol",
            phone = "111-2222",
            email = "carol@test.com",
            company = "Beta Corp",
            title = "Engineer",
            website = "https://carol.dev",
            bio = "I build things.",
            shareFields = "displayName,phone,email,company,title,website,bio"
        )
        val url = DeeplinkUtils.generateShareUrl(profile)
        val decoded = DeeplinkUtils.decodeShareUrl(url)
        assertNotNull(decoded)
        with(decoded!!) {
            assertEquals("Carol", get("displayName"))
            assertEquals("111-2222", get("phone"))
            assertEquals("carol@test.com", get("email"))
            assertEquals("Beta Corp", get("company"))
            assertEquals("Engineer", get("title"))
            assertEquals("https://carol.dev", get("website"))
            assertEquals("I build things.", get("bio"))
        }
    }

    @Test
    fun `generateShareUrl includes version field`() {
        val profile = Profile(id = "versioned", displayName = "Dave", version = 5,
            shareFields = "displayName")
        val decoded = DeeplinkUtils.decodeShareUrl(DeeplinkUtils.generateShareUrl(profile))
        assertNotNull(decoded)
        assertEquals("5", decoded!!["version"])
    }

    @Test
    fun `generateShareUrl skips blank fields even when listed in shareFields`() {
        val profile = Profile(
            id = "blanks",
            displayName = "Eve",
            phone = "",  // blank — should be omitted
            shareFields = "displayName,phone"
        )
        val decoded = DeeplinkUtils.decodeShareUrl(DeeplinkUtils.generateShareUrl(profile))
        assertNotNull(decoded)
        assertEquals("Eve", decoded!!["displayName"])
        assertNull("blank phone should not appear in payload", decoded["phone"])
    }

    @Test
    fun `generateShareUrl with all fields disabled produces URL with only version`() {
        val profile = Profile(id = "empty", displayName = "Frank", shareFields = "")
        val url = DeeplinkUtils.generateShareUrl(profile)
        val decoded = DeeplinkUtils.decodeShareUrl(url)
        assertNotNull(decoded)
        // version is always included; profile fields should not be present
        assertNotNull("version always present", decoded!!["version"])
        assertNull(decoded["displayName"])
    }

    // decodeShareUrl

    @Test
    fun `decodeShareUrl returns null for non-aura URL`() {
        assertNull(DeeplinkUtils.decodeShareUrl("https://example.com/foo/bar"))
    }

    @Test
    fun `decodeShareUrl returns null for empty string`() {
        assertNull(DeeplinkUtils.decodeShareUrl(""))
    }

    @Test
    fun `decodeShareUrl returns null for malformed base64 payload`() {
        assertNull(DeeplinkUtils.decodeShareUrl("https://aura.app/c/!!!not-base64!!!"))
    }

    @Test
    fun `decodeShareUrl returns null for valid base64 but non-JSON payload`() {
        val notJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("this is not json".toByteArray())
        assertNull(DeeplinkUtils.decodeShareUrl("https://aura.app/c/$notJson"))
    }

    @Test
    fun `decodeShareUrl handles unicode display names`() {
        val profile = Profile(
            id = "unicode",
            displayName = "日本語テスト",
            shareFields = "displayName"
        )
        val decoded = DeeplinkUtils.decodeShareUrl(DeeplinkUtils.generateShareUrl(profile))
        assertNotNull(decoded)
        assertEquals("日本語テスト", decoded!!["displayName"])
    }

    @Test
    fun `decodeShareUrl handles display name with special characters`() {
        val profile = Profile(
            id = "special",
            displayName = "O'Brien, Dr. & Sons <test>",
            shareFields = "displayName"
        )
        val decoded = DeeplinkUtils.decodeShareUrl(DeeplinkUtils.generateShareUrl(profile))
        assertNotNull(decoded)
        assertEquals("O'Brien, Dr. & Sons <test>", decoded!!["displayName"])
    }

    // URL structure invariants

    @Test
    fun `generated URL contains no padding characters`() {
        // Base64url without padding — '+', '/', '=' must not appear in payload segment
        val profile = Profile(id = "padding-test", displayName = "Padding Test",
            shareFields = "displayName")
        val url = DeeplinkUtils.generateShareUrl(profile)
        val payload = url.removePrefix("https://aura.app/c/")
        assertFalse("URL payload must not contain '+' (use Base64url)", payload.contains('+'))
        assertFalse("URL payload must not contain '/'", payload.contains('/'))
        assertFalse("URL payload must not contain '='", payload.contains('='))
    }

    private fun assertFalse(message: String, condition: Boolean) {
        assertTrue(message, !condition)
    }
}
