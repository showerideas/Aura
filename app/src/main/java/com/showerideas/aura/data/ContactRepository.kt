package com.showerideas.aura.data

import com.showerideas.aura.data.local.ContactDao
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.MergeEvent
import com.showerideas.aura.utils.ContactDiffEngine
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

    /** locate the most recently-saved contact for an endpoint. */
    suspend fun findLatestByEndpoint(endpointId: String) =
        contactDao.findLatestByEndpoint(endpointId)

    /**
     * Save a contact with deduplication by identity key hash.
     *
     * If [contact.identityKeyHash] is non-null and we already have a contact
     * with that hash, the existing record is updated in-place (preserving its
     * original [Contact.id], [Contact.receivedAt], [Contact.isFavorite], and
     * [Contact.notes] so the contact retains its history and favourite status).
     *
     * Falls back to a plain insert when no hash is available (e.g. legacy
     * contacts from before identity-key tracking was added in DB v4).
     *
     * @return A [MergeEvent] if an existing contact was updated and any visible
     *   fields changed — the caller should show the merge review UI.
     *   Returns null on a fresh insert or when all fields are identical.
     */
    suspend fun saveDeduped(contact: Contact): MergeEvent? {
        val existing = contact.identityKeyHash
            ?.takeIf { it.isNotBlank() }
            ?.let { contactDao.findByIdentityKeyHash(it) }

        return if (existing != null) {
            // Compute diff BEFORE we overwrite the record
            val event = ContactDiffEngine.diff(previous = existing, incoming = contact)
            // Merge: refresh mutable fields, preserve immutable record identity
            val merged = contact.copy(
                id         = existing.id,
                receivedAt = existing.receivedAt,
                isFavorite = existing.isFavorite,
                notes      = existing.notes
            )
            contactDao.update(merged)
            // Only surface a MergeEvent if something actually changed
            if (event.hasChanges) event.copy(preserved = merged) else null
        } else {
            contactDao.insert(contact)
            null
        }
    }

    /**
     * Apply per-field [selections] chosen by the user in the merge UI and
     * persist the resulting contact to Room.
     *
     * @param base       The currently-saved (merged) contact.
     * @param selections Map of fieldName → chosen value (old or new).
     */
    /**
     * Insert-or-replace a list of contacts in bulk (e.g. restoring from backup).
     * Uses ContactDao.insert which has OnConflictStrategy.REPLACE.
     */
    suspend fun upsertAll(contacts: List<Contact>) {
        contacts.forEach { contactDao.insert(it) }
    }

    suspend fun applyMergeSelections(base: Contact, selections: Map<String, String>) {
        val updated = ContactDiffEngine.applySelections(base, selections)
        contactDao.update(updated)
    }
}
