package com.showerideas.aura

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.model.KnownPeer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DAO tests for the persisted TOFU endpoint-identity registry.
 *
 * Uses an in-memory Room database so each test starts with a clean slate
 * without touching disk or requiring migrations.
 */
@RunWith(AndroidJUnit4::class)
class KnownPeerDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun upsert_then_get_returns_matching_key() = runBlocking {
        val peer = KnownPeer(
            endpointId = "ep-abc",
            identityPublicKeyBase64 = "dGVzdGtleQ==", // base64("testkey")
            firstSeenAt = 1_000L,
            lastSeenAt = 1_000L
        )

        db.knownPeerDao().upsert(peer)

        val result = db.knownPeerDao().get("ep-abc")
        assertNotNull(result)
        assertEquals("ep-abc", result!!.endpointId)
        assertEquals("dGVzdGtleQ==", result.identityPublicKeyBase64)
        assertEquals(1_000L, result.firstSeenAt)
        assertEquals(1_000L, result.lastSeenAt)
    }

    @Test
    fun second_upsert_updates_lastSeenAt() = runBlocking {
        val first = KnownPeer(
            endpointId = "ep-xyz",
            identityPublicKeyBase64 = "a2V5QQ==",
            firstSeenAt = 1_000L,
            lastSeenAt = 1_000L
        )
        db.knownPeerDao().upsert(first)

        // Simulate a later visit: same key, updated lastSeenAt.
        val second = first.copy(lastSeenAt = 9_000L)
        db.knownPeerDao().upsert(second)

        val result = db.knownPeerDao().get("ep-xyz")
        assertNotNull(result)
        // firstSeenAt is preserved by KnownPeerRepository; at DAO level REPLACE
        // overwrites the whole row, so we verify lastSeenAt changed.
        assertEquals(9_000L, result!!.lastSeenAt)
        assertEquals("a2V5QQ==", result.identityPublicKeyBase64)
    }

    @Test
    fun delete_then_get_returns_null() = runBlocking {
        val peer = KnownPeer(
            endpointId = "ep-del",
            identityPublicKeyBase64 = "a2V5Qg==",
            firstSeenAt = 2_000L,
            lastSeenAt = 2_000L
        )
        db.knownPeerDao().upsert(peer)
        assertNotNull(db.knownPeerDao().get("ep-del"))

        db.knownPeerDao().delete("ep-del")

        assertNull(db.knownPeerDao().get("ep-del"))
    }
}
