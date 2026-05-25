package com.showerideas.aura.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's own profile — what gets broadcast during an AURA exchange.
 *
 * Kept deliberately minimal: only fields the user explicitly chooses to share.
 * Sensitive fields are stored encrypted via EncryptedSharedPreferences.
 *
 * ## Multiple profiles (v2.2+)
 * The [profileType] column distinguishes Personal from Work (and Custom) cards.
 * The existing `"local_profile"` row retains its PK to preserve upgrade
 * compatibility; new profiles use UUID-based IDs.
 *
 * Only one profile can be active for exchange at a time — tracked by
 * [isActive]. The UI writes [isActive = true] to the selected profile and
 * [isActive = false] to all others atomically via [ProfileDao.setActive].
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
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Profile type — Personal, Work, or Custom.
     * Stored as the enum name (TEXT). Defaults to PERSONAL for the
     * existing single-profile row and for any upgrade path.
     * Added in DB v6 (MIGRATION_5_6).
     */
    @ColumnInfo(name = "profile_type")
    val profileType: ProfileType = ProfileType.PERSONAL,
    /**
     * Whether this is the currently-active profile for exchange.
     * Exactly one profile row should have isActive=true at all times.
     * The original "local_profile" row starts as active.
     * Added in DB v6 (MIGRATION_5_6).
     */
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    /**
     * User-supplied label for [ProfileType.CUSTOM] profiles (v2.3+).
     * Ignored for PERSONAL and WORK.
     */
    @ColumnInfo(name = "custom_label")
    val customLabel: String = "",
    /**
     * Monotonically-increasing profile version, auto-incremented by
     * [com.showerideas.aura.data.ProfileRepository.update] on every field save.
     * Sent to peers via [toShareableMap] so they can detect when a returning contact
     * updated their card — used by Phase 6.7 to surface the "Card updated" banner.
     * Added in DB v8 (MIGRATION_7_8).
     */
    @ColumnInfo(name = "version")
    val version: Int = 1
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
            // Version is always sent — peers use it to detect card updates (Phase 6.7).
            put("version", version.toString())
        }
    }
}
