package com.showerideas.aura.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    contactRepository: ContactRepository
) : ViewModel() {

    val profile: StateFlow<Profile?> = profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recentContacts: StateFlow<List<Contact>> = contactRepository.allContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
