package com.showerideas.aura.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.data.AuthPreferences
import com.showerideas.aura.data.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * backing ViewModel for the Settings screen. Centralises all the
 * app-wide knobs that previously lived only in code or inside other
 * screens.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val gestureAuthManager: GestureAuthManager,
    private val contactRepository: ContactRepository,
    private val authPreferences: AuthPreferences
) : ViewModel() {

    val authMethod: StateFlow<String> = authPreferences.authMethod
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            AuthPreferences.DEFAULT_METHOD)

    val bgActivationEnabled: StateFlow<Boolean> = authPreferences.bgActivationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setAuthMethod(method: String) {
        viewModelScope.launch { authPreferences.setAuthMethod(method) }
    }

    fun setBgActivation(enabled: Boolean) {
        viewModelScope.launch { authPreferences.setBgActivationEnabled(enabled) }
    }

    fun clearGesture() {
        gestureAuthManager.clearPattern()
    }

    fun clearAllContacts() {
        viewModelScope.launch { contactRepository.deleteAll() }
    }

    suspend fun contactCount(): Int = contactRepository.count()
}
