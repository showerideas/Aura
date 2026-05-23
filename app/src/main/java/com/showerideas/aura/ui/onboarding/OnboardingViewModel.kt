package com.showerideas.aura.ui.onboarding

import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.data.OnboardingPreferences
import com.showerideas.aura.data.ProfileRepository
import com.showerideas.aura.model.GesturePattern
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val onboardingPrefs: OnboardingPreferences,
    private val gestureAuthManager: GestureAuthManager
) : ViewModel() {

    private val _finishEvent = MutableStateFlow(false)
    val finishEvent: StateFlow<Boolean> = _finishEvent

    val gestureRecordingState: StateFlow<GestureAuthManager.RecordingState> =
        gestureAuthManager.recordingState

    /** Live camera state — drives gesture label + stability bar in the UI. */
    val liveGestureState: StateFlow<CameraHandEmbedder.GestureState> =
        gestureAuthManager.liveGestureState

    fun startGestureCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) =
        gestureAuthManager.startCamera(lifecycleOwner, previewView)

    fun stopGestureCamera() = gestureAuthManager.stopCamera()

    fun saveGesturePattern(pattern: GesturePattern) = gestureAuthManager.savePattern(pattern)

    fun completeOnboarding(name: String, email: String) {
        viewModelScope.launch {
            val existing = profileRepository.getOrCreate()
            profileRepository.update(
                existing.copy(
                    displayName = name.trim(),
                    email       = email.trim()
                )
            )
            onboardingPrefs.setOnboardingComplete(true)
            _finishEvent.value = true
        }
    }
}
