package com.showerideas.aura.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Centralised registry of all DataStore preference keys used in the app.
 */
object DataStoreKeys {
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
    val AUTH_METHOD = stringPreferencesKey("auth_method")
    val BG_ACTIVATION_ENABLED = booleanPreferencesKey("bg_activation_enabled")
    val GESTURE_GATE_OPEN = booleanPreferencesKey("gesture_gate_open")
    /** Phase 8.3: route relay traffic through Tor/Orbot when true. */
    val TOR_PROXY_ENABLED = booleanPreferencesKey("tor_proxy_enabled")
}
