package com.showerideas.aura.ui.exchange

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.auth.LivenessGuard
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

    /**
     * Real-time liveness result from [LivenessGuard]. Collecting this flow allows
     * the UI to show a "Live ✓" or "Spoof detected ⚠" badge during gesture recording.
     * Spoof rejections are also surfaced via [gestureRecordingState] (RecordingState.Error)
     * — this flow provides the immediate per-frame signal for progress indication.
     */
    val livenessResult: StateFlow<LivenessGuard.Result> =
        gestureAuthManager.livenessResult

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
            if (ok) NearbyExchangeService.markGestureVerified(context)
            return ok
        }
        return false
    }

    fun resetGestureCapture() = gestureAuthManager.resetGestureCapture()

    fun proceedWithoutGesture() = NearbyExchangeService.markGestureVerified(context)

    fun markExchangeVerified() = NearbyExchangeService.markGestureVerified(context)

    fun hasGesturePattern(): Boolean = gestureAuthManager.hasStoredPattern()

    /**
     * SAS PIN confirmed — the user and their peer see the same code.
     * Sends [NearbyExchangeService.ACTION_CONFIRM_SAS] to ungate [sendProfile].
     */
    fun confirmSas() = NearbyExchangeService.confirmSas(context)

    /**
     * SAS PIN mismatch — user reports the codes differ (possible MITM).
     * Terminates the session with an error via [NearbyExchangeService.ACTION_ABORT_SAS].
     */
    fun abortSas() = NearbyExchangeService.abortSas(context)
}
