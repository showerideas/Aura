package com.showerideas.aura.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.ProfileType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    contactRepository: ContactRepository
) : ViewModel() {

    /** The currently-active profile — drives greeting text + profile chip label. */
    val profile: StateFlow<Profile?> = profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** All profiles — drives the ProfileSwitcherBottomSheet list. */
    val allProfiles: StateFlow<List<Profile>> = profileRepository.allProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentContacts: StateFlow<List<Contact>> = contactRepository.allContacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Atomically switch the active profile. Called from ProfileSwitcherBottomSheet. */
    suspend fun setActiveProfile(id: String) = profileRepository.setActive(id)

    /**
     * Create a new profile of the given [type] and immediately make it active.
     * The new profile starts with an empty display name — the user edits it in ProfileFragment.
     */
    suspend fun createProfile(type: ProfileType) {
        val newProfile = profileRepository.create(type = type)
        profileRepository.setActive(newProfile.id)
    }

    /** Delete a non-active profile. */
    fun deleteProfile(id: String) {
        viewModelScope.launch {
            runCatching { profileRepository.delete(id) }
        }
    }
}

