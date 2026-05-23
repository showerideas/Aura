package com.showerideas.aura.ui.profile

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.GesturePattern
import com.showerideas.aura.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

    /** Live camera state — drives gesture label in the UI. */
    val liveGestureState: StateFlow<CameraHandEmbedder.GestureState> =
        gestureAuthManager.liveGestureState

    /**
     * 0..1 stability/confidence from the camera embedder, mapped to the same
     * field name the ProfileFragment already observes so the 5-bar strength
     * meter continues to work without layout changes.
     */
    val liveVariance: StateFlow<Float> = gestureAuthManager.liveGestureState
        .map { state ->
            when (state) {
                is CameraHandEmbedder.GestureState.Detecting -> state.stability
                is CameraHandEmbedder.GestureState.Stable    -> 1f
                else                                          -> 0f
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

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
            profileRepository.update(
                existing.copy(
                    displayName = name.trim(),
                    phone       = phone.trim(),
                    email       = email.trim(),
                    company     = company.trim(),
                    title       = title.trim(),
                    website     = website.trim(),
                    bio         = bio.trim(),
                    shareFields = shareFields
                )
            )
            _saveSuccess.value = true
        }
    }

    fun startGestureCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) =
        gestureAuthManager.startCamera(lifecycleOwner, previewView)

    fun stopGestureCamera() = gestureAuthManager.stopCamera()

    fun saveGesturePattern(pattern: GesturePattern) = gestureAuthManager.savePattern(pattern)

    fun clearGesturePattern() = gestureAuthManager.clearPattern()

    fun setAvatarUri(path: String) {
        viewModelScope.launch {
            val existing = profileRepository.getOrCreate()
            profileRepository.update(existing.copy(avatarUri = path))
        }
    }
}
