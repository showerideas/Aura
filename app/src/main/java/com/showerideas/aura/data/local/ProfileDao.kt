package com.showerideas.aura.data.local

import androidx.room.*
import com.showerideas.aura.model.Profile
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for the `profile` table.
 *
 * Single-profile legacy path
 * The original row keyed `"local_profile"` is preserved and remains the active
 * profile after DB v6 migration. All existing callers that used [get] / [observe]
 * continue to work — they are re-routed to the active-profile equivalents.
 *
 * Multi-profile additions (DB v6)
 * New DAO methods support listing, creating, switching, and deleting profiles.
 * Exactly one row must have `is_active = 1` at all times; [setActive] enforces
 * this atomically inside a `@Transaction`.
 *
 * Why abstract class + open @Transaction method?
 * Room + Kotlin 2.0 / KAPT requires two things for `@Transaction` suspend methods:
 * 1. The DAO must be an abstract class (not an interface) — KAPT generates `final`
 *    stubs for interface default methods, which Room rejects.
 * 2. The concrete `@Transaction` method must be marked `open` — Kotlin abstract-class
 *    methods are `final` by default, and KAPT emits `public final` in the Java stub,
 *    which Room's annotation processor also rejects with "must not be final".
 * 3. All bodyless Room-annotated functions must be explicitly marked `abstract` —
 *    unlike interfaces, abstract classes do not make functions implicitly abstract.
 */
@Dao
abstract class ProfileDao {

    // Backward-compatible single-profile queries (legacy callers)

    /**
     * Returns the currently-active profile as a Flow, or null if the table
     * is empty. Re-routes the old 'local_profile'-hardcoded query to the
     * active-profile column so all consumers stay on the live active card.
     */
    @Query("SELECT * FROM profile WHERE is_active = 1 LIMIT 1")
    abstract fun observe(): Flow<Profile?>

    /** Suspend variant of [observe] — one-shot read of the active profile. */
    @Query("SELECT * FROM profile WHERE is_active = 1 LIMIT 1")
    abstract suspend fun get(): Profile?

    // Multi-profile queries

    /** All profiles ordered by creation time — drives the profile switcher list. */
    @Query("SELECT * FROM profile ORDER BY createdAt ASC")
    abstract fun getAllProfiles(): Flow<List<Profile>>

    /** Observe the active profile (alias for [observe]). */
    @Query("SELECT * FROM profile WHERE is_active = 1 LIMIT 1")
    abstract fun observeActive(): Flow<Profile?>

    /** One-shot read of the active profile. */
    @Query("SELECT * FROM profile WHERE is_active = 1 LIMIT 1")
    abstract suspend fun getActive(): Profile?

    /**
     * Atomically make [id] the active profile.
     *
     * Runs inside a transaction: all rows are set inactive first, then the
     * target row is set active. This prevents a momentary state where no
     * profile is active between the two writes.
     */
    @Transaction
    open suspend fun setActive(id: String) {
        clearActive()
        activate(id)
    }

    @Query("UPDATE profile SET is_active = 0")
    abstract suspend fun clearActive()

    @Query("UPDATE profile SET is_active = 1 WHERE id = :id")
    abstract suspend fun activate(id: String)

    // Write operations

    /** Insert or replace a profile row (used for the legacy local_profile upsert). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(profile: Profile)

    /** Update an existing profile row. */
    @Update
    abstract suspend fun update(profile: Profile)

    /**
     * Insert a brand-new profile. Uses ABORT so a duplicate-key collision
     * surfaces as an exception rather than silently replacing an existing row.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertNew(profile: Profile)

    /**
     * Delete a profile by id.
     *
     * The caller (ProfileRepository) is responsible for ensuring at least one
     * profile row remains and that the deleted profile is not currently active.
     */
    @Query("DELETE FROM profile WHERE id = :id")
    abstract suspend fun deleteProfile(id: String)

    /** Nuke the entire table — only used during onboarding reset. */
    @Query("DELETE FROM profile")
    abstract suspend fun clear()
}

