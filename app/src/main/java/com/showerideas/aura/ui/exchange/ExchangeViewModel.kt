package com.showerideas.aura.ui.exchange

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.model.GesturePattern
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ExchangeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gestureAuthManager: GestureAuthManager
) : ViewModel() {

    val sessionState: StateFlow<ExchangeSession?> = NearbyExchangeService.sessionState
    val gestureRecordingState: StateFlow<GestureAuthManager.RecordingState> =
        gestureAuthManager.recordingState

    fun cancelExchange() = NearbyExchangeService.stop(context)

    fun startGestureRecording() = gestureAuthManager.startRecording()

    /**
     * Stop the gesture recording, run a DTW match against the stored
     * pattern, and — if the match succeeds — flip the service-level
     * verification gate so [NearbyExchangeService.startSession] can proceed.
     *
     * @return true when the gesture matched and the service gate was opened.
     */
    fun validateGestureAndUnlockService(): Boolean {
        gestureAuthManager.stopRecording()
        val state = gestureAuthManager.recordingState.value
        if (state is GestureAuthManager.RecordingState.Complete) {
            val ok = gestureAuthManager.match(state.pattern)
            if (ok) {
                NearbyExchangeService.markGestureVerified()
            }
            return ok
        }
        return false
    }

    /**
     * Called when the user explicitly acknowledges they want to proceed
     * without any gesture protection. Opens the gate directly.
     */
    fun proceedWithoutGesture() {
        NearbyExchangeService.markGestureVerified()
    }

    fun hasGesturePattern(): Boolean = gestureAuthManager.hasStoredPattern()
}
