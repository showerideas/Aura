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
 * PR-16: thin DataStore wrapper for auth-method preferences. Mirrors
 * [OnboardingPreferences] so the rest of the codebase doesn't have to
 * juggle DataStore plumbing directly.
 *
 * Reuses the same `aura_prefs` DataStore instance as onboarding (declared
 * here via [authPrefsDataStore] which is the same underlying file —
 * preferencesDataStore() is idempotent per Context + name).
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
}
