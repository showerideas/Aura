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

    fun stopGestureAndValidate(): Boolean {
        gestureAuthManager.stopRecording()
        val state = gestureAuthManager.recordingState.value
        if (state is GestureAuthManager.RecordingState.Complete) {
            return gestureAuthManager.match(state.pattern)
        }
        return false
    }

    fun hasGesturePattern(): Boolean = gestureAuthManager.hasStoredPattern()
}
