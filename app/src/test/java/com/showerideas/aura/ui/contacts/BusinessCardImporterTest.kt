package com.showerideas.aura.ui.contacts

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [BusinessCardImporter.parseContactFields].
 *
 * Tests the regex + heuristic extraction logic directly without OCR.
 * Covers email, phone, URL extraction and name/company/title heuristics.
 */
class BusinessCardImporterTest {

    private lateinit var importer: BusinessCardImporter

    @Before
    fun setUp() {
        importer = BusinessCardImporter(mockk<Context>(relaxed = true))
    }

    // ── Email extraction ──────────────────────────────────────────────────────

    @Test
    fun `extracts standard email address`() {
        val result = importer.parseContactFields("John Smith\njohn.smith@example.com")
        assertEquals("john.smith@example.com", result.email)
    }

    @Test
    fun `extracts email with subdomain`() {
        val result = importer.parseContactFields("Alice Jones\nalice@corp.example.co.uk")
        assertEquals("alice@corp.example.co.uk", result.email)
    }

    @Test
    fun `no email returns null`() {
        val result = importer.parseContactFields("John Smith\n+1 555 123 4567")
        assertNull(result.email)
    }

    // ── Phone extraction ──────────────────────────────────────────────────────

    @Test
    fun `extracts US phone with dashes`() {
        val result = importer.parseContactFields("Jane Doe\n555-867-5309")
        assertNotNull(result.phone)
        assertTrue(result.phone!!.contains("5309"))
    }

    @Test
    fun `extracts phone with parentheses`() {
        val result = importer.parseContactFields("Bob Builder\n(415) 555-0100")
        assertNotNull(result.phone)
        assertTrue(result.phone!!.contains("0100"))
    }

    @Test
    fun `extracts international phone with plus`() {
        val result = importer.parseContactFields("Maria Garcia\n+1 650 555 7777")
        assertNotNull(result.phone)
        assertTrue(result.phone!!.contains("7777"))
    }

    // ── Website extraction ────────────────────────────────────────────────────

    @Test
    fun `extracts https URL`() {
        val result = importer.parseContactFields("ACME Corp\nhttps://acmecorp.com")
        assertEquals("https://acmecorp.com", result.website)
    }

    @Test
    fun `extracts www URL`() {
        val result = importer.parseContactFields("ACME Corp\nwww.acmecorp.io")
        assertEquals("www.acmecorp.io", result.website)
    }

    // ── Name heuristic ────────────────────────────────────────────────────────

    @Test
    fun `extracts name from first title-case line`() {
        val result = importer.parseContactFields("Sarah Connor\nCyberDyne Systems\nDirector of Operations\nsconnor@cyberdyne.com")
        assertEquals("Sarah Connor", result.displayName)
    }

    @Test
    fun `name is null for single-digit line`() {
        val result = importer.parseContactFields("12345\ntest@example.com")
        // "12345" fails the name regex (contains digits) so name should be null
        assertNull(result.displayName)
    }

    // ── Title heuristic ───────────────────────────────────────────────────────

    @Test
    fun `extracts CEO title`() {
        val result = importer.parseContactFields("Tim Cook\nCEO\nApple Inc\ntim@apple.com")
        assertNotNull(result.title)
        assertTrue(result.title!!.contains("CEO"))
    }

    @Test
    fun `extracts senior engineer title`() {
        val result = importer.parseContactFields("Wei Zhang\nSenior Engineer\nMegaCorp\nwei@megacorp.com")
        assertNotNull(result.title)
        assertTrue(result.title!!.lowercase().contains("senior"))
    }

    // ── Full card parse ───────────────────────────────────────────────────────

    @Test
    fun `full card parse extracts all fields`() {
        val card = """
            Emma Wilson
            VP of Product
            Acme Corp
            emma.wilson@acme.com
            +1 415 555 9999
            https://acme.com
        """.trimIndent()
        val result = importer.parseContactFields(card)
        assertNotNull(result.email)
        assertNotNull(result.phone)
        assertNotNull(result.website)
        assertTrue(result.hasAnyField)
    }

    @Test
    fun `empty text produces empty result with hasAnyField false`() {
        val result = importer.parseContactFields("   ")
        assertNull(result.email)
        assertNull(result.phone)
        assertNull(result.displayName)
        assertFalse(result.hasAnyField)
    }

    @Test
    fun `rawOcrText is preserved`() {
        val raw = "Raw OCR output\nwith multiple lines"
        val result = importer.parseContactFields(raw)
        assertEquals(raw, result.rawOcrText)
    }
}
