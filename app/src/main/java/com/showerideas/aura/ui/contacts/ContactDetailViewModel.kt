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
     * PR-14: permanently block the Nearby endpoint that originally sent us
     * this contact. The next time that endpoint initiates a connection,
     * [com.showerideas.aura.service.NearbyExchangeService] will reject it
     * before any handshake. No-op when the contact has no source endpoint
     * recorded (e.g. legacy rows from before the field was populated).
     */
    fun blockEndpoint(endpointId: String, note: String = "") {
        if (endpointId.isBlank()) return
        viewModelScope.launch {
            blocklistRepository.block(endpointId, note)
        }
    }
}
