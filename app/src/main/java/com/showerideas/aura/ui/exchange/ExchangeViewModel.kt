package com.showerideas.aura.ui.exchange

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ExchangeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gestureAuthManager: GestureAuthManager,
    private val authPreferences: AuthPreferences
) : ViewModel() {

    val sessionState: StateFlow<ExchangeSession?> = NearbyExchangeService.sessionState

    val gestureRecordingState: StateFlow<GestureAuthManager.RecordingState> =
        gestureAuthManager.recordingState

    /** Live camera state — drives gesture name + stability indicator in the UI. */
    val liveGestureState: StateFlow<CameraHandEmbedder.GestureState> =
        gestureAuthManager.liveGestureState

    val authMethod: StateFlow<String> = authPreferences.authMethod
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthPreferences.DEFAULT_METHOD)

    fun cancelExchange() = NearbyExchangeService.stop(context)

    fun startGestureCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) =
        gestureAuthManager.startCamera(lifecycleOwner, previewView)

    fun stopGestureCamera() = gestureAuthManager.stopCamera()

    fun validateGestureAndUnlockService(): Boolean {
        val state = gestureAuthManager.recordingState.value
        if (state is GestureAuthManager.RecordingState.Complete) {
            val ok = gestureAuthManager.match(state.pattern)
            if (ok) NearbyExchangeService.markGestureVerified()
            return ok
        }
        return false
    }

    fun resetGestureCapture() = gestureAuthManager.resetGestureCapture()

    fun proceedWithoutGesture() = NearbyExchangeService.markGestureVerified()

    fun markExchangeVerified() = NearbyExchangeService.markGestureVerified()

    fun hasGesturePattern(): Boolean = gestureAuthManager.hasStoredPattern()
}
