package com.showerideas.aura.data

import com.showerideas.aura.data.local.ProfileDao
import com.showerideas.aura.model.Profile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao
) {
    val profile: Flow<Profile?> = profileDao.observe()

    suspend fun get(): Profile? = profileDao.get()

    suspend fun save(profile: Profile) = profileDao.insert(profile)

    suspend fun update(profile: Profile) {
        val updated = profile.copy(updatedAt = System.currentTimeMillis())
        profileDao.update(updated)
    }

    suspend fun getOrCreate(): Profile {
        return profileDao.get() ?: Profile().also { profileDao.insert(it) }
    }
}
