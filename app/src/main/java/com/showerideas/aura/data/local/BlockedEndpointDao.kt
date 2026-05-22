package com.showerideas.aura.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.showerideas.aura.model.BlockedEndpoint
import kotlinx.coroutines.flow.Flow

/**
 * PR-14: read/write access to the blocked-endpoints table. Used by
 * [com.showerideas.aura.data.BlocklistRepository] and the new
 * `BlockedDevicesFragment` (PR-19).
 */
@Dao
interface BlockedEndpointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(endpoint: BlockedEndpoint)

    @Delete
    suspend fun unblock(endpoint: BlockedEndpoint)

    @Query("SELECT COUNT(*) > 0 FROM blocked_endpoints WHERE endpointId = :id")
    suspend fun isBlocked(id: String): Boolean

    @Query("SELECT * FROM blocked_endpoints ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedEndpoint>>
}
