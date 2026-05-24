package com.showerideas.aura.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Centralised registry of all DataStore preference keys used in the app.
 *
 * Adding a new flag? Put it here and reference it from the call site instead
 * of declaring keys ad-hoc. Keys are namespaced by feature for readability.
 */
object DataStoreKeys {

    // First-run onboarding completion flag.
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

    // Auth method preference ("gesture" | "biometric").
    val AUTH_METHOD = stringPreferencesKey("auth_method")

    // whether the VolumeButtonListenerService is allowed to run in
    // the background to detect the triple-press activation gesture.
    val BG_ACTIVATION_ENABLED = booleanPreferencesKey("bg_activation_enabled")

    // per-profile gesture-gate flag.  Stored in aura_auth_prefs so it
    // is physically separate from the onboarding store and is scoped to the
    // Android user profile that owns the DataStore file.  Reset to false on every
    // session termination — ephemeral state, not a long-lived preference.
    val GESTURE_GATE_OPEN = booleanPreferencesKey("gesture_gate_open")
}
