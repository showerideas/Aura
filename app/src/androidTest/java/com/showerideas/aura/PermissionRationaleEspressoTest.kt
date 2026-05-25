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
import com.showerideas.aura.ui.PermissionRationaleBottomSheet
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso smoke test for [PermissionRationaleBottomSheet].
 *
 * Verifies:
 *  1. The sheet can be shown with a non-empty permission list.
 *  2. The container for permission rows is visible.
 *  3. The "Open Settings" and "Not now" buttons are both present.
 *  4. Tapping "Not now" dismisses the sheet without crashing.
 *
 * v1.3 fix: ensureOnHome() now calls waitForView(btn_activate) to let the
 * HomeFragment fully settle after navigation before the sheet is shown.
 * Without this guard, the sheet could be displayed while the NavController
 * fragment transition is still running, leaving the activity window in a
 * continuous requestLayout state — triggering RootViewWithoutFocusException
 * in waitForView (which previously only caught NoMatchingViewException).
 * waitForView now catches all Throwable so it retries on focus-change errors.
 */
@RunWith(AndroidJUnit4::class)
class PermissionRationaleEspressoTest {

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
        // Wait for HomeFragment to fully settle before showing the bottom sheet.
        // Without this, the NavController fragment transition may still be running
        // when showSheet() is called, leaving the activity in continuous requestLayout
        // and causing RootViewWithoutFocusException in waitForView.
        waitForView(R.id.btn_activate)
    }

    @Test
    fun sheet_shows_title_rows_and_buttons() {
        showSheet(listOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN))

        waitForView(R.id.tv_title)
        onView(withId(R.id.tv_title)).check(matches(isDisplayed()))
        onView(withId(R.id.container_permission_rows)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_open_settings)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_not_now)).check(matches(isDisplayed()))
    }

    @Test
    fun tapping_not_now_dismisses_without_crash() {
        showSheet(listOf(Manifest.permission.CAMERA))

        waitForView(R.id.btn_not_now)
        onView(withId(R.id.btn_not_now)).perform(click())
        SystemClock.sleep(500)
    }

    @Test
    fun sheet_renders_with_all_seven_permission_types() {
        showSheet(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )

        waitForView(R.id.container_permission_rows)
        onView(withId(R.id.container_permission_rows)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_open_settings)).check(matches(isDisplayed()))
    }

    private fun navigateToHomeIfOnOnboarding() {
        activityRule.scenario.onActivity { activity ->
            val navController = Navigation.findNavController(activity, R.id.nav_host_fragment)
            if (navController.currentDestination?.id == R.id.onboardingFragment) {
                navController.navigate(R.id.action_onboarding_to_home)
            }
        }
    }

    private fun showSheet(permissions: List<String>) {
        activityRule.scenario.onActivity { activity ->
            val sheet = PermissionRationaleBottomSheet.newInstance(permissions)
            sheet.show(activity.supportFragmentManager, PermissionRationaleBottomSheet.TAG)
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
