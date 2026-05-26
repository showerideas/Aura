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
import com.showerideas.aura.data.ContactRepository
import com.showerideas.aura.data.local.SharePresetDao
import com.showerideas.aura.model.Contact
import com.showerideas.aura.model.ExchangeSession
import com.showerideas.aura.model.SharePreset
import com.showerideas.aura.service.NearbyExchangeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExchangeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gestureAuthManager: GestureAuthManager,
    private val authPreferences: AuthPreferences,
    private val contactRepository: ContactRepository,
    private val sharePresetDao: SharePresetDao
) : ViewModel() {

    val sessionState: StateFlow<ExchangeSession?> = NearbyExchangeService.sessionState

    private val _sasDialogShown = MutableStateFlow(false)
    val sasDialogShown: StateFlow<Boolean> = _sasDialogShown.asStateFlow()

    fun onSasDialogShown() { _sasDialogShown.value = true }

    init {
        viewModelScope.launch {
            sessionState.collect { session ->
                if (session?.state != ExchangeSession.State.VERIFYING) {
                    _sasDialogShown.value = false
                }
            }
        }
    }

    val gestureRecordingState: StateFlow<GestureAuthManager.RecordingState> =
        gestureAuthManager.recordingState

    val liveGestureState: StateFlow<CameraHandEmbedder.GestureState> =
        gestureAuthManager.liveGestureState

    val livenessResult: StateFlow<LivenessGuard.Result> =
        gestureAuthManager.livenessResult

    val authMethod: StateFlow<String> = authPreferences.authMethod
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthPreferences.DEFAULT_METHOD)

    // -------------------------------------------------------------------------
    // Phase 9.1: Share presets
    // -------------------------------------------------------------------------

    /** Emits the current list of share presets (default + user-created). */
    val sharePresets: StateFlow<List<SharePreset>> = sharePresetDao.getAllPresetsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Mark a preset as selected (updates lastUsedAt for recency sorting). */
    fun selectPreset(preset: SharePreset) {
        viewModelScope.launch {
            sharePresetDao.updateLastUsed(preset.id, System.currentTimeMillis())
        }
    }

    // -------------------------------------------------------------------------

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

    fun confirmSas() = NearbyExchangeService.confirmSas(context)

    fun abortSas() = NearbyExchangeService.abortSas(context)

    fun applyMergeSelections(base: Contact, selections: Map<String, String>) {
        viewModelScope.launch { contactRepository.applyMergeSelections(base, selections) }
    }
}
