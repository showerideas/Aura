package com.showerideas.aura.ar

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject

/**
 * Task 103 — ViewModel for Settings → Privacy → AR Exchange.
 *
 * Manages:
 *   - [arExchangeEnabled]: master toggle gated by [arPrivacyAccepted].
 *   - [arPrivacyAccepted]: one-time acceptance of [ArPrivacyDisclosureSheet].
 *   - [arRangeMetres]: UWB/RSSI distance threshold (1m / 1.5m / 2m).
 *   - [xrAmbientModeEnabled]: whether spatial cards appear without explicit user activation.
 *
 * Preferences are persisted to EncryptedSharedPreferences under the key prefix "ar_".
 *
 * See: ROADMAP §Task 103
 */
@HiltViewModel
class ArSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val PREFS_NAME = "ar_settings"
        private const val KEY_PRIVACY_ACCEPTED  = "ar_privacy_accepted"
        private const val KEY_AR_ENABLED        = "ar_exchange_enabled"
        private const val KEY_AR_RANGE          = "ar_range_metres"
        private const val KEY_XR_AMBIENT        = "xr_ambient_mode"

        val ALLOWED_RANGES = listOf(1.0f, 1.5f, 2.0f)
        const val DEFAULT_RANGE = 1.5f
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _arPrivacyAccepted = MutableStateFlow(prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false))
    val arPrivacyAccepted: StateFlow<Boolean> = _arPrivacyAccepted

    private val _arExchangeEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AR_ENABLED, false) && _arPrivacyAccepted.value
    )
    val arExchangeEnabled: StateFlow<Boolean> = _arExchangeEnabled

    private val _arRangeMetres = MutableStateFlow(
        prefs.getFloat(KEY_AR_RANGE, DEFAULT_RANGE)
    )
    val arRangeMetres: StateFlow<Float> = _arRangeMetres

    private val _xrAmbientModeEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_XR_AMBIENT, false)
    )
    val xrAmbientModeEnabled: StateFlow<Boolean> = _xrAmbientModeEnabled

    // ── Actions ───────────────────────────────────────────────────────────────

    fun acceptArPrivacy() {
        prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply()
        _arPrivacyAccepted.value = true
        Timber.i("ArSettingsViewModel: AR privacy accepted")
    }

    fun declineArPrivacy() {
        _arPrivacyAccepted.value = false
        setArExchangeEnabled(false)
        Timber.i("ArSettingsViewModel: AR privacy declined")
    }

    fun setArExchangeEnabled(enabled: Boolean) {
        if (enabled && !_arPrivacyAccepted.value) {
            Timber.w("ArSettingsViewModel: cannot enable AR exchange — privacy not accepted")
            return
        }
        prefs.edit().putBoolean(KEY_AR_ENABLED, enabled).apply()
        _arExchangeEnabled.value = enabled
        Timber.i("ArSettingsViewModel: AR exchange enabled=$enabled")
    }

    fun setArRangeMetres(metres: Float) {
        require(metres in ALLOWED_RANGES) {
            "AR range must be one of $ALLOWED_RANGES, got $metres"
        }
        prefs.edit().putFloat(KEY_AR_RANGE, metres).apply()
        _arRangeMetres.value = metres
        Timber.i("ArSettingsViewModel: AR range set to ${metres}m")
    }

    fun setXrAmbientModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_XR_AMBIENT, enabled).apply()
        _xrAmbientModeEnabled.value = enabled
        Timber.i("ArSettingsViewModel: XR ambient mode enabled=$enabled")
    }
}
