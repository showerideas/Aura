package com.showerideas.aura

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.ProfileDao
import com.showerideas.aura.model.Profile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PR-21: in-memory Room tests for [ProfileDao]. The profile table is a single-row
 * store (primary key is always "local_profile"), so the round-trip and update
 * semantics matter for the Settings + onboarding flows.
 *
 *  - insert then get returns the same profile
 *  - update changes updatedAt (and persists the new field values)
 */
@RunWith(AndroidJUnit4::class)
class ProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProfileDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.profileDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insert_then_get_returns_same_profile() = runBlocking {
        val profile = Profile(
            displayName = "Ada Lovelace",
            phone = "+44 1234 567890",
            email = "ada@analytical.engine",
            company = "Analytical Engine Ltd",
            title = "Mathematician",
            website = "https://ada.example",
            bio = "First programmer."
        )
        dao.insert(profile)

        val fetched = dao.get()
        assertNotNull(fetched)
        assertEquals("local_profile", fetched!!.id)
        assertEquals("Ada Lovelace", fetched.displayName)
        assertEquals("+44 1234 567890", fetched.phone)
        assertEquals("ada@analytical.engine", fetched.email)
        assertEquals("Analytical Engine Ltd", fetched.company)
        assertEquals("Mathematician", fetched.title)
        assertEquals("https://ada.example", fetched.website)
        assertEquals("First programmer.", fetched.bio)
    }

    @Test
    fun update_changes_updatedAt_and_persists_new_values() = runBlocking {
        val original = Profile(
            displayName = "v1",
            updatedAt = 1_000L
        )
        dao.insert(original)

        val before = dao.get()
        assertNotNull(before)
        assertEquals(1_000L, before!!.updatedAt)
        assertEquals("v1", before.displayName)

        val updated = before.copy(
            displayName = "v2",
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)

        val after = dao.get()
        assertNotNull(after)
        assertEquals("v2", after!!.displayName)
        assertTrue(
            "updatedAt should have advanced past the original value",
            after.updatedAt > 1_000L
        )
    }

    @Test
    fun insert_with_replace_strategy_overwrites_existing_row() = runBlocking {
        dao.insert(Profile(displayName = "first"))
        dao.insert(Profile(displayName = "second"))

        val fetched = dao.get()
        assertNotNull(fetched)
        // Single-row table — second insert REPLACEs the first.
        assertEquals("second", fetched!!.displayName)
    }

    @Test
    fun clear_removes_profile_row() = runBlocking {
        dao.insert(Profile(displayName = "soon-to-be-gone"))
        assertNotNull(dao.get())

        dao.clear()
        assertEquals(null, dao.get())
    }
}
