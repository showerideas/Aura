package com.showerideas.aura.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * thin DataStore wrapper for auth-method preferences. Mirrors
 * [OnboardingPreferences] so the rest of the codebase doesn't have to
 * juggle DataStore plumbing directly.
 *
 * Uses a separate `aura_auth_prefs` DataStore file from the onboarding
 * DataStore (`aura_prefs`). Auth and onboarding flags are intentionally
 * kept in distinct files so they can be cleared independently.
 */
private val Context.authPrefsDataStore by preferencesDataStore(name = "aura_auth_prefs")

@Singleton
class AuthPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val METHOD_GESTURE = "gesture"
        const val METHOD_BIOMETRIC = "biometric"
        const val DEFAULT_METHOD = METHOD_GESTURE
    }

    /**
     * Currently selected auth method. Defaults to "gesture" so existing
     * installs continue to behave exactly the same after upgrading.
     */
    val authMethod: Flow<String> = context.authPrefsDataStore.data
        .map { it[DataStoreKeys.AUTH_METHOD] ?: DEFAULT_METHOD }

    suspend fun setAuthMethod(method: String) {
        require(method == METHOD_GESTURE || method == METHOD_BIOMETRIC) {
            "Unknown auth method: $method"
        }
        context.authPrefsDataStore.edit { it[DataStoreKeys.AUTH_METHOD] = method }
    }

    /**
     * whether the user wants AURA to run a background listener for
     * the triple-press volume-down activation gesture. Defaults to true
     * so the headline feature works out of the box.
     */
    val bgActivationEnabled: Flow<Boolean> = context.authPrefsDataStore.data
        .map { it[DataStoreKeys.BG_ACTIVATION_ENABLED] ?: true }

    suspend fun setBgActivationEnabled(enabled: Boolean) {
        context.authPrefsDataStore.edit {
            it[DataStoreKeys.BG_ACTIVATION_ENABLED] = enabled
        }
    }

    // -----------------------------------------------------------------------
    // gesture-gate DataStore binding.
    //
    // The gate flag used to live in NearbyExchangeService's companion object
    // (a JVM static), shared across all instances in the same process.  On a
    // device running AURA in both a personal and a work Android profile the
    // two profiles share the same APK class-loader and therefore the same
    // static fields — a verified gesture in profile A would silently open the
    // gate in profile B.
    //
    // Each Android user profile gets its own DataStore file on disk, so
    // storing the gate here makes the flag physically per-profile.
    // -----------------------------------------------------------------------

    /** Emits true while the current exchange session's gesture gate is open. */
    val gestureGateOpen: Flow<Boolean> = context.authPrefsDataStore.data
        .map { it[DataStoreKeys.GESTURE_GATE_OPEN] ?: false }

    /** Open or close the per-profile gesture gate. */
    suspend fun setGestureGateOpen(open: Boolean) {
        context.authPrefsDataStore.edit { it[DataStoreKeys.GESTURE_GATE_OPEN] = open }
    }
}
