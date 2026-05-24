package com.showerideas.aura

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.ContactDao
import com.showerideas.aura.model.Contact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * PR-12: in-memory Room tests for the contacts DAO. Covers the read paths
 * the new favourites filter and notes editor depend on:
 *  - insert + observeAll emits the inserted record
 *  - search filters on all 8 text columns (name, email, phone, company,
 *    title, notes, bio, website)
 *  - delete removes the record
 *  - observeFavorites only returns isFavorite = true rows
 */
@RunWith(AndroidJUnit4::class)
class ContactDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.contactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleContact(
        name: String = "Ada Lovelace",
        email: String = "ada@analytical.engine",
        phone: String = "+44 20 7946 0958",
        isFavorite: Boolean = false
    ) = Contact(
        id = UUID.randomUUID().toString(),
        displayName = name,
        email = email,
        phone = phone,
        isFavorite = isFavorite
    )

    @Test
    fun insert_then_observeAll_emits_contact() = runBlocking {
        val c = sampleContact()
        dao.insert(c)
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(c.id, all.first().id)
    }

    @Test
    fun search_matches_name_email_and_phone() = runBlocking {
        dao.insert(sampleContact(name = "Ada Lovelace", email = "ada@x.com", phone = "111"))
        dao.insert(sampleContact(name = "Bob Stone",  email = "bob@y.com", phone = "222"))
        dao.insert(sampleContact(name = "Carol Vance", email = "carol@z.com", phone = "333"))

        val byName = dao.search("Lovelace").first()
        assertEquals(1, byName.size)
        assertEquals("Ada Lovelace", byName.first().displayName)

        val byEmail = dao.search("y.com").first()
        assertEquals(1, byEmail.size)
        assertEquals("Bob Stone", byEmail.first().displayName)

        val byPhone = dao.search("333").first()
        assertEquals(1, byPhone.size)
        assertEquals("Carol Vance", byPhone.first().displayName)
    }

    /**
     * Verify the search LIKE expression covers all 8 text columns.
     * company, title, notes, bio, and website were added when the Contact
     * model was extended; if any is missing from the DAO query this catches it.
     */
    @Test
    fun search_matches_company_title_notes_bio_and_website() = runBlocking {
        val contact = Contact(
            id          = UUID.randomUUID().toString(),
            displayName = "ZZZ Unique Placeholder",
            company     = "Acme Corp",
            title       = "Lead Engineer",
            notes       = "Met at summit",
            bio         = "Loves Kotlin",
            website     = "https://acme.example.com"
        )
        dao.insert(contact)

        assertEquals(1, dao.search("Acme Corp").first().size)
        assertEquals(1, dao.search("Lead Engineer").first().size)
        assertEquals(1, dao.search("summit").first().size)
        assertEquals(1, dao.search("Kotlin").first().size)
        assertEquals(1, dao.search("acme.example").first().size)
        // Sanity: non-matching term must return nothing
        assertEquals(0, dao.search("zzznomatch123").first().size)
    }

    @Test
    fun delete_removes_record() = runBlocking {
        val c = sampleContact()
        dao.insert(c)
        assertEquals(1, dao.count())
        dao.delete(c)
        assertEquals(0, dao.count())
        assertNull(dao.getById(c.id))
    }

    @Test
    fun observeFavorites_only_returns_favorite_rows() = runBlocking {
        dao.insert(sampleContact(name = "Fave A", isFavorite = true))
        dao.insert(sampleContact(name = "Fave B", isFavorite = true))
        dao.insert(sampleContact(name = "Plain",  isFavorite = false))

        val favs = dao.observeFavorites().first()
        assertEquals(2, favs.size)
        assertTrue(favs.all { it.isFavorite })
    }
}
