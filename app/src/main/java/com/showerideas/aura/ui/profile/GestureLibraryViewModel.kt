package com.showerideas.aura.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.GestureAuthManager
import com.showerideas.aura.data.AuthPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 9.3 — ViewModel for GestureLibraryFragment.
 * Manages up to 5 named gesture profiles stored in EncryptedSharedPreferences.
 */
@HiltViewModel
class GestureLibraryViewModel @Inject constructor(
    private val authPreferences: AuthPreferences,
    private val gestureAuthManager: GestureAuthManager
) : ViewModel() {

    companion object {
        const val MAX_GESTURE_PROFILES = 5
    }

    data class GestureProfile(
        val slot: Int,
        val name: String,
        val enrolledAt: Long,
        val hasData: Boolean
    )

    private val _profiles = MutableStateFlow<List<GestureProfile>>(emptyList())
    val profiles: StateFlow<List<GestureProfile>> = _profiles.asStateFlow()

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            val profiles = (0 until MAX_GESTURE_PROFILES).map { slot ->
                val name = authPreferences.getGestureProfileName(slot) ?: "Gesture ${slot + 1}"
                val enrolledAt = authPreferences.getGestureProfileEnrolledAt(slot) ?: 0L
                val hasData = authPreferences.hasGestureProfile(slot)
                GestureProfile(slot, name, enrolledAt, hasData)
            }
            _profiles.value = profiles
        }
    }

    fun deleteProfile(slot: Int) {
        viewModelScope.launch {
            authPreferences.deleteGestureProfile(slot)
            loadProfiles()
        }
    }

    fun renameProfile(slot: Int, newName: String) {
        viewModelScope.launch {
            authPreferences.setGestureProfileName(slot, newName)
            loadProfiles()
        }
    }
}
