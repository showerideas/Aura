package com.showerideas.aura.data

import com.showerideas.aura.data.local.ProfileDao
import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.ProfileType
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the user's own profile(s).
 *
 * ## Single-profile legacy path
 * [profile] and [get] / [save] / [update] / [getOrCreate] are unchanged from
 * v1.x — all existing callers continue to work without modification.
 *
 * ## Multi-profile additions (Phase 6.4 / DB v6)
 * [allProfiles] exposes all rows ordered by creation time.
 * [setActive] atomically switches the active profile.
 * [create] builds a new profile row with a UUID key and the desired type.
 * [delete] removes a non-active profile.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao
) {
    // -------------------------------------------------------------------------
    // Backward-compatible single-profile interface
    // -------------------------------------------------------------------------

    /** Flow of the currently-active profile. Used by HomeViewModel for the greeting. */
    val profile: Flow<Profile?> = profileDao.observe()

    /** One-shot read of the active profile. */
    suspend fun get(): Profile? = profileDao.getActive()

    /** Insert or replace (legacy single-profile path — key is always 'local_profile'). */
    suspend fun save(profile: Profile) = profileDao.insert(profile)

    /**
     * Update an existing profile, stamping [Profile.updatedAt] and incrementing
     * [Profile.version] so peers detect card changes on their next exchange (Phase 6.7).
     */
    suspend fun update(profile: Profile) {
        val updated = profile.copy(
            updatedAt = System.currentTimeMillis(),
            version   = profile.version + 1
        )
        profileDao.update(updated)
    }

    /** Get the active profile or create the default 'local_profile' if table is empty. */
    suspend fun getOrCreate(): Profile {
        return profileDao.getActive() ?: Profile().also { profileDao.insert(it) }
    }

    // -------------------------------------------------------------------------
    // Multi-profile interface (Phase 6.4)
    // -------------------------------------------------------------------------

    /** All profiles ordered oldest-first — drives the profile switcher list. */
    val allProfiles: Flow<List<Profile>> = profileDao.getAllProfiles()

    /** Observe the currently-active profile (alias for [profile]). */
    fun observeActive(): Flow<Profile?> = profileDao.observeActive()

    /** One-shot read of the active profile. */
    suspend fun getActive(): Profile? = profileDao.getActive()

    /**
     * Atomically switch the active profile to [id].
     *
     * Emits on [profile] / [allProfiles] immediately after the transaction.
     */
    suspend fun setActive(id: String) = profileDao.setActive(id)

    /**
     * Create a new profile with the given [type] and optional [customLabel].
     *
     * The new profile starts inactive — call [setActive] to switch to it.
     * Returns the newly-created profile.
     */
    suspend fun create(
        type: ProfileType = ProfileType.PERSONAL,
        displayName: String = "",
        customLabel: String = ""
    ): Profile {
        val newProfile = Profile(
            id = UUID.randomUUID().toString(),
            profileType = type,
            customLabel = customLabel,
            displayName = displayName,
            isActive = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        profileDao.insertNew(newProfile)
        return newProfile
    }

    /**
     * Delete a profile by [id].
     *
     * Safety rules enforced here:
     * - Cannot delete the active profile — caller must switch first.
     *
     * @throws IllegalStateException if the profile is currently active.
     */
    suspend fun delete(id: String) {
        val active = profileDao.getActive()
        check(active?.id != id) {
            "Cannot delete the active profile. Switch to a different profile first."
        }
        profileDao.deleteProfile(id)
    }
}
