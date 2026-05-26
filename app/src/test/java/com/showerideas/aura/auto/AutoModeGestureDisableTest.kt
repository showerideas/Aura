package com.showerideas.aura.auto

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * T36 — Verifies that AuraCarAppService correctly writes/clears the
 * gesture-disabled flag so downstream GestureAuthManager can respect it.
 */
@RunWith(RobolectricTestRunner::class)
class AutoModeGestureDisableTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `gesture disabled flag is false by default`() {
        assertFalse(
            prefs.getBoolean(AuraCarAppService.PREF_AUTO_MODE_GESTURE_DISABLED, false)
        )
    }

    @Test
    fun `writing disabled flag to true reflects in prefs`() {
        prefs.edit()
            .putBoolean(AuraCarAppService.PREF_AUTO_MODE_GESTURE_DISABLED, true)
            .commit()
        assertTrue(
            prefs.getBoolean(AuraCarAppService.PREF_AUTO_MODE_GESTURE_DISABLED, false)
        )
    }

    @Test
    fun `clearing disabled flag restores false`() {
        prefs.edit()
            .putBoolean(AuraCarAppService.PREF_AUTO_MODE_GESTURE_DISABLED, true)
            .commit()
        prefs.edit()
            .putBoolean(AuraCarAppService.PREF_AUTO_MODE_GESTURE_DISABLED, false)
            .commit()
        assertFalse(
            prefs.getBoolean(AuraCarAppService.PREF_AUTO_MODE_GESTURE_DISABLED, false)
        )
    }
}
