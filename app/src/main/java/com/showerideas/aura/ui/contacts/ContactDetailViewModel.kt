package com.showerideas.aura.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.BlocklistRepository
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val blocklistRepository: BlocklistRepository
) : ViewModel() {

    private val _contact = MutableStateFlow<Contact?>(null)
    val contact: StateFlow<Contact?> = _contact

    fun loadContact(id: String) {
        viewModelScope.launch {
            _contact.value = contactRepository.getById(id)
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            contactRepository.delete(contact)
        }
    }

    /**
     * PR-12: flip the favourite flag and refresh the observed contact so the
     * bottom sheet's star button repaints immediately.
     */
    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            val updated = contact.copy(isFavorite = !contact.isFavorite)
            contactRepository.update(updated)
            _contact.value = updated
        }
    }

    /**
     * PR-12: persist inline notes.
     */
    fun saveNote(contact: Contact, note: String) {
        viewModelScope.launch {
            val updated = contact.copy(notes = note)
            contactRepository.update(updated)
            _contact.value = updated
        }
    }

    /**
     * FIX-5: block this contact's device using stable identity key hash so
     * the block survives reconnects with a fresh Nearby endpoint ID.
     * Falls back to endpoint-ID-only block when hash is unavailable (e.g.
     * legacy contacts saved before FIX-5 populated the field).
     *
     * No-op when both endpointId and identityKeyHash are absent (should not
     * happen for contacts saved after PR-13).
     */
    fun blockEndpoint(endpointId: String, identityKeyHash: String?, note: String = "") {
        if (endpointId.isBlank() && identityKeyHash == null) return
        viewModelScope.launch {
            blocklistRepository.blockByIdentity(endpointId, identityKeyHash, note)
        }
    }
}
