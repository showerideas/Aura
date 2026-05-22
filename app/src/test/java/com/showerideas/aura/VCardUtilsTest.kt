package com.showerideas.aura

import com.showerideas.aura.model.Contact
import com.showerideas.aura.utils.toVCard
import com.showerideas.aura.utils.toVCardBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VCardUtilsTest {

    @Test
    fun `vCard contains all populated fields`() {
        val c = Contact(
            id = "1",
            displayName = "Ada Lovelace",
            phone = "+44 1234 567890",
            email = "ada@example.com",
            company = "Analytical Engines Ltd",
            title = "Mathematician",
            website = "https://example.com",
            bio = "First programmer",
            notes = "Met at Bletchley"
        )
        val vcard = c.toVCard()

        assertTrue(vcard.startsWith("BEGIN:VCARD\r\n"))
        assertTrue(vcard.endsWith("END:VCARD\r\n"))
        assertTrue(vcard.contains("VERSION:3.0"))
        assertTrue(vcard.contains("FN:Ada Lovelace"))
        assertTrue(vcard.contains("N:Ada Lovelace;;;;"))
        assertTrue(vcard.contains("TEL;TYPE=CELL:+44 1234 567890"))
        assertTrue(vcard.contains("EMAIL;TYPE=INTERNET:ada@example.com"))
        assertTrue(vcard.contains("ORG:Analytical Engines Ltd"))
        assertTrue(vcard.contains("TITLE:Mathematician"))
        assertTrue(vcard.contains("URL:https://example.com"))
        // NOTE field should concatenate bio + notes
        assertTrue(vcard.contains("First programmer"))
        assertTrue(vcard.contains("Met at Bletchley"))
    }

    @Test
    fun `blank fields are omitted entirely`() {
        val c = Contact(id = "2", displayName = "Solo", phone = "555-1212")
        val vcard = c.toVCard()

        assertTrue(vcard.contains("FN:Solo"))
        assertTrue(vcard.contains("TEL;TYPE=CELL:555-1212"))
        // No empty EMAIL, ORG, TITLE, URL, NOTE lines.
        assertFalse(vcard.contains("EMAIL"))
        assertFalse(vcard.contains("ORG:"))
        assertFalse(vcard.contains("TITLE:"))
        assertFalse(vcard.contains("URL:"))
        assertFalse(vcard.contains("NOTE:"))
    }

    @Test
    fun `bundle concatenates each contact's vCard`() {
        val a = Contact(id = "a", displayName = "Alice")
        val b = Contact(id = "b", displayName = "Bob")
        val bundle = listOf(a, b).toVCardBundle()
        // Two complete records
        assertEquals(2, bundle.split("END:VCARD\r\n").filter { it.isNotBlank() }.size)
        assertTrue(bundle.contains("FN:Alice"))
        assertTrue(bundle.contains("FN:Bob"))
    }

    @Test
    fun `special chars in fields are escaped`() {
        val c = Contact(id = "3", displayName = "Tag, Author", bio = "Line 1\nLine 2; etc")
        val vcard = c.toVCard()
        assertTrue(vcard.contains("FN:Tag\\, Author"))
        assertTrue(vcard.contains("NOTE:Line 1\\nLine 2\\; etc"))
    }
}
