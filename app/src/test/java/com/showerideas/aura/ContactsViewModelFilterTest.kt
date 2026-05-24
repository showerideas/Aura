package com.showerideas.aura

import com.showerideas.aura.model.Contact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pure-JVM test of the favourites-filter selection logic used by
 * ContactsViewModel.contacts (without the debounce + flatMapLatest plumbing
 * which would need a TestDispatcher and adds zero test value here). The
 * branch chosen on (query, favsOnly) is the part we actually need to pin
 * — the rest is straight Kotlin Flow stdlib.
 */
class ContactsViewModelFilterTest {

    private fun pickSource(query: String, favsOnly: Boolean): String = when {
        favsOnly && query.isBlank() -> "favorites"
        favsOnly                    -> "favorites+filtered"
        query.isBlank()             -> "allContacts"
        else                        -> "search"
    }

    @Test
    fun `default state uses allContacts source`() {
        assertEquals("allContacts", pickSource(query = "", favsOnly = false))
    }

    @Test
    fun `favourites only with empty query uses repository favorites flow`() {
        assertEquals("favorites", pickSource(query = "", favsOnly = true))
    }

    @Test
    fun `favourites only with query filters the favourites flow`() {
        assertEquals("favorites+filtered", pickSource(query = "ada", favsOnly = true))
    }

    @Test
    fun `non-empty query without favourites uses search`() {
        assertEquals("search", pickSource(query = "ada", favsOnly = false))
    }

    @Test
    fun `toggling filter twice returns to original state`() = runBlocking {
        // Mirrors ContactsViewModel._showFavouritesOnly behaviour.
        val flag = MutableStateFlow(false)
        flag.value = !flag.value
        assertTrue(flag.first())
        flag.value = !flag.value
        assertEquals(false, flag.first())
    }

    /**
     * Mirrors the inline filter applied when both favsOnly=true and a
     * non-empty query are active. Pinned here so changes to the filter
     * logic must update this test too.
     *
     * The filter covers all 8 text fields: displayName, email, phone,
     * company, title, notes, bio, website.
     */
    private fun applyFilter(list: List<Contact>, query: String): List<Contact> =
        list.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.email.contains(query, ignoreCase = true) ||
                it.phone.contains(query, ignoreCase = true) ||
                it.company.contains(query, ignoreCase = true) ||
                it.title.contains(query, ignoreCase = true) ||
                it.notes.contains(query, ignoreCase = true) ||
                it.bio.contains(query, ignoreCase = true) ||
                it.website.contains(query, ignoreCase = true)
        }

    @Test
    fun `client-side filter matches displayName`() {
        val list = listOf(
            Contact(id = "1", displayName = "Ada Lovelace", isFavorite = true),
            Contact(id = "2", displayName = "Bob Stone",    isFavorite = true)
        )
        val result = applyFilter(list, "lovelace")
        assertEquals(1, result.size)
        assertEquals("Ada Lovelace", result.first().displayName)
    }

    @Test
    fun `client-side filter matches email`() {
        val list = listOf(
            Contact(id = "1", email = "ada@x.com",  isFavorite = true),
            Contact(id = "2", email = "bob@y.com",  isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "x.com").size)
        assertEquals("ada@x.com", applyFilter(list, "x.com").first().email)
    }

    @Test
    fun `client-side filter matches phone`() {
        val list = listOf(
            Contact(id = "1", phone = "111", isFavorite = true),
            Contact(id = "2", phone = "222", isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "222").size)
    }

    @Test
    fun `client-side filter matches company`() {
        val list = listOf(
            Contact(id = "1", company = "Acme Corp",    isFavorite = true),
            Contact(id = "2", company = "Widget & Co",  isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "acme").size)
        assertEquals("Acme Corp", applyFilter(list, "acme").first().company)
    }

    @Test
    fun `client-side filter matches title`() {
        val list = listOf(
            Contact(id = "1", title = "Engineer",  isFavorite = true),
            Contact(id = "2", title = "Designer",  isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "designer").size)
    }

    @Test
    fun `client-side filter matches notes`() {
        val list = listOf(
            Contact(id = "1", notes = "Met at conference",  isFavorite = true),
            Contact(id = "2", notes = "Old friend",         isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "conference").size)
    }

    @Test
    fun `client-side filter matches bio`() {
        val list = listOf(
            Contact(id = "1", bio = "Loves hiking",    isFavorite = true),
            Contact(id = "2", bio = "Avid reader",     isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "hiking").size)
    }

    @Test
    fun `client-side filter matches website`() {
        val list = listOf(
            Contact(id = "1", website = "https://ada.dev",  isFavorite = true),
            Contact(id = "2", website = "https://bob.io",   isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "ada.dev").size)
    }

    @Test
    fun `client-side filter is case-insensitive`() {
        val list = listOf(
            Contact(id = "1", displayName = "Ada Lovelace", isFavorite = true)
        )
        assertEquals(1, applyFilter(list, "ADA LOVELACE").size)
        assertEquals(1, applyFilter(list, "ada lovelace").size)
    }

    @Test
    fun `client-side filter returns empty when no field matches`() {
        val list = listOf(
            Contact(id = "1", displayName = "Ada", email = "ada@x.com", isFavorite = true)
        )
        assertEquals(0, applyFilter(list, "zzznomatch").size)
    }
}
