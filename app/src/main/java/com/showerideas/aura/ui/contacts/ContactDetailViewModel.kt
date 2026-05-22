package com.showerideas.aura.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val contactRepository: ContactRepository
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
     * bottom sheet's star button repaints immediately. The previous version
     * mutated state but only the row in the list reflected it; the sheet
     * itself kept the stale `isFavorite` value until you reopened it.
     */
    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            val updated = contact.copy(isFavorite = !contact.isFavorite)
            contactRepository.update(updated)
            _contact.value = updated
        }
    }

    /**
     * PR-12: persist inline notes. We refresh _contact so subsequent
     * toggleFavorite calls operate on the latest `notes` value and don't
     * overwrite the just-saved text with a stale copy.
     */
    fun saveNote(contact: Contact, note: String) {
        viewModelScope.launch {
            val updated = contact.copy(notes = note)
            contactRepository.update(updated)
            _contact.value = updated
        }
    }
}
