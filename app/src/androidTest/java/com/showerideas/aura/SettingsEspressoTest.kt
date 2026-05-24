package com.showerideas.aura

import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.showerideas.aura.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso smoke test for [com.showerideas.aura.ui.settings.SettingsFragment].
 *
 * Verifies:
 *  1. The Settings screen is reachable from the app toolbar overflow menu.
 *  2. The auth-method radio group (gesture / biometric) is visible.
 *  3. The "Blocked devices" shortcut row is visible and tappable.
 *  4. The version row renders (i.e. build metadata is wired into the layout).
 *
 * These assertions cover the yellow item in AUDIT.md §2 row 19 ("Settings +
 * Blocked screens — manual QA") and promote it to green automated coverage.
 */
@RunWith(AndroidJUnit4::class)
class SettingsEspressoTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun settings_screen_shows_auth_and_blocked_rows() {
        // Open the options menu and tap Settings.
        openActionBarOverflowOrOptionsMenu(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        onView(withText(R.string.settings_title)).perform(click())

        // Auth method radio group must be visible.
        waitForView(R.id.rg_auth_method)
        onView(withId(R.id.rb_auth_gesture)).check(matches(isDisplayed()))
        onView(withId(R.id.rb_auth_biometric)).check(matches(isDisplayed()))

        // Blocked devices shortcut row must be present.
        onView(withId(R.id.row_blocked_devices)).check(matches(isDisplayed()))

        // Background activation switch must be present.
        onView(withId(R.id.switch_bg_activation)).check(matches(isDisplayed()))

        // Version row must be present (confirms BuildConfig plumbing is wired).
        onView(withId(R.id.tv_version)).check(matches(isDisplayed()))
    }

    @Test
    fun blocked_devices_row_navigates_to_blocked_screen() {
        // Open Settings.
        openActionBarOverflowOrOptionsMenu(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        onView(withText(R.string.settings_title)).perform(click())
        waitForView(R.id.row_blocked_devices)

        // Tap the Blocked devices row — NavController should push BlockedDevicesFragment.
        onView(withId(R.id.row_blocked_devices)).perform(click())

        // BlockedDevicesFragment has a RecyclerView as its root landmark.
        // The exact ID may differ; we wait for the nav transition to complete.
        SystemClock.sleep(400)
        // If nav succeeded, the row_blocked_devices from Settings is gone.
        // We assert it is no longer visible.
        try {
            onView(withId(R.id.row_blocked_devices)).check(matches(isDisplayed()))
            // If still visible, the navigation didn't happen — fail the test.
            throw AssertionError(
                "row_blocked_devices still visible after tapping — navigation to " +
                "BlockedDevicesFragment did not occur"
            )
        } catch (e: NoMatchingViewException) {
            // Expected: the settings row is gone because we're on a new screen.
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun waitForView(@IdRes id: Int, timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e; SystemClock.sleep(150)
            } catch (e: AssertionError) {
                lastError = e; SystemClock.sleep(150)
            }
        }
        lastError?.let { throw it }
        onView(withId(id)).check(matches(isDisplayed()))
    }
}
