package com.showerideas.aura.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.GesturePattern
import com.showerideas.aura.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val gestureAuthManager: GestureAuthManager
) : ViewModel() {

    val profile: StateFlow<Profile?> = profileRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gestureRecordingState: StateFlow<GestureAuthManager.RecordingState> =
        gestureAuthManager.recordingState

    /**
     * PR-11: rolling variance from the gesture manager, surfaced to the UI
     * to drive the 5-bar gesture-strength meter while recording.
     */
    val liveVariance: StateFlow<Float> = gestureAuthManager.liveVariance

    val hasGesturePattern: Boolean get() = gestureAuthManager.hasStoredPattern()

    private val _saveSuccess = MutableStateFlow(false)
    val saveSuccess: StateFlow<Boolean> = _saveSuccess

    fun saveProfile(
        name: String, phone: String, email: String,
        company: String, title: String, website: String, bio: String,
        shareFields: String
    ) {
        viewModelScope.launch {
            val existing = profileRepository.getOrCreate()
            val updated = existing.copy(
                displayName = name.trim(),
                phone = phone.trim(),
                email = email.trim(),
                company = company.trim(),
                title = title.trim(),
                website = website.trim(),
                bio = bio.trim(),
                shareFields = shareFields
            )
            profileRepository.update(updated)
            _saveSuccess.value = true
        }
    }

    fun startGestureRecording() = gestureAuthManager.startRecording()

    fun stopGestureRecording() = gestureAuthManager.stopRecording()

    fun saveGesturePattern(pattern: GesturePattern) = gestureAuthManager.savePattern(pattern)

    fun clearGesturePattern() = gestureAuthManager.clearPattern()

    /**
     * PR-10: persist the absolute filesystem path of the user's chosen
     * avatar onto the Profile entity. The actual JPEG lives on disk —
     * we only store its path here so the share pipeline can stream it.
     */
    fun setAvatarUri(path: String) {
        viewModelScope.launch {
            val existing = profileRepository.getOrCreate()
            profileRepository.update(existing.copy(avatarUri = path))
        }
    }
}
