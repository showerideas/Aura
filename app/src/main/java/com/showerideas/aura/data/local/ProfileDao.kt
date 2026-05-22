package com.showerideas.aura.data.local

import androidx.room.*
import com.showerideas.aura.model.Profile
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profile WHERE id = 'local_profile' LIMIT 1")
    fun observe(): Flow<Profile?>

    @Query("SELECT * FROM profile WHERE id = 'local_profile' LIMIT 1")
    suspend fun get(): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: Profile)

    @Update
    suspend fun update(profile: Profile)

    @Query("DELETE FROM profile")
    suspend fun clear()
}
