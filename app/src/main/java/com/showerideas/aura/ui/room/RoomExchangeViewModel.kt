package com.showerideas.aura.ui.room

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for the room-mode exchange screen.
 *
 * Auth mirrors [ExchangeViewModel]: the service gate must be unlocked via
 * [validateGestureAndUnlockService] (or [proceedWithoutGesture]) before
 * any startRoom* call.  The camera pipeline is started by the Fragment
 * passing its view-lifecycle owner and [PreviewView] reference.
 */
@HiltViewModel
class RoomExchangeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gestureAuthManager: GestureAuthManager
) : ViewModel() {

    val sessionState: StateFlow<ExchangeSession?> = NearbyExchangeService.sessionState
    val connectedCount: StateFlow<Int>             = NearbyExchangeService.connectedCount

    val gestureRecordingState: StateFlow<GestureAuthManager.RecordingState> =
        gestureAuthManager.recordingState

    val liveGestureState: StateFlow<CameraHandEmbedder.GestureState> =
        gestureAuthManager.liveGestureState

    fun hasGesturePattern(): Boolean = gestureAuthManager.hasStoredPattern()

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

    fun startHost()  = NearbyExchangeService.startRoomHost(context)
    fun startGuest() = NearbyExchangeService.startRoomGuest(context)
    fun closeRoom()  = NearbyExchangeService.stop(context)
}
