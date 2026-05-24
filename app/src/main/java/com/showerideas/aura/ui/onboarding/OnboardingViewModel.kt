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

    /**
     * Add [pattern] to the multi-sample enrollment centroid accumulator.
     *
     * Previously called [GestureAuthManager.savePattern] (single-sample only).
     * Using [GestureAuthManager.addEnrollmentSample] persists up to
     * [GestureAuthManager.MAX_ENROLLMENT_SAMPLES] raw embeddings and recomputes
     * the centroid after each addition, reducing per-capture noise and improving
     * the False Accept Rate without requiring the user to re-do onboarding.
     *
     * @return sample count now stored (1 .. [GestureAuthManager.MAX_ENROLLMENT_SAMPLES]).
     */
    fun addEnrollmentSample(pattern: GesturePattern): Int =
        gestureAuthManager.addEnrollmentSample(pattern)

    fun enrolledSampleCount(): Int = gestureAuthManager.enrolledSampleCount()

    /**
     * Reset the gesture capture pipeline after a failed attempt (e.g. liveness
     * spoof rejection) so the user can try again without restarting the camera.
     */
    fun resetGestureCapture() = gestureAuthManager.resetGestureCapture()

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
