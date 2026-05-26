package com.showerideas.aura.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.showerideas.aura.model.SharePreset
import kotlinx.coroutines.flow.Flow

/**
 * T42 — DAO for [SharePreset] entities, relocated to data/local/ alongside all
 * other Room DAOs (was erroneously placed in data/ in Phase 9.1).
 *
 * The companion stub in data/SharePresetDao.kt is deprecated and will be
 * removed once all import sites have been migrated.
 */
@Dao
interface SharePresetDao {
    @Query("SELECT * FROM share_presets ORDER BY lastUsedAt DESC, id ASC")
    fun getAllPresetsFlow(): Flow<List<SharePreset>>

    @Query("SELECT * FROM share_presets ORDER BY lastUsedAt DESC, id ASC")
    suspend fun getAllPresets(): List<SharePreset>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: SharePreset): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(presets: List<SharePreset>)

    @Update
    suspend fun update(preset: SharePreset)

    @Delete
    suspend fun delete(preset: SharePreset)

    @Query("UPDATE share_presets SET lastUsedAt = :timestamp WHERE id = :presetId")
    suspend fun updateLastUsed(presetId: Int, timestamp: Long)

    @Query("SELECT COUNT(*) FROM share_presets WHERE isDefault = 1")
    suspend fun countDefaults(): Int
}
