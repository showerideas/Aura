package com.showerideas.aura.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.model.Contact
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    /**
     * PR-12: favourites-only filter, driven by the new chip in the Contacts
     * fragment. When ON, the contacts flow switches to
     * [ContactRepository.favorites]; when OFF, it falls back to the
     * search-aware allContacts source.
     */
    private val _showFavouritesOnly = MutableStateFlow(false)
    val showFavouritesOnly: StateFlow<Boolean> = _showFavouritesOnly.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val contacts: StateFlow<List<Contact>> = combine(
        _searchQuery.debounce(200),
        _showFavouritesOnly
    ) { query, favsOnly -> query to favsOnly }
        .flatMapLatest { (query, favsOnly) ->
            when {
                favsOnly && query.isBlank() -> contactRepository.favorites
                favsOnly -> contactRepository.favorites.map { list ->
                    list.filter {
                        it.displayName.contains(query, ignoreCase = true) ||
                            it.email.contains(query, ignoreCase = true) ||
                            it.phone.contains(query, ignoreCase = true)
                    }
                }
                query.isBlank() -> contactRepository.allContacts
                else -> contactRepository.search(query)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFavouritesFilter() {
        _showFavouritesOnly.value = !_showFavouritesOnly.value
    }

    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            contactRepository.update(contact.copy(isFavorite = !contact.isFavorite))
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            contactRepository.delete(contact)
        }
    }
}
