package com.showerideas.aura.data.local

import androidx.room.*
import com.showerideas.aura.model.Contact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: String): Contact?

    @Query("SELECT * FROM contacts WHERE isFavorite = 1 ORDER BY receivedAt DESC")
    fun observeFavorites(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE displayName LIKE '%' || :query || '%' OR email LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<Contact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: Contact)

    @Update
    suspend fun update(contact: Contact)

    @Delete
    suspend fun delete(contact: Contact)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM contacts")
    suspend fun count(): Int

    /** PR-10: latest contact saved from a given Nearby endpoint, used to attach an incoming avatar. */
    @Query("SELECT * FROM contacts WHERE sourceEndpointId = :endpointId ORDER BY receivedAt DESC LIMIT 1")
    suspend fun findLatestByEndpoint(endpointId: String): Contact?
}
