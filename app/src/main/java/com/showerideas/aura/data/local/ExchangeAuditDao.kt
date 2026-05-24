package com.showerideas.aura.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.showerideas.aura.model.ExchangeAuditEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface ExchangeAuditDao {

    /**
     * Insert a new audit log entry.  Conflict strategy REPLACE handles the
     * (practically impossible) case where two entries are generated with the
     * same UUID within the same millisecond.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ExchangeAuditEntry)

    /** All entries ordered newest-first — drives the audit log UI screen. */
    @Query("SELECT * FROM exchange_audit_log ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<ExchangeAuditEntry>>

    /** Most recent [limit] entries — useful for "recent activity" home screen widget. */
    @Query("SELECT * FROM exchange_audit_log ORDER BY timestampMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ExchangeAuditEntry>>

    /** All entries for a specific peer identity key hash — enables per-peer history. */
    @Query("SELECT * FROM exchange_audit_log WHERE peerIdentityKeyHash = :hash ORDER BY timestampMs DESC")
    fun observeForPeer(hash: String): Flow<List<ExchangeAuditEntry>>

    /** Count of all audit entries (for badge/indicator). */
    @Query("SELECT COUNT(*) FROM exchange_audit_log")
    suspend fun count(): Int

    /**
     * Count of FAILED/SPOOF outcomes from a specific peer in the last [windowMs] ms.
     * Used for anomaly detection — many failures from the same device may indicate
     * a probing attack.
     */
    @Query("""
        SELECT COUNT(*) FROM exchange_audit_log
        WHERE peerIdentityKeyHash = :hash
          AND outcome IN ('FAILED', 'SPOOF')
          AND timestampMs >= :since
    """)
    suspend fun countFailuresForPeer(hash: String, since: Long): Int

    /** Delete all audit entries — called from Settings "Clear audit log". */
    @Query("DELETE FROM exchange_audit_log")
    suspend fun deleteAll()

    /** Delete entries older than [beforeMs] — for a rolling retention window. */
    @Query("DELETE FROM exchange_audit_log WHERE timestampMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}
