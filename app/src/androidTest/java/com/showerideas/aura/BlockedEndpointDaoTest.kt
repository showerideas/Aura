package com.showerideas.aura

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.BlockedEndpointDao
import com.showerideas.aura.model.BlockedEndpoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * in-memory tests for the blocklist DAO. Confirms the
 * contract NearbyExchangeService relies on:
 *  - isBlocked toggles correctly through block / unblock
 *  - isBlockedByKeyHash rejects reconnects via stable identity key hash
 *  - observeAll surfaces every row in reverse-chrono order
 */
@RunWith(AndroidJUnit4::class)
class BlockedEndpointDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BlockedEndpointDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.blockedEndpointDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun block_then_isBlocked_returns_true() = runBlocking {
        dao.block(BlockedEndpoint(endpointId = "endpoint-a", note = "scammer"))
        assertTrue(dao.isBlocked("endpoint-a"))
    }

    @Test
    fun unblock_then_isBlocked_returns_false() = runBlocking {
        val entity = BlockedEndpoint(endpointId = "endpoint-b")
        dao.block(entity)
        assertTrue(dao.isBlocked("endpoint-b"))
        dao.unblock(entity)
        assertFalse(dao.isBlocked("endpoint-b"))
    }

    @Test
    fun isBlocked_returns_false_for_unknown_endpoint() = runBlocking {
        assertFalse(dao.isBlocked("never-seen"))
    }

    @Test
    fun observeAll_returns_all_blocked_in_reverse_chrono_order() = runBlocking {
        // Insert two with explicit timestamps so the ORDER BY blockedAt DESC
        // ordering is deterministic.
        dao.block(BlockedEndpoint(endpointId = "older", blockedAt = 100L))
        dao.block(BlockedEndpoint(endpointId = "newer", blockedAt = 200L))
        val all = dao.observeAll().first()
        assertEquals(2, all.size)
        assertEquals("newer", all.first().endpointId)
        assertEquals("older", all.last().endpointId)
    }

    @Test
    fun blocking_same_endpoint_twice_replaces_existing_row() = runBlocking {
        dao.block(BlockedEndpoint(endpointId = "dup", note = "first"))
        dao.block(BlockedEndpoint(endpointId = "dup", note = "second"))
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("second", all.first().note)
    }

    // isBlockedByKeyHash — stable identity-based blocklist

    @Test
    fun isBlockedByKeyHash_returns_true_when_hash_matches() = runBlocking {
        dao.block(BlockedEndpoint(endpointId = "ep-hash-1", identityKeyHash = "hash-abc"))
        assertTrue(dao.isBlockedByKeyHash("hash-abc"))
    }

    @Test
    fun isBlockedByKeyHash_returns_false_for_unknown_hash() = runBlocking {
        dao.block(BlockedEndpoint(endpointId = "ep-hash-2", identityKeyHash = "hash-known"))
        assertFalse(dao.isBlockedByKeyHash("hash-unknown"))
    }

    @Test
    fun isBlockedByKeyHash_returns_false_when_table_is_empty() = runBlocking {
        assertFalse(dao.isBlockedByKeyHash("any-hash"))
    }

    @Test
    fun isBlockedByKeyHash_ignores_null_hash_rows() = runBlocking {
        // Rows without an identity hash (legacy endpoint-only blocks) must not
        // match any key-hash query — NULL != :hash in SQL.
        dao.block(BlockedEndpoint(endpointId = "ep-no-hash", identityKeyHash = null))
        assertFalse(dao.isBlockedByKeyHash("any-hash"))
    }

    @Test
    fun key_hash_block_survives_unblock_of_unrelated_endpoint() = runBlocking {
        val original = BlockedEndpoint(endpointId = "ep-original", identityKeyHash = "hash-stable")
        val other    = BlockedEndpoint(endpointId = "ep-other")
        dao.block(original)
        dao.block(other)
        dao.unblock(other)

        assertTrue(
            "Key-hash block must persist after unblocking an unrelated endpoint",
            dao.isBlockedByKeyHash("hash-stable")
        )
    }
}
