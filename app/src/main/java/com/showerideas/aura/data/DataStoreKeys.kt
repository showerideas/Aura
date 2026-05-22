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

    // PR-05: First-run onboarding completion flag.
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

    // PR-16: Auth method preference ("gesture" | "biometric").
    val AUTH_METHOD = stringPreferencesKey("auth_method")

    // PR-19: whether the VolumeButtonListenerService is allowed to run in
    // the background to detect the triple-press activation gesture.
    val BG_ACTIVATION_ENABLED = booleanPreferencesKey("bg_activation_enabled")
}
