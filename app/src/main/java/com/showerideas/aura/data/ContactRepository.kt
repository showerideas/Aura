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
}
