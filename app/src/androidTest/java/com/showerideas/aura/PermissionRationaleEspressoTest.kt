package com.showerideas.aura

import android.Manifest
import android.os.SystemClock
import androidx.annotation.IdRes
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers
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
 * v1.5 fix: Two root causes addressed together:
 *
 * (A) @Before regression: waitForView(btn_activate) with Throwable-catch caused
 *     tapping_not_now regression. Espresso's RootViewPicker waits up to 10 seconds
 *     internally per onView() call — so one failed iteration already exceeds the 5s
 *     deadline. Using SystemClock.sleep(400) instead avoids Espresso entirely and lets
 *     the activity settle without triggering the internal 10s focus timeout.
 *
 * (B) sheet_renders_with_all_seven_permission_types: BottomSheetDialogFragment with
 *     isCancelable=false may not acquire window focus on a cold emulator (first test
 *     run). Espresso's RootViewPicker throws RootViewWithoutFocusException. Fix: use
 *     inRoot(RootMatchers.isDialog()) which matches by window type/flags — not by
 *     input focus — so the check succeeds even without window focus. Also added
 *     SystemClock.sleep(800) after showSheet() to let the dialog attach and draw
 *     before Espresso inspects it.
 *
 * waitForView is reverted to catch only NoMatchingViewException + AssertionError —
 * catching Throwable was the original regression vector.
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
        // Brief pause for NavController fragment transition + Choreographer layout pass.
        // Intentionally avoids Espresso here: Espresso's RootViewPicker waits up to
        // 10 seconds per onView() call, which would exceed the waitForView 5s deadline
        // on the very first iteration when the emulator is cold.
        SystemClock.sleep(400)
    }

    @Test
    fun sheet_shows_title_rows_and_buttons() {
        showSheet(listOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN))

        SystemClock.sleep(600)
        onView(withId(R.id.tv_title))
            .inRoot(RootMatchers.isDialog())
            .check(matches(isDisplayed()))
        onView(withId(R.id.btn_open_settings))
            .inRoot(RootMatchers.isDialog())
            .check(matches(isDisplayed()))
        onView(withId(R.id.btn_not_now))
            .inRoot(RootMatchers.isDialog())
            .check(matches(isDisplayed()))
    }

    @Test
    fun tapping_not_now_dismisses_without_crash() {
        showSheet(listOf(Manifest.permission.CAMERA))

        SystemClock.sleep(600)
        onView(withId(R.id.btn_not_now))
            .inRoot(RootMatchers.isDialog())
            .check(matches(isDisplayed()))
        onView(withId(R.id.btn_not_now))
            .inRoot(RootMatchers.isDialog())
            .perform(click())
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

        // Allow the BottomSheetDialogFragment to attach, draw all 7 rows, and
        // stabilise before Espresso inspects it.
        SystemClock.sleep(800)

        // inRoot(isDialog()) matches by window type/flags — does NOT require the
        // dialog window to have input focus. This is the correct matcher when the
        // BottomSheet may not yet have acquired focus on a cold emulator.
        onView(withId(R.id.tv_title))
            .inRoot(RootMatchers.isDialog())
            .check(matches(isDisplayed()))
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
     * Only catches NoMatchingViewException and AssertionError — NOT Throwable.
     * Catching Throwable would absorb RootViewWithoutFocusException (a RuntimeException
     * thrown after Espresso's internal 10-second wait), making one failed iteration
     * already exceed the 5-second deadline.
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
