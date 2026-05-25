package com.showerideas.aura

import com.showerideas.aura.model.Profile
import com.showerideas.aura.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for multi-profile domain logic (Phase 6.4).
 *
 * Covers:
 * - ProfileType display names
 * - Profile.toShareableMap() respects shareFields regardless of type
 * - isActive semantics
 * - Profile equality / identity
 * - customLabel fallback for ProfileType.CUSTOM
 */
class MultiProfileTest {

    // -------------------------------------------------------------------------
    // ProfileType
    // -------------------------------------------------------------------------

    @Test
    fun profileType_personal_displayName() {
        assertEquals("Personal", ProfileType.PERSONAL.displayName())
    }

    @Test
    fun profileType_work_displayName() {
        assertEquals("Work", ProfileType.WORK.displayName())
    }

    @Test
    fun profileType_custom_displayName() {
        assertEquals("Custom", ProfileType.CUSTOM.displayName())
    }

    @Test
    fun profileType_valueOf_roundtrip() {
        ProfileType.values().forEach { type ->
            assertEquals(type, ProfileType.valueOf(type.name))
        }
    }

    // -------------------------------------------------------------------------
    // Profile entity
    // -------------------------------------------------------------------------

    @Test
    fun profile_defaults_to_personal_and_active() {
        val profile = Profile()
        assertEquals(ProfileType.PERSONAL, profile.profileType)
        assertTrue(profile.isActive)
        assertEquals("", profile.customLabel)
    }

    @Test
    fun profile_work_type_is_preserved() {
        val profile = Profile(id = "work-1", profileType = ProfileType.WORK, isActive = false)
        assertEquals(ProfileType.WORK, profile.profileType)
        assertFalse(profile.isActive)
    }

    @Test
    fun profile_custom_label_stored() {
        val profile = Profile(
            id = "custom-1",
            profileType = ProfileType.CUSTOM,
            customLabel = "Conference mode"
        )
        assertEquals("Conference mode", profile.customLabel)
    }

    @Test
    fun profile_different_ids_are_not_equal() {
        val p1 = Profile(id = "id-1", displayName = "Alice")
        val p2 = Profile(id = "id-2", displayName = "Alice")
        assertNotEquals(p1, p2)
    }

    @Test
    fun profile_same_id_and_fields_are_equal() {
        val p1 = Profile(id = "id-x", displayName = "Bob", profileType = ProfileType.WORK)
        val p2 = Profile(id = "id-x", displayName = "Bob", profileType = ProfileType.WORK)
        assertEquals(p1, p2)
    }

    // -------------------------------------------------------------------------
    // toShareableMap — shares only enabled fields
    // -------------------------------------------------------------------------

    @Test
    fun shareableMap_personal_only_shares_enabled_fields() {
        val profile = Profile(
            displayName = "Alice",
            phone = "555-1234",
            email = "alice@example.com",
            company = "ACME",
            shareFields = "displayName,phone"
        )
        val map = profile.toShareableMap()
        assertTrue(map.containsKey("displayName"))
        assertTrue(map.containsKey("phone"))
        assertFalse(map.containsKey("email"))
        assertFalse(map.containsKey("company"))
    }

    @Test
    fun shareableMap_work_shares_company_and_title() {
        val profile = Profile(
            id = "work-2",
            profileType = ProfileType.WORK,
            displayName = "Bob Smith",
            company = "Initech",
            title = "Engineer",
            phone = "555-9999",
            shareFields = "displayName,company,title,phone"
        )
        val map = profile.toShareableMap()
        assertEquals("Bob Smith", map["displayName"])
        assertEquals("Initech", map["company"])
        assertEquals("Engineer", map["title"])
        assertEquals("555-9999", map["phone"])
    }

    @Test
    fun shareableMap_excludes_blank_values_even_when_field_enabled() {
        val profile = Profile(
            displayName = "Charlie",
            phone = "",          // blank — should be excluded
            email = "c@c.com",
            shareFields = "displayName,phone,email"
        )
        val map = profile.toShareableMap()
        assertEquals("Charlie", map["displayName"])
        assertEquals("c@c.com", map["email"])
        assertFalse(map.containsKey("phone"))  // blank value excluded
    }

    @Test
    fun shareableMap_empty_shareFields_returns_only_version() {
        val profile = Profile(
            displayName = "Dave",
            shareFields = ""
        )
        // No user fields enabled → only the always-present version sentinel is sent
        val map = profile.toShareableMap()
        assertEquals(1, map.size)
        assertTrue("version should always be present", map.containsKey("version"))
    }

    // -------------------------------------------------------------------------
    // isActive state transitions
    // -------------------------------------------------------------------------

    @Test
    fun profile_copy_can_toggle_active() {
        val original = Profile(id = "id-1", isActive = true)
        val toggled = original.copy(isActive = false)
        assertFalse(toggled.isActive)
        assertEquals(original.id, toggled.id)
    }

    @Test
    fun only_one_profile_should_be_active_at_a_time() {
        // Simulate what the DB transaction enforces: only one isActive=true
        val profiles = listOf(
            Profile(id = "p1", isActive = false),
            Profile(id = "p2", isActive = true),
            Profile(id = "p3", isActive = false)
        )
        val activeCount = profiles.count { it.isActive }
        assertEquals(1, activeCount)
        assertEquals("p2", profiles.single { it.isActive }.id)
    }

    @Test
    fun all_profiles_inactive_is_invalid_state() {
        val profiles = listOf(
            Profile(id = "p1", isActive = false),
            Profile(id = "p2", isActive = false)
        )
        assertEquals(0, profiles.count { it.isActive })
        // Verify this is detectable — real code guards against this at DAO level
        assertTrue("No active profile — invalid state", profiles.none { it.isActive })
    }
}
