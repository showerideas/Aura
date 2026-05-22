package com.showerideas.aura.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.data.BlocklistRepository
import com.showerideas.aura.model.BlockedEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PR-19: lists blocked endpoints (PR-14) and lets the user unblock them.
 */
@HiltViewModel
class BlockedDevicesViewModel @Inject constructor(
    private val blocklistRepository: BlocklistRepository
) : ViewModel() {

    val blocked: StateFlow<List<BlockedEndpoint>> = blocklistRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun unblock(endpoint: BlockedEndpoint) {
        viewModelScope.launch { blocklistRepository.unblock(endpoint) }
    }
}
