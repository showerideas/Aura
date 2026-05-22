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
 * PR-14: in-memory tests for the blocklist DAO. Confirms the contract
 * NearbyExchangeService relies on: isBlocked toggles correctly through
 * block / unblock, and observeAll surfaces every row.
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
}
