package com.showerideas.aura.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's own profile — what gets broadcast during an AURA exchange.
 *
 * Kept deliberately minimal: only fields the user explicitly chooses to share.
 * Sensitive fields are stored encrypted via EncryptedSharedPreferences.
 */
@Entity(tableName = "profile")
data class Profile(
    @PrimaryKey val id: String = "local_profile",
    val displayName: String = "",
    val phone: String = "",
    val email: String = "",
    val company: String = "",
    val title: String = "",
    val website: String = "",
    val bio: String = "",
    /** Which fields are enabled for sharing (comma-separated field names) */
    val shareFields: String = "displayName,phone,email",
    val avatarUri: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toShareableMap(): Map<String, String> {
        val enabled = shareFields.split(",").map { it.trim() }.toSet()
        return buildMap {
            if ("displayName" in enabled && displayName.isNotBlank()) put("displayName", displayName)
            if ("phone" in enabled && phone.isNotBlank()) put("phone", phone)
            if ("email" in enabled && email.isNotBlank()) put("email", email)
            if ("company" in enabled && company.isNotBlank()) put("company", company)
            if ("title" in enabled && title.isNotBlank()) put("title", title)
            if ("website" in enabled && website.isNotBlank()) put("website", website)
            if ("bio" in enabled && bio.isNotBlank()) put("bio", bio)
        }
    }
}
