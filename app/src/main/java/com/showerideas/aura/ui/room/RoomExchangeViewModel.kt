package com.showerideas.aura.ui.room

import android.content.Context
import androidx.lifecycle.ViewModel
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for the room-mode exchange screen. Forwards lifecycle events
 * to [NearbyExchangeService] and exposes its live state flows.
 *
 * Auth flow mirrors [com.showerideas.aura.ui.exchange.ExchangeViewModel]:
 * the service's gesture gate must be unlocked via [markGestureVerified]
 * BEFORE any startRoom* call. The fragment is responsible for:
 *   1. Recording + validating the gesture via [validateGestureAndUnlockService]
 *      when a pattern is stored.
 *   2. Calling [proceedWithoutGesture] only after an explicit user
 *      confirmation when no pattern is stored.
 */
@HiltViewModel
class RoomExchangeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gestureAuthManager: GestureAuthManager
) : ViewModel() {

    val sessionState: StateFlow<ExchangeSession?> = NearbyExchangeService.sessionState
    val connectedCount: StateFlow<Int> = NearbyExchangeService.connectedCount
    val gestureRecordingState: StateFlow<GestureAuthManager.RecordingState> =
        gestureAuthManager.recordingState

    fun hasGesturePattern(): Boolean = gestureAuthManager.hasStoredPattern()

    fun startGestureRecording() = gestureAuthManager.startRecording()

    /**
     * Stop recording, run DTW against the stored pattern, and unlock the
     * service gate iff the gesture matched.
     *
     * @return true when the gesture matched and the service gate is now open.
     */
    fun validateGestureAndUnlockService(): Boolean {
        gestureAuthManager.stopRecording()
        val state = gestureAuthManager.recordingState.value
        if (state is GestureAuthManager.RecordingState.Complete) {
            val ok = gestureAuthManager.match(state.pattern)
            if (ok) NearbyExchangeService.markGestureVerified()
            return ok
        }
        return false
    }

    /**
     * Called when the user explicitly acknowledges they want to proceed
     * without any gesture protection. Opens the gate directly.
     */
    fun proceedWithoutGesture() = NearbyExchangeService.markGestureVerified()

    fun startHost() = NearbyExchangeService.startRoomHost(context)
    fun startGuest() = NearbyExchangeService.startRoomGuest(context)
    fun closeRoom() = NearbyExchangeService.stop(context)
}
