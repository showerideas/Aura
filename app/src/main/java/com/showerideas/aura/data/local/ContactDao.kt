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

    @Query("""
        SELECT * FROM contacts WHERE
            displayName LIKE '%' || :query || '%' OR
            email       LIKE '%' || :query || '%' OR
            phone       LIKE '%' || :query || '%' OR
            company     LIKE '%' || :query || '%' OR
            title       LIKE '%' || :query || '%' OR
            notes       LIKE '%' || :query || '%' OR
            bio         LIKE '%' || :query || '%' OR
            website     LIKE '%' || :query || '%'
    """)
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

    /** latest contact saved from a given Nearby endpoint, used to attach an incoming avatar. */
    @Query("SELECT * FROM contacts WHERE sourceEndpointId = :endpointId ORDER BY receivedAt DESC LIMIT 1")
    suspend fun findLatestByEndpoint(endpointId: String): Contact?

    /**
     * Find an existing contact by their stable identity key hash.
     *
     * Used for contact deduplication: if we already have a contact with this
     * identity key hash, the incoming exchange is from the same device and we
     * should update the existing record rather than creating a duplicate.
     *
     * Returns null if [hash] is null/empty (no hash available) to avoid
     * treating all hash-less contacts as the same person.
     */
    @Query("SELECT * FROM contacts WHERE identityKeyHash = :hash LIMIT 1")
    suspend fun findByIdentityKeyHash(hash: String): Contact?
}
