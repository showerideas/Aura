package com.showerideas.aura.model

/**
 * Distinguishes the purpose of a [Profile] — what context the user intends
 * to share it in.
 *
 * Stored as a TEXT column in Room (name of the enum constant, e.g. "PERSONAL").
 *
 * Design rationale
 * AURA users often want to share different information in professional vs.
 * personal settings. Rather than requiring them to edit their profile before
 * each exchange, multiple profiles let them pick the right "card" at exchange
 * time. The active profile is selected by the gesture that unlocks the exchange
 * (gesture-per-profile is implemented in).
 *
 * Values
 * - [PERSONAL] — the default, for social/personal contact sharing.
 * - [WORK]     — professional information (company, title, work email/phone).
 * - [CUSTOM]   — user-defined label stored in [Profile.customLabel]; v2.3+.
 */
enum class ProfileType {
    /** Default profile — social / personal card. */
    PERSONAL,
    /** Professional card — company, title, work contact details. */
    WORK,
    /** User-defined — label stored in Profile.customLabel (v2.3+). */
    CUSTOM;

    /**
     * A human-readable display name for this type.
     * Resolved in the UI using [R.string.profile_type_*] instead of
     * this property so the label is localised. This is the fallback
     * used in logs and debug output.
     */
    fun displayName(): String = when (this) {
        PERSONAL -> "Personal"
        WORK     -> "Work"
        CUSTOM   -> "Custom"
    }
}
