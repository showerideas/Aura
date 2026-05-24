package com.showerideas.aura.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.showerideas.aura.model.BlockedEndpoint
import kotlinx.coroutines.flow.Flow

/**
 * read/write access to the blocked-endpoints table. Used by
 * [com.showerideas.aura.data.BlocklistRepository] and the new
 * `BlockedDevicesFragment`.
 */
@Dao
interface BlockedEndpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(endpoint: BlockedEndpoint)

    @Delete
    suspend fun unblock(endpoint: BlockedEndpoint)

    @Query("SELECT COUNT(*) > 0 FROM blocked_endpoints WHERE endpointId = :id")
    suspend fun isBlocked(id: String): Boolean

    /**
     * check whether any blocked entry carries this identity-key hash.
     * Matches devices that reconnected with a fresh Nearby endpoint ID —
     * the hash is stable across sessions; the endpoint ID is not.
     */
    @Query("SELECT COUNT(*) > 0 FROM blocked_endpoints WHERE identityKeyHash = :hash")
    suspend fun isBlockedByKeyHash(hash: String): Boolean

    @Query("SELECT * FROM blocked_endpoints ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedEndpoint>>
}
