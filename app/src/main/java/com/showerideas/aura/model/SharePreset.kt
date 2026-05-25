package com.showerideas.aura.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 9.1 — A named share preset that stores which contact fields to include
 * when starting an AURA exchange.
 *
 * Presets allow quick selection of field subsets (e.g., "Professional" = name +
 * email + company, "Minimal" = name only) without editing the full profile every
 * time.
 */
@Entity(tableName = "share_presets")
data class SharePreset(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    /** User-visible name for this preset (e.g., "Professional", "Minimal") */
    val name: String,
    /**
     * Comma-separated list of field keys to share (matches Profile field names):
     * displayName, phone, email, company, title, website, bio, avatarUri
     */
    val fieldSet: String,
    /** Epoch millis of last use — used to sort the quick-select list by recency. */
    val lastUsedAt: Long = 0L,
    /** True for the 3 default presets shipped with the app; false for user-created. */
    val isDefault: Boolean = false
) {
    companion object {
        /** Default presets — inserted on first run via a Room pre-populate callback. */
        val DEFAULTS = listOf(
            SharePreset(name = "Full card",
                        fieldSet = "displayName,phone,email,company,title,website,bio,avatarUri",
                        isDefault = true),
            SharePreset(name = "Professional",
                        fieldSet = "displayName,email,company,title",
                        isDefault = true),
            SharePreset(name = "Minimal",
                        fieldSet = "displayName,phone",
                        isDefault = true),
        )
    }
}
