package com.showerideas.aura.data

import com.showerideas.aura.data.local.ContactDao
import com.showerideas.aura.model.Contact
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(
    private val contactDao: ContactDao
) {
    val allContacts: Flow<List<Contact>> = contactDao.observeAll()

    /**
     * Alias for [allContacts] — used by [com.showerideas.aura.utils.IdentityRotationDetector]
     * and other callers that prefer the shorter property name.
     */
    val contacts: Flow<List<Contact>> get() = allContacts

    val favorites: Flow<List<Contact>> = contactDao.observeFavorites()

    fun search(query: String): Flow<List<Contact>> = contactDao.search(query)

    suspend fun getById(id: String): Contact? = contactDao.getById(id)

    suspend fun save(contact: Contact) = contactDao.insert(contact)

    suspend fun update(contact: Contact) = contactDao.update(contact)

    suspend fun delete(contact: Contact) = contactDao.delete(contact)

    suspend fun deleteAll() = contactDao.deleteAll()

    suspend fun count(): Int = contactDao.count()

    /** PR-10: locate the most recently-saved contact for an endpoint. */
    suspend fun findLatestByEndpoint(endpointId: String) =
        contactDao.findLatestByEndpoint(endpointId)

    /**
     * Save a contact with deduplication by identity key hash.
     *
     * If [contact.identityKeyHash] is non-null and we already have a contact
     * with that hash, the existing record is updated in-place (preserving its
     * original [Contact.id], [Contact.receivedAt], and [Contact.isFavorite]
     * so the contact retains its history and favourite status).
     *
     * Falls back to a plain insert when no hash is available (e.g. legacy
     * contacts from before identity-key tracking was added in DB v4).
     */
    suspend fun saveDeduped(contact: Contact) {
        val existing = contact.identityKeyHash
            ?.takeIf { it.isNotBlank() }
            ?.let { contactDao.findByIdentityKeyHash(it) }
        if (existing != null) {
            // Merge: refresh mutable fields, preserve immutable record identity
            contactDao.update(
                contact.copy(
                    id         = existing.id,
                    receivedAt = existing.receivedAt,
                    isFavorite = existing.isFavorite,
                    notes      = existing.notes
                )
            )
        } else {
            contactDao.insert(contact)
        }
    }
}
