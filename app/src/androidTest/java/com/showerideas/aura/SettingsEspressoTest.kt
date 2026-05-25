package com.showerideas.aura

import android.Manifest
import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.navigation.Navigation
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.showerideas.aura.ui.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso smoke test for [com.showerideas.aura.ui.settings.SettingsFragment].
 *
 * Verifies:
 *  1. The Settings screen is reachable from the app toolbar Settings gear icon.
 *  2. The auth-method radio group (gesture / biometric) is visible.
 *  3. The "Blocked devices" shortcut row is visible and tappable.
 *  4. The version row renders (i.e. build metadata is wired into the layout).
 *
 * v1.4 fix: menu_main.xml uses showAsAction="always" for the Settings item —
 * it renders as a gear icon directly in the Toolbar, not in an overflow dropdown.
 * openActionBarOverflowOrOptionsMenu() was therefore wrong here; replaced with
 * onView(withId(R.id.action_settings)).perform(click()) to target the icon directly.
 * Also added waitForView(btn_activate) in ensureOnHome() so the Toolbar menu is
 * fully inflated before the click is attempted.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEspressoTest {

    @get:Rule(order = 0)
    val grantPermissionsRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA
    )

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun ensureOnHome() {
        navigateToHomeIfOnOnboarding()
        // Wait for HomeFragment to settle so the Toolbar menu is fully inflated.
        waitForView(R.id.btn_activate)
    }

    @Test
    fun settings_screen_shows_auth_and_blocked_rows() {
        // Settings icon (gear) is showAsAction="always" — click it directly.
        onView(withId(R.id.action_settings)).perform(click())

        waitForView(R.id.rg_auth_method)
        onView(withId(R.id.rb_auth_gesture)).check(matches(isDisplayed()))
        onView(withId(R.id.rb_auth_biometric)).check(matches(isDisplayed()))
        onView(withId(R.id.row_blocked_devices)).check(matches(isDisplayed()))
        onView(withId(R.id.switch_bg_activation)).check(matches(isDisplayed()))
        onView(withId(R.id.tv_version)).check(matches(isDisplayed()))
    }

    @Test
    fun blocked_devices_row_navigates_to_blocked_screen() {
        // Settings icon (gear) is showAsAction="always" — click it directly.
        onView(withId(R.id.action_settings)).perform(click())
        waitForView(R.id.row_blocked_devices)

        onView(withId(R.id.row_blocked_devices)).perform(click())

        SystemClock.sleep(400)
        try {
            onView(withId(R.id.row_blocked_devices)).check(matches(isDisplayed()))
            throw AssertionError(
                "row_blocked_devices still visible after tapping — navigation to " +
                "BlockedDevicesFragment did not occur"
            )
        } catch (e: NoMatchingViewException) {
            // Expected: settings row is gone because we navigated to a new screen.
        }
    }

    private fun navigateToHomeIfOnOnboarding() {
        activityRule.scenario.onActivity { activity ->
            val navController = Navigation.findNavController(activity, R.id.nav_host_fragment)
            if (navController.currentDestination?.id == R.id.onboardingFragment) {
                navController.navigate(R.id.action_onboarding_to_home)
            }
        }
    }

    /**
     * Polls until [id] is displayed or [timeoutMs] elapses.
     * Catches all Throwable — including RootViewWithoutFocusException — so
     * transient window-focus changes trigger a retry rather than a hard failure.
     */
    private fun waitForView(@IdRes id: Int, timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: Throwable) {
                lastError = e
                SystemClock.sleep(150)
            }
        }
        lastError?.let { throw it }
        onView(withId(id)).check(matches(isDisplayed()))
    }
}
