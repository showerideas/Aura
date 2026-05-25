package com.showerideas.aura.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.showerideas.aura.model.KnownPeer

/**
 * DAO for the [KnownPeer] TOFU registry.
 *
 * All operations are suspend functions so callers always run them on an
 * appropriate coroutine context (never the main thread).
 */
@Dao
interface KnownPeerDao {

    /** Return the persisted record for [endpointId], or null if unseen. */
    @Query("SELECT * FROM known_peers WHERE endpointId = :endpointId LIMIT 1")
    suspend fun get(endpointId: String): KnownPeer?

    /**
     * Insert or replace the record for this endpoint. On a repeat visit
     * this updates [KnownPeer.lastSeenAt] while preserving [KnownPeer.firstSeenAt].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: KnownPeer)

    /** Remove the record entirely (e.g. when the user unblocks / forgets a device). */
    @Query("DELETE FROM known_peers WHERE endpointId = :endpointId")
    suspend fun delete(endpointId: String)

    /**
     * Update only the [KnownPeer.lastSeenProfileVersion] for [endpointId].
     *
     * Called after a successful exchange so we can detect future card updates:
     * on next exchange, if the incoming [Contact.profileVersion] is higher
     * than this stored value, the peer's card changed since we last saw them.
     *
     * No-op if [endpointId] is not in the registry (unknown peer).
     */
    @Query("UPDATE known_peers SET last_seen_profile_version = :version WHERE endpointId = :endpointId")
    suspend fun updateLastSeenProfileVersion(endpointId: String, version: Int)
}
