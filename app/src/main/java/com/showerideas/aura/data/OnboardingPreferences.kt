package com.showerideas.aura.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.onboardingDataStore by preferencesDataStore(name = "aura_prefs")

/**
 * Tiny wrapper around DataStore for onboarding-related flags so the rest
 * of the app doesn't have to know about the DataStore plumbing.
 */
@Singleton
class OnboardingPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isOnboardingComplete: Flow<Boolean> =
        context.onboardingDataStore.data.map { it[DataStoreKeys.ONBOARDING_COMPLETE] ?: false }

    /** Synchronous read for use during Activity.onCreate before the nav graph inflates. */
    suspend fun isOnboardingCompleteOnce(): Boolean = isOnboardingComplete.first()

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.onboardingDataStore.edit { it[DataStoreKeys.ONBOARDING_COMPLETE] = complete }
    }
}
