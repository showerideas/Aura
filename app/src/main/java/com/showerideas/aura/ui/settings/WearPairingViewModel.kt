package com.showerideas.aura.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Phase F1 — ViewModel for [WearPairingBottomSheet].
 *
 * Discovers connected Wear OS nodes via the Wearable Data Layer and exposes
 * the result as a [UiState] flow. Handles the case where the GMS Wearable
 * API is unavailable (FOSS build / no Play Services) gracefully.
 */
@HiltViewModel
class WearPairingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed class UiState {
        object Loading : UiState()
        /** Wearable Data Layer is not available (FOSS build / no Play Services). */
        object NoWearableApi : UiState()
        /** API available but no Wear OS device is currently connected. */
        object NoNodesFound : UiState()
        /** One or more watches connected; [nodeNames] is the display-name list. */
        data class NodesFound(val nodeNames: List<String>) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            _uiState.value = discoverNodes()
        }
    }

    private suspend fun discoverNodes(): UiState {
        return try {
            val client = com.google.android.gms.wearable.Wearable.getNodeClient(context)
            val nodes = kotlinx.coroutines.tasks.await(client.connectedNodes)
            Timber.d("WearPairingViewModel: found %d connected node(s)", nodes.size)
            if (nodes.isEmpty()) {
                UiState.NoNodesFound
            } else {
                UiState.NodesFound(nodes.map { it.displayName })
            }
        } catch (e: Exception) {
            Timber.w(e, "WearPairingViewModel: Wearable API unavailable")
            UiState.NoWearableApi
        }
    }
}
