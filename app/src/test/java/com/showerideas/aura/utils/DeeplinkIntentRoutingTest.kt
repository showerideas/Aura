package com.showerideas.aura.utils

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Deeplink web-landing round-trip integration tests.
 *
 * Verifies the full intent-routing contract for AURA share links:
 *   https://aura.app/c/<base64url-payload>
 *
 * Tested invariants:
 * 1. URI scheme and host match the intent-filter declaration in AndroidManifest.xml.
 * 2. [DeeplinkUtils.generateShareUrl] produces a URI accepted by the intent-filter.
 * 3. [DeeplinkUtils.decodeShareUrl] correctly reconstructs the profile from the URI.
 * 4. The path prefix /c/ is preserved through the encode → decode cycle.
 * 5. Non-AURA URIs are not parsed as deeplinks.
 * 6. Edge cases: empty profile, special chars, unicode names.
 */
@RunWith(RobolectricTestRunner::class)
class DeeplinkIntentRoutingTest {

    companion object {
        const val AURA_HOST   = "aura.app"
        const val AURA_SCHEME = "https"
        const val AURA_PATH_PREFIX = "/c/"
    }

    // Intent-filter contract

    @Test
    fun `generated URL matches intent-filter scheme and host`() {
        val profile = com.showerideas.aura.model.Profile(
            id = "if-test", displayName = "Intent Filter Test", shareFields = "displayName"
        )
        val url = DeeplinkUtils.generateShareUrl(profile)
        val uri = Uri.parse(url)

        assertEquals("Scheme must be https", AURA_SCHEME, uri.scheme)
        assertEquals("Host must be aura.app", AURA_HOST, uri.host)
        assertTrue("Path must start with /c/", uri.path?.startsWith(AURA_PATH_PREFIX) == true)
    }

    @Test
    fun `VIEW intent constructed from generated URL has correct data URI`() {
        val profile = com.showerideas.aura.model.Profile(
            id = "intent-test", displayName = "Alice", shareFields = "displayName"
        )
        val url = DeeplinkUtils.generateShareUrl(profile)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        assertNotNull(intent.data)
        assertEquals(AURA_SCHEME, intent.data!!.scheme)
        assertEquals(AURA_HOST,   intent.data!!.host)
    }

    // Round-trip through URI

    @Test
    fun `round-trip encode decode preserves all standard fields`() {
        val profile = com.showerideas.aura.model.Profile(
            id          = "rt",
            displayName = "Round Trip",
            email       = "rt@example.com",
            phone       = "555-9999",
            company     = "AURA Inc",
            title       = "SDK Lead",
            website     = "https://aura.app",
            bio         = "Testing deeplink round-trip.",
            shareFields = "displayName,email,phone,company,title,website,bio"
        )
        val url     = DeeplinkUtils.generateShareUrl(profile)
        val decoded = DeeplinkUtils.decodeShareUrl(url)

        assertNotNull("Decoded payload must not be null", decoded)
        assertEquals("Round Trip",              decoded!!["displayName"])
        assertEquals("rt@example.com",          decoded["email"])
        assertEquals("555-9999",                decoded["phone"])
        assertEquals("AURA Inc",                decoded["company"])
        assertEquals("SDK Lead",                decoded["title"])
        assertEquals("https://aura.app",        decoded["website"])
        assertEquals("Testing deeplink round-trip.", decoded["bio"])
    }

    @Test
    fun `path prefix slash c slash is preserved in URL`() {
        val profile = com.showerideas.aura.model.Profile(
            id = "path", displayName = "Path Test", shareFields = "displayName"
        )
        val url = DeeplinkUtils.generateShareUrl(profile)
        assertTrue("URL must contain /c/ path", url.contains("aura.app/c/"))
    }

    @Test
    fun `payload segment has no URL-unsafe characters`() {
        val profile = com.showerideas.aura.model.Profile(
            id          = "url-safe",
            displayName = "Søren Ångström & <br> test",
            email       = "test+label@example.com",
            shareFields = "displayName,email"
        )
        val url     = DeeplinkUtils.generateShareUrl(profile)
        val segment = url.removePrefix("https://aura.app/c/")

        assertFalse("No '+' in Base64url payload", segment.contains('+'))
        assertFalse("No '/' in Base64url payload", segment.contains('/'))
        assertFalse("No '=' padding in Base64url payload", segment.contains('='))
        assertFalse("No space in payload", segment.contains(' '))
    }

    // Non-AURA URI rejection

    @Test
    fun `non-AURA URL returns null`() {
        assertNull(DeeplinkUtils.decodeShareUrl("https://evil.com/c/payload"))
        assertNull(DeeplinkUtils.decodeShareUrl("http://aura.app/c/payload"))   // http not https
        assertNull(DeeplinkUtils.decodeShareUrl("https://aura.app/other/abc"))  // wrong path
        assertNull(DeeplinkUtils.decodeShareUrl(""))
    }

    @Test
    fun `HTTP URL with correct host is rejected`() {
        // The intent-filter declares scheme=https only; http must not match
        val url = "http://aura.app/c/dGVzdA"
        assertNull("http must not be decoded as AURA deeplink", DeeplinkUtils.decodeShareUrl(url))
    }

    // Edge cases

    @Test
    fun `unicode display name survives round-trip`() {
        val profile = com.showerideas.aura.model.Profile(
            id = "unicode", displayName = "田中 太郎 🎌", shareFields = "displayName"
        )
        val decoded = DeeplinkUtils.decodeShareUrl(DeeplinkUtils.generateShareUrl(profile))
        assertNotNull(decoded)
        assertEquals("田中 太郎 🎌", decoded!!["displayName"])
    }

    @Test
    fun `profile with no shared fields produces decodable URL`() {
        val profile = com.showerideas.aura.model.Profile(
            id = "empty", displayName = "NoShare", shareFields = ""
        )
        val url     = DeeplinkUtils.generateShareUrl(profile)
        val decoded = DeeplinkUtils.decodeShareUrl(url)
        assertNotNull("Empty shareFields must still produce a valid URL", decoded)
        // version is always included
        assertNotNull(decoded!!["version"])
        assertNull(decoded["displayName"])
    }

    // Helper

    private fun assertFalse(msg: String, cond: Boolean) = assertTrue(msg, !cond)
}
