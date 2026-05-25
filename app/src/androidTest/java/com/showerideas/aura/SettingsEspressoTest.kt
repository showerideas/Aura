package com.showerideas.aura

import android.Manifest
import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
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
 * v1.5 fix: Choreographer layout timing.
 *
 * Root cause: after onView(action_settings).perform(click()), NavController adds
 * SettingsFragment to the back stack and its view enters the hierarchy. However,
 * Choreographer has not yet fired a VSYNC layout traversal. waitForView calls
 * onView().check() in a polling loop — each onView() call posts to the main thread
 * via waitForIdleSync(), which PREVENTS Choreographer callbacks from running because
 * the main thread looper never goes fully idle. Result: getGlobalVisibleRect() stays
 * empty for the entire 5-second window, and every isDisplayed() check fails.
 *
 * Fix: SystemClock.sleep(500) after the settings click gives Choreographer 500ms of
 * uninterrupted main-thread time to run the layout traversal. After that the view
 * bounds are populated and isDisplayed() succeeds.
 *
 * scrollTo() is added before each isDisplayed() assertion to handle views that may
 * be below the fold on a small emulator screen (fragment_settings.xml root is
 * ScrollView).
 *
 * waitForView is reverted to catch only NoMatchingViewException + AssertionError —
 * the Throwable-catch variant caused Espresso's internal 10s focus-wait to absorb
 * the entire deadline.
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
        // Main window always has focus — waitForView is safe here.
        waitForView(R.id.btn_activate)
    }

    @Test
    fun settings_screen_shows_auth_and_blocked_rows() {
        // Settings icon (gear) is showAsAction="always" — click it directly.
        onView(withId(R.id.action_settings)).perform(click())
        // Allow Choreographer to process the fragment layout traversal.
        // onView() calls waitForIdleSync() which prevents Choreographer from
        // running if called immediately after navigate(); sleep avoids this.
        SystemClock.sleep(500)

        onView(withId(R.id.rb_auth_gesture)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.rb_auth_biometric)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.row_blocked_devices)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.switch_bg_activation)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.tv_version)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun blocked_devices_row_navigates_to_blocked_screen() {
        // Settings icon (gear) is showAsAction="always" — click it directly.
        onView(withId(R.id.action_settings)).perform(click())
        // Allow Choreographer layout pass to complete before interacting.
        SystemClock.sleep(500)

        onView(withId(R.id.row_blocked_devices)).perform(scrollTo(), click())

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
     * Only catches NoMatchingViewException and AssertionError — NOT Throwable.
     * Catching Throwable would absorb RootViewWithoutFocusException (thrown after
     * Espresso's internal 10-second wait), making one failed iteration already
     * exceed the 5-second deadline.
     */
    private fun waitForView(@IdRes id: Int, timeoutMs: Long = 5_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                onView(withId(id)).check(matches(isDisplayed()))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e
                SystemClock.sleep(150)
            } catch (e: AssertionError) {
                lastError = e
                SystemClock.sleep(150)
            }
        }
        lastError?.let { throw it }
        onView(withId(id)).check(matches(isDisplayed()))
    }
}
