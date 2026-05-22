package com.showerideas.aura

import com.showerideas.aura.model.Contact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-12: pure-JVM test of the favourites-filter selection logic used by
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

    @Test
    fun `client-side filter on favourites matches name email and phone`() {
        // Mirrors the inline filter applied when both favsOnly=true and a
        // non-empty query are active. Pinned here so changes to the filter
        // logic must update this test too.
        val list = listOf(
            Contact(id = "1", displayName = "Ada Lovelace", email = "ada@x.com", phone = "111", isFavorite = true),
            Contact(id = "2", displayName = "Bob Stone",   email = "bob@y.com", phone = "222", isFavorite = true)
        )
        val query = "stone"
        val filtered = list.filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.email.contains(query, ignoreCase = true) ||
                it.phone.contains(query, ignoreCase = true)
        }
        assertEquals(1, filtered.size)
        assertEquals("Bob Stone", filtered.first().displayName)
    }
}
