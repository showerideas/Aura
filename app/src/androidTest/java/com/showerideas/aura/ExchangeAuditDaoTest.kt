package com.showerideas.aura

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.ExchangeAuditDao
import com.showerideas.aura.model.ExchangeAuditEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * In-memory Room tests for [ExchangeAuditDao].
 *
 * Covers:
 *  - basic insert + observeAll round-trip
 *  - observeRecent(limit) caps the result set
 *  - observeForPeer filters correctly on peerIdentityKeyHash
 *  - countFailuresForPeer counts FAILED/SPOOF within the time window
 *  - deleteOlderThan prunes old entries without touching newer ones
 *  - deleteAll clears the table
 */
@RunWith(AndroidJUnit4::class)
class ExchangeAuditDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ExchangeAuditDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.exchangeAuditDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun entry(
        outcome: String = ExchangeAuditEntry.OUTCOME_SUCCESS,
        peerHash: String? = null,
        channel: String = "NEARBY",
        timestampMs: Long = System.currentTimeMillis()
    ) = ExchangeAuditEntry(
        id = UUID.randomUUID().toString(),
        outcome = outcome,
        peerIdentityKeyHash = peerHash,
        channel = channel,
        timestampMs = timestampMs
    )

    // -----------------------------------------------------------------------
    // insert + observeAll
    // -----------------------------------------------------------------------

    @Test
    fun insert_then_observeAll_returns_entry() = runBlocking {
        val e = entry(outcome = ExchangeAuditEntry.OUTCOME_SUCCESS)
        dao.insert(e)

        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(e.id, all.first().id)
        assertEquals(ExchangeAuditEntry.OUTCOME_SUCCESS, all.first().outcome)
    }

    @Test
    fun observeAll_orders_newest_first() = runBlocking {
        dao.insert(entry(timestampMs = 1_000L))
        dao.insert(entry(timestampMs = 3_000L))
        dao.insert(entry(timestampMs = 2_000L))

        val all = dao.observeAll().first()
        assertEquals(3, all.size)
        assertTrue(
            "Entries must be ordered newest-first",
            all[0].timestampMs >= all[1].timestampMs &&
                all[1].timestampMs >= all[2].timestampMs
        )
    }

    // -----------------------------------------------------------------------
    // observeRecent
    // -----------------------------------------------------------------------

    @Test
    fun observeRecent_caps_at_given_limit() = runBlocking {
        repeat(5) { dao.insert(entry()) }
        val recent = dao.observeRecent(limit = 3).first()
        assertEquals(3, recent.size)
    }

    @Test
    fun observeRecent_returns_fewer_than_limit_when_table_has_less() = runBlocking {
        dao.insert(entry())
        dao.insert(entry())
        val recent = dao.observeRecent(limit = 10).first()
        assertEquals(2, recent.size)
    }

    // -----------------------------------------------------------------------
    // observeForPeer
    // -----------------------------------------------------------------------

    @Test
    fun observeForPeer_filters_by_peerIdentityKeyHash() = runBlocking {
        dao.insert(entry(peerHash = "hash-alice"))
        dao.insert(entry(peerHash = "hash-alice"))
        dao.insert(entry(peerHash = "hash-bob"))

        val aliceEntries = dao.observeForPeer("hash-alice").first()
        assertEquals(2, aliceEntries.size)
        assertTrue(aliceEntries.all { it.peerIdentityKeyHash == "hash-alice" })

        val bobEntries = dao.observeForPeer("hash-bob").first()
        assertEquals(1, bobEntries.size)
    }

    @Test
    fun observeForPeer_returns_empty_for_unknown_hash() = runBlocking {
        dao.insert(entry(peerHash = "hash-alice"))
        assertEquals(0, dao.observeForPeer("hash-unknown").first().size)
    }

    // -----------------------------------------------------------------------
    // count
    // -----------------------------------------------------------------------

    @Test
    fun count_reflects_number_of_inserted_entries() = runBlocking {
        assertEquals(0, dao.count())
        dao.insert(entry())
        dao.insert(entry())
        assertEquals(2, dao.count())
    }

    // -----------------------------------------------------------------------
    // countFailuresForPeer
    // -----------------------------------------------------------------------

    @Test
    fun countFailuresForPeer_counts_FAILED_and_SPOOF_within_window() = runBlocking {
        val hash = "hash-suspect"
        val now  = 1_000_000L
        // 2 failures within window
        dao.insert(entry(outcome = ExchangeAuditEntry.OUTCOME_FAILED, peerHash = hash, timestampMs = now - 1_000L))
        dao.insert(entry(outcome = ExchangeAuditEntry.OUTCOME_SPOOF,  peerHash = hash, timestampMs = now - 2_000L))
        // 1 failure outside window
        dao.insert(entry(outcome = ExchangeAuditEntry.OUTCOME_FAILED, peerHash = hash, timestampMs = now - 100_000L))
        // 1 success inside window — must NOT be counted
        dao.insert(entry(outcome = ExchangeAuditEntry.OUTCOME_SUCCESS, peerHash = hash, timestampMs = now - 500L))

        val count = dao.countFailuresForPeer(hash, since = now - 10_000L)
        assertEquals(2, count)
    }

    @Test
    fun countFailuresForPeer_returns_zero_for_unknown_peer() = runBlocking {
        dao.insert(entry(outcome = ExchangeAuditEntry.OUTCOME_FAILED, peerHash = "hash-other"))
        assertEquals(0, dao.countFailuresForPeer("hash-unknown", since = 0L))
    }

    // -----------------------------------------------------------------------
    // deleteOlderThan
    // -----------------------------------------------------------------------

    @Test
    fun deleteOlderThan_removes_old_entries_preserves_newer() = runBlocking {
        val cutoff = 5_000L
        dao.insert(entry(timestampMs = 1_000L)) // old — must be deleted
        dao.insert(entry(timestampMs = 3_000L)) // old — must be deleted
        dao.insert(entry(timestampMs = 6_000L)) // new — must survive
        dao.insert(entry(timestampMs = 9_000L)) // new — must survive

        dao.deleteOlderThan(cutoff)

        val remaining = dao.observeAll().first()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.timestampMs >= cutoff })
    }

    // -----------------------------------------------------------------------
    // deleteAll
    // -----------------------------------------------------------------------

    @Test
    fun deleteAll_clears_table() = runBlocking {
        repeat(3) { dao.insert(entry()) }
        assertEquals(3, dao.count())

        dao.deleteAll()

        assertEquals(0, dao.count())
        assertEquals(0, dao.observeAll().first().size)
    }
}
