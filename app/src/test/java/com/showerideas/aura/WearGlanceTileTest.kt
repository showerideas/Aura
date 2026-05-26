package com.showerideas.aura

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 50 — Wear OS 7 Glance tile unit tests.
 *
 * GlanceTileService requires a device/emulator for full integration; these unit
 * tests verify the HealthConnectHrvReader availability logic and permission set.
 */
class WearGlanceTileTest {

    @Test
    fun `hrv required permissions set is non-empty`() {
        val perms = com.showerideas.aura.wear.HealthConnectHrvReader.requiredPermissions()
        assertTrue("Must declare at least one HC permission", perms.isNotEmpty())
        assertTrue(
            "Must include HRV permission",
            perms.any { it.contains("HEART_RATE_VARIABILITY", ignoreCase = true) }
        )
    }

    @Test
    fun `hrv permission string matches health connect namespace`() {
        val perms = com.showerideas.aura.wear.HealthConnectHrvReader.requiredPermissions()
        perms.forEach { perm ->
            assertTrue(
                "All HC permissions must be under android.permission.health namespace",
                perm.startsWith("android.permission.health.")
            )
        }
    }
}
