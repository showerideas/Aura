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
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.showerideas.aura.ui.MainActivity
import com.showerideas.aura.ui.PermissionRationaleBottomSheet
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
 * Uses [ActivityScenarioRule] + scenario.onActivity to show the bottom
 * sheet on the main thread via the activity's supportFragmentManager —
 * the same way the production code displays it — without needing a
 * dedicated fragment-testing artifact.
 *
 * Covers AUDIT.md §2 row 03 ("Permission-rationale sheet — UI test pending").
 */
@RunWith(AndroidJUnit4::class)
class PermissionRationaleEspressoTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun sheet_shows_title_rows_and_buttons() {
        showSheet(listOf(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_SCAN))

        // Title is visible.
        waitForView(R.id.tv_title)
        onView(withId(R.id.tv_title)).check(matches(isDisplayed()))

        // Container that receives the dynamically-built permission rows.
        onView(withId(R.id.container_permission_rows)).check(matches(isDisplayed()))

        // Both action buttons must be present.
        onView(withId(R.id.btn_open_settings)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_not_now)).check(matches(isDisplayed()))
    }

    @Test
    fun tapping_not_now_dismisses_without_crash() {
        showSheet(listOf(Manifest.permission.CAMERA))

        waitForView(R.id.btn_not_now)
        // isCancelable = false means the sheet cannot be swiped away, but the
        // "Not now" button still calls dismiss() + activity.finish().
        // We interact with it before it finishes the activity.
        onView(withId(R.id.btn_not_now)).perform(click())

        // After the activity finishes the test completes — no crash == pass.
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun showSheet(permissions: List<String>) {
        activityRule.scenario.onActivity { activity ->
            val sheet = PermissionRationaleBottomSheet.newInstance(permissions)
            sheet.show(activity.supportFragmentManager, PermissionRationaleBottomSheet.TAG)
        }
    }

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
