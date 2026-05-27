package com.showerideas.aura.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authPrefsDataStore by preferencesDataStore(name = "aura_auth_prefs")

@Singleton
class AuthPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val METHOD_GESTURE = "gesture"
        const val METHOD_BIOMETRIC = "biometric"
        const val DEFAULT_METHOD = METHOD_GESTURE

        private const val GESTURE_PREFS = "aura_gesture_prefs"
    }

    // EncryptedSharedPreferences for gesture profile metadata (sync reads, no PII)
    private val gesturePrefs: android.content.SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, GESTURE_PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val authMethod: Flow<String> = context.authPrefsDataStore.data
        .map { it[DataStoreKeys.AUTH_METHOD] ?: DEFAULT_METHOD }

    suspend fun setAuthMethod(method: String) {
        require(method == METHOD_GESTURE || method == METHOD_BIOMETRIC) {
            "Unknown auth method: $method"
        }
        context.authPrefsDataStore.edit { it[DataStoreKeys.AUTH_METHOD] = method }
    }

    val gestureGateOpen: Flow<Boolean> = context.authPrefsDataStore.data
        .map { it[DataStoreKeys.GESTURE_GATE_OPEN] ?: false }

    suspend fun setGestureGateOpen(open: Boolean) {
        context.authPrefsDataStore.edit { it[DataStoreKeys.GESTURE_GATE_OPEN] = open }
    }

    // Tor proxy preference

    val torProxyEnabled: Flow<Boolean> = context.authPrefsDataStore.data
        .map { it[DataStoreKeys.TOR_PROXY_ENABLED] ?: false }

    suspend fun setTorProxyEnabled(enabled: Boolean) {
        context.authPrefsDataStore.edit { it[DataStoreKeys.TOR_PROXY_ENABLED] = enabled }
    }

    // Gesture profile metadata (name, timestamp, presence)
    //
    // Stored in the same EncryptedSharedPreferences file as the embeddings so
    // all gesture data is co-located and encrypted at rest.

    fun getGestureProfileName(slot: Int): String? =
        gesturePrefs.getString("gesture_profile_name_$slot", null)

    fun setGestureProfileName(slot: Int, name: String) {
        gesturePrefs.edit().putString("gesture_profile_name_$slot", name).apply()
    }

    fun getGestureProfileEnrolledAt(slot: Int): Long? {
        val v = gesturePrefs.getLong("gesture_profile_enrolled_at_$slot", -1L)
        return if (v == -1L) null else v
    }

    fun hasGestureProfile(slot: Int): Boolean =
        gesturePrefs.contains("gesture_feature_vector_$slot") ||
        (slot == 0 && gesturePrefs.contains("gesture_feature_vector"))

    fun deleteGestureProfile(slot: Int) {
        gesturePrefs.edit()
            .remove("gesture_profile_name_$slot")
            .remove("gesture_profile_enrolled_at_$slot")
            .remove("gesture_feature_vector_$slot")
            .apply()
    }
}
